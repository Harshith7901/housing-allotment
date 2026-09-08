package dev.harshith.housing.service;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.util.Hashing;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawSelectionEntity;
import dev.harshith.housing.persistence.DrawStatus;
import dev.harshith.housing.persistence.EligibilityCheckEntity;
import dev.harshith.housing.persistence.UnitAllotmentEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the answer to "why not me?".
 *
 * <p>This is the endpoint the whole design exists to make possible, so it is worth saying
 * what it is <em>for</em>. An applicant who lost has three questions, and a scheme that
 * cannot answer all three deserves to be doubted:
 *
 * <ol>
 *   <li><b>Was I in the draw at all?</b> If not, which published condition did I fail, on
 *       what evidence, decided by whom — or which application was I merged into, at what
 *       match score.</li>
 *   <li><b>Where did I come?</b> My ticket, my pool, my rank among how many, for how many
 *       seats, and my waitlist position if I have one.</li>
 *   <li><b>How do I check you are not lying?</b> The seed, the arithmetic that produced my
 *       ticket from it, and the hashes of the roll and the result — enough to recompute my
 *       own ticket with {@code sha256sum} and to run the full verifier over the bundle.</li>
 * </ol>
 *
 * <p>Note the third: the response hands over the material to falsify it. A system that
 * only tells you that you lost is asking for trust; one that tells you how to check is not.
 */
@Service
public class ExplanationService {

    private final IntakeService intake;
    private final EligibilityService eligibility;
    private final DrawService draws;
    private final AllotmentService allotments;
    private final DeduplicationService deduplication;

    public ExplanationService(IntakeService intake,
                              EligibilityService eligibility,
                              DrawService draws,
                              AllotmentService allotments,
                              DeduplicationService deduplication) {
        this.intake = intake;
        this.eligibility = eligibility;
        this.draws = draws;
        this.allotments = allotments;
        this.deduplication = deduplication;
    }

    public record EligibilityLine(String checkCode, boolean passed, String reason,
                                  String evidenceRef, String decidedBy, String decidedAt) {
    }

    public record DrawLine(String drawId,
                           String outcome,
                           String poolCode,
                           int seatsInPool,
                           int candidatesInPool,
                           int rankInPool,
                           Integer waitlistPosition,
                           int residencyTier,
                           String ticketHex,
                           String reasonCode,
                           String reasonText) {
    }

    public record AllotmentLine(String allotmentId, String unitId, String unitType, String block,
                                int preferenceRankHonoured, String status, String basis) {
    }

    public record HowToVerify(String seedHex,
                              String rollHash,
                              String resultHash,
                              String ticketFormula,
                              String shellCommand,
                              String bundleUrl,
                              String verifierCommand) {
    }

    public record Explanation(String applicationId,
                              String schemeCode,
                              String status,
                              String statusReason,
                              String clusterId,
                              String supersededByApplicationId,
                              List<EligibilityLine> eligibilityChecks,
                              List<DrawLine> draws,
                              List<AllotmentLine> allotments,
                              HowToVerify howToVerify) {
    }

    @Transactional(readOnly = true)
    public Explanation explain(String applicationId) {
        ApplicationEntity application = intake.get(applicationId);

        List<EligibilityLine> checks = new ArrayList<>();
        for (EligibilityCheckEntity c : eligibility.forApplication(applicationId)) {
            checks.add(new EligibilityLine(c.getCheckCode(), c.isPassed(), c.getReason(),
                    c.getEvidenceRef(), c.getDecidedBy(), String.valueOf(c.getDecidedAt())));
        }

        List<DrawLine> drawLines = new ArrayList<>();
        HowToVerify verify = null;

        for (DrawEntity draw : draws.history(application.getSchemeCode())) {
            if (draw.getStatus() != DrawStatus.PUBLISHED) {
                // An executed but unpublished result is not yet anybody's to see. Telling
                // one applicant before the announcement is exactly the leak that makes a
                // draw contestable.
                continue;
            }
            // Not being on this draw's roll is an ordinary answer, not an error: the
            // ineligible and the superseded have no line in it. Asked, never caught -- a
            // NotFoundException thrown by a @Transactional lookup marks this read-only
            // transaction rollback-only, and handling it here would still fail the whole
            // request at commit with UnexpectedRollbackException.
            DrawSelectionEntity selection =
                    draws.findSelection(draw.getDrawId(), applicationId).orElse(null);
            if (selection == null) {
                continue;
            }
            Draw.DrawOutcome outcome = draws.outcome(draw.getDrawId());
            Draw.PoolResult pool = outcome.pool(selection.getPoolCode());

            drawLines.add(new DrawLine(
                    draw.getDrawId(),
                    selection.getOutcome().name(),
                    pool.poolCode(),
                    pool.seats(),
                    pool.candidateCount(),
                    selection.getRankInPool(),
                    selection.getWaitlistPosition(),
                    selection.getResidencyTier(),
                    selection.getTicketHex(),
                    selection.getReasonCode().name(),
                    selection.getReasonText()));

            String formula = "SHA256(\"ticket/1|" + draw.getSeedHex() + "|" + applicationId + "\")";
            verify = new HowToVerify(
                    draw.getSeedHex(),
                    draw.getRollHash(),
                    draw.getResultHash(),
                    formula,
                    "printf '%s' 'ticket/1|" + draw.getSeedHex() + "|" + applicationId
                            + "' | sha256sum   # expect " + selection.getTicketHex(),
                    "/api/public/draws/" + draw.getDrawId() + "/verification-bundle",
                    "python3 verify/verify.py <unpacked-bundle-directory>");

            // Sanity: the formula quoted to the applicant must actually reproduce the
            // ticket that was used. If it does not, something is wrong and it is better to
            // fail than to hand somebody an instruction that will not verify.
            String recomputed = Hashing.sha256Hex("ticket/1|" + draw.getSeedHex() + "|" + applicationId);
            if (!recomputed.equals(selection.getTicketHex())) {
                throw new IllegalStateException("stored ticket for " + applicationId + " in draw "
                        + draw.getDrawId() + " does not match the seed and application id on record");
            }
        }

        List<AllotmentLine> allotmentLines = new ArrayList<>();
        for (UnitAllotmentEntity a : allotments.forApplication(applicationId)) {
            allotmentLines.add(new AllotmentLine(a.getAllotmentId(), a.getUnitId(), a.getUnitType(),
                    a.getBlock(), a.getPreferenceRankHonoured(), a.getStatus().name(), a.getBasis()));
        }

        return new Explanation(
                application.getApplicationId(),
                application.getSchemeCode(),
                application.getStatus().name(),
                application.getStatusReason(),
                application.getClusterId(),
                application.getSupersededByApplicationId(),
                checks,
                drawLines,
                allotmentLines,
                verify);
    }

    /**
     * The other half of the story: the matches that were considered against this
     * application, with their scores. Kept off {@link #explain} because it names other
     * applications, so it is served to the applicant's own household and to reviewers
     * rather than to anyone holding an application id.
     */
    @Transactional(readOnly = true)
    public List<String> duplicateHistory(String applicationId) {
        List<String> out = new ArrayList<>();
        deduplication.reviewQueue(intake.get(applicationId).getSchemeCode()).forEach(link -> {
            if (link.getLeftApplicationId().equals(applicationId)
                    || link.getRightApplicationId().equals(applicationId)) {
                out.add(link.getLinkId() + " score=" + link.getScore()
                        + " decision=" + link.getReviewDecision() + " - " + link.getRationale());
            }
        });
        return out;
    }
}
