package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.draw.AllocationEngine;
import dev.harshith.housing.core.draw.ResultHasher;
import dev.harshith.housing.core.draw.SeedDeriver;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawRollEntity;
import dev.harshith.housing.persistence.DrawSelectionEntity;
import dev.harshith.housing.persistence.DrawStatus;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.DrawRepository;
import dev.harshith.housing.persistence.repo.DrawSelectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The draw lifecycle: commit, reveal and execute, publish.
 *
 * <h2>Why the result is recomputed on every read</h2>
 * {@link #outcome(String)} does not load the draw result from the database. It reloads the
 * frozen roll and the published rule set, re-runs the allocation engine with the stored
 * seed, and then checks that what comes out hashes to the {@code resultHash} recorded at
 * execution. Only then does it return.
 *
 * <p>That is a deliberate inversion of the usual arrangement. The stored selection rows
 * exist for fast individual lookup; the <em>authority</em> is the recomputation. So a
 * database edit — a row changed from NOT_SELECTED to SELECTED, a ticket rewritten — does
 * not produce a wrong answer, it produces a loud failure naming the draw. The system is
 * built so that the cheapest thing to do is the honest thing, and tampering has nowhere to
 * hide that is not checked on the next read.
 *
 * <p>It is also cheap: 3,400 SHA-256 hashes and a handful of sorts, single-digit
 * milliseconds.
 */
@Service
public class DrawService {

    private final DrawRepository draws;
    private final DrawSelectionRepository selections;
    private final RollService rolls;
    private final RuleSetService ruleSets;
    private final SchemeService schemes;
    private final AuditService audit;
    private final Clock clock;

    public DrawService(DrawRepository draws,
                       DrawSelectionRepository selections,
                       RollService rolls,
                       RuleSetService ruleSets,
                       SchemeService schemes,
                       AuditService audit,
                       Clock clock) {
        this.draws = draws;
        this.selections = selections;
        this.rolls = rolls;
        this.ruleSets = ruleSets;
        this.schemes = schemes;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Publishes a seed commitment against a frozen roll.
     *
     * <p>{@code entropySourceDescription} must name, in advance and in words, the public
     * value that will be used — "the six-digit winning number of the State lottery draw of
     * 25 May 2026". Naming it afterwards would let the authority pick whichever public
     * number produced the outcome it wanted, which defeats the entire protocol. The
     * description is stored now and shown in the published bundle.
     */
    @Transactional
    public DrawEntity commitSeed(Actor actor,
                                 String schemeCode,
                                 String drawId,
                                 String entropySourceDescription) {
        actor.require(Role.SCHEME_ADMIN);
        schemes.requirePhase(schemeCode, SchemePhase.ROLL_FROZEN);
        if (entropySourceDescription == null || entropySourceDescription.isBlank()) {
            throw new IllegalArgumentException("the public entropy source must be named before committing, "
                    + "not chosen after the fact");
        }
        if (draws.existsById(drawId)) {
            throw new PhaseViolationException("draw " + drawId + " already exists");
        }

        DrawRollEntity roll = rolls.entity(schemes.get(schemeCode).getActiveRollId());
        String nonce = SeedDeriver.generateNonce();
        Draw.SeedCommitment commitment = SeedDeriver.commit(
                drawId, roll.getRollHash(), nonce, clock.instant(), actor.id());

        DrawEntity entity = new DrawEntity();
        entity.setDrawId(drawId);
        entity.setSchemeCode(schemeCode);
        entity.setRollId(roll.getRollId());
        entity.setRollHash(roll.getRollHash());
        entity.setRuleSetVersion(roll.getRuleSetVersion());
        entity.setStatus(DrawStatus.COMMITTED);
        entity.setCommitmentHex(commitment.commitmentHex());
        entity.setCommittedAt(commitment.committedAt());
        entity.setCommittedBy(actor.id());
        entity.setEntropySourceDescription(entropySourceDescription);
        entity.setNonce(nonce);
        draws.save(entity);

        schemes.advance(actor, schemeCode, SchemePhase.SEED_COMMITTED);

        // The nonce is deliberately absent from the audit payload: the audit log is
        // readable by auditors before the draw, and the nonce must stay secret until the
        // reveal or the commitment protocol is worthless.
        audit.append(actor, "SEED_COMMITTED", "DRAW", drawId,
                "commitment=" + commitment.commitmentHex()
                        + " rollHash=" + roll.getRollHash()
                        + " entropySource=" + entropySourceDescription);
        return entity;
    }

    @Transactional
    public Draw.DrawOutcome execute(Actor actor, String drawId, String publicEntropy) {
        actor.require(Role.SCHEME_ADMIN);
        DrawEntity entity = entity(drawId);
        if (entity.getStatus() != DrawStatus.COMMITTED) {
            throw new PhaseViolationException("draw " + drawId + " is " + entity.getStatus()
                    + " and can only be executed once, from COMMITTED");
        }
        schemes.requirePhase(entity.getSchemeCode(), SchemePhase.SEED_COMMITTED);

        // The officer who committed the seed may not be the one who executes the draw.
        actor.requireDifferentFrom(entity.getCommittedBy(), "executing the draw");

        Roll.DrawRoll roll = rolls.load(entity.getRollId());
        Rules.RuleSet rules = ruleSets.load(entity.getRuleSetVersion());

        Draw.SeedCommitment commitment = new Draw.SeedCommitment(drawId, entity.getRollHash(),
                entity.getCommitmentHex(), entity.getCommittedAt(), entity.getCommittedBy());
        Draw.DrawSeed seed = SeedDeriver.reveal(commitment, publicEntropy, entity.getNonce());

        Draw.DrawOutcome outcome = AllocationEngine.execute(drawId, roll, rules, seed, clock.instant());

        entity.setPublicEntropy(publicEntropy);
        entity.setSeedHex(seed.seedHex());
        entity.setExecutedAt(outcome.executedAt());
        entity.setExecutedBy(actor.id());
        entity.setResultHash(outcome.resultHash());
        entity.setSeatPlan(renderSeatPlan(outcome.seatPlan()));
        entity.setPoolSummary(renderPools(outcome));
        entity.setStatus(DrawStatus.EXECUTED);
        draws.save(entity);

        for (Draw.Selection s : outcome.selections()) {
            DrawSelectionEntity row = new DrawSelectionEntity();
            row.setSelectionId(DrawSelectionEntity.idFor(drawId, s.applicationId()));
            row.setDrawId(drawId);
            row.setApplicationId(s.applicationId());
            row.setOutcome(s.outcome());
            row.setPoolCode(s.decidingPoolCode());
            row.setTicketHex(s.ticketHex());
            row.setResidencyTier(s.residencyTier());
            row.setRankInPool(s.rankInDecidingPool());
            row.setWaitlistPosition(s.waitlistPosition());
            row.setReasonCode(s.reasonCode());
            row.setReasonText(s.reasonText());
            selections.save(row);
        }

        schemes.advance(actor, entity.getSchemeCode(), SchemePhase.DRAW_EXECUTED);

        audit.append(actor, "DRAW_EXECUTED", "DRAW", drawId,
                "publicEntropy=" + publicEntropy
                        + " nonceRevealed=" + entity.getNonce()
                        + " seedHex=" + seed.seedHex()
                        + " rollHash=" + roll.rollHash()
                        + " resultHash=" + outcome.resultHash()
                        + " selected=" + outcome.selectedCount()
                        + " candidates=" + roll.entryCount());
        return outcome;
    }

    @Transactional
    public DrawEntity publish(Actor actor, String drawId) {
        actor.require(Role.SCHEME_ADMIN);
        DrawEntity entity = entity(drawId);
        if (entity.getStatus() != DrawStatus.EXECUTED) {
            throw new PhaseViolationException("only an executed draw can be published; " + drawId
                    + " is " + entity.getStatus());
        }
        // Recompute before announcing. If the stored result and the recomputation disagree,
        // this throws and nothing is published.
        Draw.DrawOutcome recomputed = outcome(drawId);

        entity.setStatus(DrawStatus.PUBLISHED);
        entity.setPublishedAt(clock.instant());
        entity.setPublishedBy(actor.id());
        draws.save(entity);

        schemes.advance(actor, entity.getSchemeCode(), SchemePhase.RESULT_PUBLISHED);
        audit.append(actor, "RESULT_PUBLISHED", "DRAW", drawId,
                "resultHash=" + recomputed.resultHash()
                        + " selected=" + recomputed.selectedCount()
                        + " verifiedByRecomputation=true");
        return entity;
    }

    /**
     * Reloads the inputs, re-runs the draw and checks the result against the hash recorded
     * at execution. Throws rather than returning a result it cannot vouch for.
     */
    @Transactional(readOnly = true)
    public Draw.DrawOutcome outcome(String drawId) {
        DrawEntity entity = entity(drawId);
        if (entity.getSeedHex() == null) {
            throw new PhaseViolationException("draw " + drawId + " has not been executed yet");
        }
        Roll.DrawRoll roll = rolls.load(entity.getRollId());
        Rules.RuleSet rules = ruleSets.load(entity.getRuleSetVersion());

        Draw.SeedCommitment commitment = new Draw.SeedCommitment(drawId, entity.getRollHash(),
                entity.getCommitmentHex(), entity.getCommittedAt(), entity.getCommittedBy());
        Draw.DrawSeed seed = SeedDeriver.reveal(commitment, entity.getPublicEntropy(), entity.getNonce());

        Draw.DrawOutcome outcome = AllocationEngine.execute(
                drawId, roll, rules, seed, entity.getExecutedAt());

        if (!outcome.resultHash().equals(entity.getResultHash())) {
            throw new IllegalStateException("recomputing draw " + drawId + " from the frozen roll, the "
                    + "published rule set and the recorded seed produces result hash "
                    + outcome.resultHash() + ", but " + entity.getResultHash() + " was recorded when the "
                    + "draw was executed. One of the stored inputs has been altered.");
        }
        if (!ResultHasher.verify(outcome)) {
            throw new IllegalStateException("draw " + drawId + " failed its own self-check");
        }
        return outcome;
    }

    /** The stored selection for one applicant — the fast path for "what happened to me?". */
    @Transactional(readOnly = true)
    public DrawSelectionEntity selection(String drawId, String applicationId) {
        return findSelection(drawId, applicationId)
                .orElseThrow(() -> new NotFoundException(
                        "application " + applicationId + " was not on the roll for draw " + drawId));
    }

    /**
     * The same lookup as a question rather than a demand, for callers to whom "not on this
     * roll" is an ordinary answer — an applicant found ineligible, or superseded as a
     * duplicate, has no line in the draw and that is not an error.
     *
     * <p>It exists because the throwing form cannot be used inside another transaction and
     * then caught: the {@link NotFoundException} marks the surrounding transaction
     * rollback-only, so the request dies at commit with {@code UnexpectedRollbackException}
     * even though the caller handled it. Asking is the only safe shape across a
     * transaction boundary.
     */
    @Transactional(readOnly = true)
    public Optional<DrawSelectionEntity> findSelection(String drawId, String applicationId) {
        return selections.findByDrawIdAndApplicationId(drawId, applicationId);
    }

    @Transactional(readOnly = true)
    public List<DrawSelectionEntity> selected(String drawId) {
        return selections.findByDrawIdAndOutcomeOrderByTicketHexAsc(drawId, Draw.Outcome.SELECTED);
    }

    @Transactional(readOnly = true)
    public List<DrawSelectionEntity> waitlist(String drawId, String poolCode) {
        return selections.findByDrawIdAndPoolCodeAndOutcomeOrderByWaitlistPositionAsc(
                drawId, poolCode, Draw.Outcome.WAITLISTED);
    }

    @Transactional(readOnly = true)
    public DrawEntity entity(String drawId) {
        return draws.findById(drawId)
                .orElseThrow(() -> new NotFoundException("no such draw: " + drawId));
    }

    @Transactional(readOnly = true)
    public List<DrawEntity> history(String schemeCode) {
        return draws.findBySchemeCodeOrderByCommittedAtDesc(schemeCode);
    }

    /**
     * Sets a draw aside without deleting it. Used when the authority itself finds a defect,
     * or when a court orders a re-draw. The record and every selection row survive, because
     * the reason the first draw was annulled is usually the substance of the next dispute.
     */
    @Transactional
    public DrawEntity annul(Actor actor, String drawId, String reason) {
        actor.require(Role.SCHEME_ADMIN);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("annulling a draw requires a stated reason");
        }
        DrawEntity entity = entity(drawId);
        entity.setStatus(DrawStatus.ANNULLED);
        entity.setAnnulmentReason(reason);
        draws.save(entity);
        audit.append(actor, "DRAW_ANNULLED", "DRAW", drawId, "reason=" + reason);
        return entity;
    }

    private String renderSeatPlan(Draw.SeatPlan plan) {
        StringBuilder sb = new StringBuilder("totalUnits=" + plan.totalUnits() + "\n");
        for (Draw.VerticalSeats v : plan.verticals()) {
            sb.append("vertical=").append(v.code()).append('|').append(v.seats());
            for (Draw.HorizontalMinimum m : v.minima()) {
                sb.append('|').append(m.code()).append(':').append(m.minimumSeats());
            }
            sb.append('\n');
        }
        plan.workings().forEach(w -> sb.append("# ").append(w).append('\n'));
        return sb.toString();
    }

    private String renderPools(Draw.DrawOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        for (Draw.PoolResult p : outcome.pools()) {
            sb.append("pool=").append(p.poolCode())
              .append('|').append(p.verticalCode())
              .append('|').append(p.seats())
              .append('|').append(p.candidateCount())
              .append('|').append(p.unfilledSeats()).append('\n');
            for (Draw.TopUpNote n : p.topUps()) {
                sb.append("  topup=").append(n.horizontalCode())
                  .append(" required=").append(n.requiredSeats())
                  .append(" had=").append(n.satisfiedBeforeTopUp())
                  .append(" promoted=").append(String.join(",", n.promotedApplicationIds()))
                  .append(" displaced=").append(String.join(",", n.displacedApplicationIds()))
                  .append(" note=").append(n.note()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Convenience for the allotment service: the winners in ticket order. */
    List<String> winnersInTicketOrder(String drawId) {
        List<String> out = new ArrayList<>();
        for (DrawSelectionEntity s : selected(drawId)) {
            out.add(s.getApplicationId());
        }
        return out;
    }
}
