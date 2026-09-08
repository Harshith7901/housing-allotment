package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.dedup.Dedup;
import dev.harshith.housing.core.dedup.Deduplicator;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.DuplicateLinkEntity;
import dev.harshith.housing.persistence.ReviewDecision;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.persistence.repo.DuplicateLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the matcher over a scheme's applications and manages the human review queue.
 *
 * <p>The service does three things the core matcher deliberately does not:
 *
 * <ol>
 *   <li><b>Persists the evidence.</b> Every pair the matcher considered plausible is
 *       stored with its score and its per-field breakdown, whether it was auto-linked or
 *       queued, so a contested merge can be explained years later.</li>
 *   <li><b>Supersedes rather than deletes.</b> A duplicate keeps its row, its id and its
 *       history; it simply stops carrying a ticket and points at the application that
 *       does.</li>
 *   <li><b>Blocks the freeze while the queue is open.</b> A pending review is an
 *       unanswered question about whether one household has two tickets, and freezing the
 *       roll with that question open is exactly what makes the result unexplainable.</li>
 * </ol>
 */
@Service
public class DeduplicationService {

    private final ApplicationRepository applications;
    private final DuplicateLinkRepository links;
    private final SchemeService schemes;
    private final AuditService audit;
    private final Clock clock;

    public DeduplicationService(ApplicationRepository applications,
                                DuplicateLinkRepository links,
                                SchemeService schemes,
                                AuditService audit,
                                Clock clock) {
        this.applications = applications;
        this.links = links;
        this.schemes = schemes;
        this.audit = audit;
        this.clock = clock;
    }

    public record Summary(int applications,
                          int comparisons,
                          int autoLinked,
                          int queuedForReview,
                          int clusters,
                          long supersededApplications,
                          List<String> notes) {
    }

    @Transactional
    public Summary run(Actor actor, String schemeCode) {
        actor.require(Role.VERIFIER, Role.SCHEME_ADMIN);
        schemes.requirePhase(schemeCode, SchemePhase.DEDUPLICATION);

        List<ApplicationEntity> rows = applications.findBySchemeCodeOrderByApplicationIdAsc(schemeCode);
        List<Dedup.ApplicantRecord> records = new ArrayList<>(rows.size());
        Map<String, ApplicationEntity> byId = new LinkedHashMap<>();
        for (ApplicationEntity row : rows) {
            byId.put(row.getApplicationId(), row);
            records.add(new Dedup.ApplicantRecord(
                    row.getApplicationId(), row.getChannel(), row.getSubmittedAt(),
                    row.getFullName(), row.getRelativeName(), row.getGovernmentIdHash(),
                    row.getPhone(), row.getDateOfBirth(), row.getAddressLine(), row.getWardCode()));
        }

        Dedup.MatchWeights weights = Dedup.MatchWeights.standard();
        Dedup.DedupReport report = Deduplicator.run(records, weights);

        // Auto-linked pairs are recorded as already decided; the matcher's certainty is
        // itself the decision, and it is attributed to the matcher rather than to a person.
        for (Dedup.CandidatePair pair : report.autoLinked()) {
            links.save(toEntity(schemeCode, pair, ReviewDecision.SAME_HOUSEHOLD,
                    "deduplication-service", "auto-linked above the published threshold of "
                            + weights.autoLinkThreshold()));
        }
        for (Dedup.CandidatePair pair : report.needsReview()) {
            links.save(toEntity(schemeCode, pair, ReviewDecision.PENDING, null, null));
        }

        long superseded = 0;
        for (Dedup.Cluster cluster : report.clusters()) {
            for (String member : cluster.memberApplicationIds()) {
                ApplicationEntity row = byId.get(member);
                row.setClusterId(cluster.clusterId());
                if (!member.equals(cluster.primaryApplicationId())) {
                    row.setStatus(ApplicationStatus.SUPERSEDED);
                    row.setSupersededByApplicationId(cluster.primaryApplicationId());
                    row.setStatusReason("Confirmed as a re-submission of "
                            + cluster.primaryApplicationId() + "; " + cluster.basis());
                    superseded++;
                }
                row.setUpdatedAt(clock.instant());
                applications.save(row);
            }
        }

        // Anything still awaiting a human gets marked, so the freeze gate can see it.
        for (Dedup.CandidatePair pair : report.needsReview()) {
            markPending(byId.get(pair.leftApplicationId()));
            markPending(byId.get(pair.rightApplicationId()));
        }

        Summary summary = new Summary(report.recordCount(), report.comparisons(),
                report.autoLinked().size(), report.needsReview().size(),
                report.distinctHouseholds(), superseded, report.notes());

        audit.append(actor, "DEDUPLICATION_COMPLETED", "SCHEME", schemeCode,
                "applications=" + summary.applications()
                        + " comparisons=" + summary.comparisons()
                        + " autoLinked=" + summary.autoLinked()
                        + " queued=" + summary.queuedForReview()
                        + " clusters=" + summary.clusters()
                        + " superseded=" + summary.supersededApplications()
                        + " weights=" + weights);
        return summary;
    }

    @Transactional(readOnly = true)
    public List<DuplicateLinkEntity> reviewQueue(String schemeCode) {
        return links.findBySchemeCodeAndReviewDecisionOrderByScoreDesc(schemeCode, ReviewDecision.PENDING);
    }

    @Transactional(readOnly = true)
    public long pendingReviewCount(String schemeCode) {
        return links.countBySchemeCodeAndReviewDecision(schemeCode, ReviewDecision.PENDING);
    }

    /**
     * A reviewer's decision on one queued pair.
     *
     * <p>Merging is transitive: if the superseded application was itself the primary of a
     * cluster, every member of that cluster is re-pointed at the surviving primary. Doing
     * that here rather than by re-running the matcher keeps the reviewer's decision as the
     * cause of the change, which is what the audit entry has to be able to say.
     */
    @Transactional
    public DuplicateLinkEntity decide(Actor actor, String linkId, ReviewDecision decision, String note) {
        actor.require(Role.VERIFIER, Role.SCHEME_ADMIN);
        if (decision == ReviewDecision.PENDING) {
            throw new IllegalArgumentException("a review must resolve to SAME_HOUSEHOLD or DIFFERENT_HOUSEHOLDS");
        }
        DuplicateLinkEntity link = links.findById(linkId)
                .orElseThrow(() -> new NotFoundException("no such duplicate link: " + linkId));
        schemes.requirePhase(link.getSchemeCode(), SchemePhase.DEDUPLICATION, SchemePhase.VERIFICATION);
        if (link.getReviewDecision() != ReviewDecision.PENDING) {
            throw new IllegalArgumentException("duplicate link " + linkId + " was already decided as "
                    + link.getReviewDecision() + " by " + link.getReviewedBy());
        }

        ApplicationEntity left = require(link.getLeftApplicationId());
        ApplicationEntity right = require(link.getRightApplicationId());

        // The reviewer's verdict is recorded first, so that the "are any matches still
        // pending for this applicant?" question below sees this link as decided. Recording
        // it afterwards would leave an application stuck in PENDING_DUPLICATE_REVIEW
        // because of the very link that was just resolved, and the roll freeze gate would
        // refuse a scheme whose queue is in fact empty.
        link.setReviewDecision(decision);
        link.setReviewedBy(actor.id());
        link.setReviewedAt(clock.instant());
        link.setReviewNote(note);
        links.saveAndFlush(link);

        if (decision == ReviewDecision.SAME_HOUSEHOLD) {
            // The primary must be the earliest submission across BOTH merged clusters, not
            // merely the earlier of the two applications named on this link.
            //
            // Consider an auto-linked cluster {X, Y} with X earliest, and a queued link
            // Y~Z where Z was submitted before Y but after X. Choosing between Y and Z
            // alone picks Z, and the loop then supersedes X - the earliest submission and
            // the current ticket holder - in favour of a later one. That contradicts the
            // published rule and quietly moves a household's ticket.
            Set<ApplicationEntity> members = new LinkedHashSet<>();
            members.addAll(clusterMembers(left));
            members.addAll(clusterMembers(right));

            ApplicationEntity primary = members.iterator().next();
            for (ApplicationEntity candidate : members) {
                primary = earlier(primary, candidate);
            }
            String targetCluster = "CL-" + primary.getApplicationId();

            for (ApplicationEntity row : members) {
                row.setClusterId(targetCluster);
                if (row.getApplicationId().equals(primary.getApplicationId())) {
                    // The surviving application carries the ticket; clear any earlier
                    // supersession so it is not left pointing at a row that now has none.
                    row.setSupersededByApplicationId(null);
                    if (row.getStatus() == ApplicationStatus.SUPERSEDED) {
                        row.setStatus(ApplicationStatus.RECEIVED);
                        row.setStatusReason("Carries the household's ticket after review of "
                                + members.size() + " linked applications");
                    }
                } else {
                    row.setStatus(ApplicationStatus.SUPERSEDED);
                    row.setSupersededByApplicationId(primary.getApplicationId());
                    row.setStatusReason("Confirmed on review as the same household as "
                            + primary.getApplicationId());
                }
                row.setUpdatedAt(clock.instant());
                applications.save(row);
            }
            clearPendingIfResolved(primary);
        } else {
            clearPendingIfResolved(left);
            clearPendingIfResolved(right);
        }

        audit.append(actor, "DUPLICATE_REVIEWED", "DUPLICATE_LINK", linkId,
                "decision=" + decision + " score=" + link.getScore()
                        + " left=" + link.getLeftApplicationId()
                        + " right=" + link.getRightApplicationId()
                        + " note=" + (note == null ? "-" : note));
        return link;
    }

    private void markPending(ApplicationEntity row) {
        if (row != null && row.getStatus() == ApplicationStatus.RECEIVED) {
            row.setStatus(ApplicationStatus.PENDING_DUPLICATE_REVIEW);
            row.setStatusReason("A plausible duplicate match is awaiting review");
            row.setUpdatedAt(clock.instant());
            applications.save(row);
        }
    }

    private void clearPendingIfResolved(ApplicationEntity row) {
        if (row.getStatus() != ApplicationStatus.PENDING_DUPLICATE_REVIEW) {
            return;
        }
        boolean stillPending = links
                .findByLeftApplicationIdOrRightApplicationId(row.getApplicationId(), row.getApplicationId())
                .stream()
                .anyMatch(l -> l.getReviewDecision() == ReviewDecision.PENDING);
        if (!stillPending) {
            row.setStatus(ApplicationStatus.RECEIVED);
            row.setStatusReason("All duplicate matches resolved as different households");
            row.setUpdatedAt(clock.instant());
            applications.save(row);
        }
    }

    /**
     * Every application already linked to this one, including itself. A row with no cluster
     * yet stands alone.
     */
    private List<ApplicationEntity> clusterMembers(ApplicationEntity row) {
        if (row.getClusterId() == null) {
            return List.of(row);
        }
        List<ApplicationEntity> members =
                applications.findByClusterIdOrderByApplicationIdAsc(row.getClusterId());
        return members.isEmpty() ? List.of(row) : members;
    }

    private ApplicationEntity require(String applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("no such application: " + applicationId));
    }

    /** Earliest submission wins; ties broken by application id. */
    private ApplicationEntity earlier(ApplicationEntity a, ApplicationEntity b) {
        Comparator<ApplicationEntity> order = Comparator
                .comparing(ApplicationEntity::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ApplicationEntity::getApplicationId);
        return order.compare(a, b) <= 0 ? a : b;
    }

    private DuplicateLinkEntity toEntity(String schemeCode,
                                         Dedup.CandidatePair pair,
                                         ReviewDecision decision,
                                         String reviewedBy,
                                         String note) {
        DuplicateLinkEntity entity = new DuplicateLinkEntity();
        entity.setLinkId(DuplicateLinkEntity.idFor(pair.leftApplicationId(), pair.rightApplicationId()));
        entity.setSchemeCode(schemeCode);
        entity.setLeftApplicationId(pair.leftApplicationId());
        entity.setRightApplicationId(pair.rightApplicationId());
        entity.setMethod(pair.method());
        entity.setScore(pair.score());
        entity.setDecision(pair.decision());
        entity.setFeatures(renderFeatures(pair));
        entity.setRationale(pair.rationale());
        entity.setSharedBlockingKeys(String.join(",", pair.sharedBlockingKeys()));
        entity.setDetectedAt(clock.instant());
        entity.setReviewDecision(decision);
        entity.setReviewedBy(reviewedBy);
        entity.setReviewedAt(reviewedBy == null ? null : clock.instant());
        entity.setReviewNote(note);
        return entity;
    }

    private String renderFeatures(Dedup.CandidatePair pair) {
        StringBuilder sb = new StringBuilder();
        for (Dedup.FeatureScore f : pair.features()) {
            sb.append(f.feature())
              .append(f.available() ? "" : " (missing, weight redistributed)")
              .append(": score=").append(String.format(java.util.Locale.ROOT, "%.4f", f.score()))
              .append(" weight=").append(String.format(java.util.Locale.ROOT, "%.2f", f.weight()))
              .append(" [").append(f.detail()).append("]\n");
        }
        return sb.toString();
    }
}
