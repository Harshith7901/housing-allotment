package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.draw.RollHasher;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.DrawRollEntity;
import dev.harshith.housing.persistence.RollEntryEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.persistence.repo.DrawRollRepository;
import dev.harshith.housing.persistence.repo.FlatUnitRepository;
import dev.harshith.housing.persistence.repo.RollEntryRepository;
import dev.harshith.housing.support.Csv;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes the roll — the single most consequential operation in the system.
 *
 * <p>Freezing is gated, and each gate corresponds to a way real schemes have gone wrong:
 *
 * <ul>
 *   <li><b>No pending duplicate reviews.</b> An open review is an unanswered question
 *       about whether a household holds two tickets.</li>
 *   <li><b>No applications left unverified.</b> Freezing with applications still in
 *       {@code RECEIVED} would silently exclude people whose paperwork simply had not been
 *       reached yet, which is indistinguishable from excluding them on purpose.</li>
 *   <li><b>A non-empty roll and a non-empty inventory.</b></li>
 * </ul>
 *
 * <p>The apportionment is computed against the inventory <em>as it stands at freeze
 * time</em>, not the figure typed into the rule set. Units get withdrawn between
 * notification and draw — a construction defect, an injunction over one block — and
 * apportioning 600 flats when 594 exist produces six allotment letters that cannot be
 * honoured.
 */
@Service
public class RollService {

    private final DrawRollRepository rolls;
    private final RollEntryRepository entries;
    private final ApplicationRepository applications;
    private final FlatUnitRepository units;
    private final SchemeService schemes;
    private final RuleSetService ruleSets;
    private final DeduplicationService deduplication;
    private final AuditService audit;
    private final Clock clock;

    public RollService(DrawRollRepository rolls,
                       RollEntryRepository entries,
                       ApplicationRepository applications,
                       FlatUnitRepository units,
                       SchemeService schemes,
                       RuleSetService ruleSets,
                       DeduplicationService deduplication,
                       AuditService audit,
                       Clock clock) {
        this.rolls = rolls;
        this.entries = entries;
        this.applications = applications;
        this.units = units;
        this.schemes = schemes;
        this.ruleSets = ruleSets;
        this.deduplication = deduplication;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DrawRollEntity freeze(Actor actor, String schemeCode, String rollId) {
        actor.require(Role.SCHEME_ADMIN);
        schemes.requirePhase(schemeCode, SchemePhase.VERIFICATION);

        long pending = deduplication.pendingReviewCount(schemeCode);
        if (pending > 0) {
            throw new PhaseViolationException(pending + " duplicate match(es) are still awaiting review; "
                    + "the roll cannot be frozen while it is unknown whether a household holds two tickets");
        }
        long unverified = applications.countBySchemeCodeAndStatus(schemeCode, ApplicationStatus.RECEIVED)
                + applications.countBySchemeCodeAndStatus(schemeCode,
                        ApplicationStatus.PENDING_DUPLICATE_REVIEW);
        if (unverified > 0) {
            throw new PhaseViolationException(unverified + " application(s) have no recorded eligibility "
                    + "decision; every application must be verified or explicitly rejected before the freeze");
        }
        if (rolls.existsById(rollId)) {
            throw new PhaseViolationException("roll " + rollId + " already exists and rolls are immutable");
        }

        int inventory = (int) units.countBySchemeCodeAndWithdrawnFalse(schemeCode);
        if (inventory <= 0) {
            throw new PhaseViolationException("scheme " + schemeCode + " has no available units");
        }

        Rules.RuleSet effective = ruleSets.active(schemeCode);
        if (effective.totalUnits() != inventory) {
            throw new PhaseViolationException("rule set " + effective.version() + " apportions "
                    + effective.totalUnits() + " units but " + inventory + " are currently available. "
                    + "The number of flats is part of the published rules, so publish an amended rule set "
                    + "version before freezing rather than drawing against a different inventory.");
        }

        List<ApplicationEntity> eligible = applications
                .findBySchemeCodeAndStatusOrderByApplicationIdAsc(schemeCode, ApplicationStatus.ELIGIBLE);
        if (eligible.isEmpty()) {
            throw new PhaseViolationException("no eligible applications; there is nothing to draw");
        }

        List<Roll.RollEntry> rollEntries = new ArrayList<>(eligible.size());
        for (ApplicationEntity a : eligible) {
            rollEntries.add(new Roll.RollEntry(
                    a.getApplicationId(),
                    a.getClusterId() == null ? "CL-" + a.getApplicationId() : a.getClusterId(),
                    a.getVerticalCode(),
                    Csv.toSortedSet(a.getHorizontalCodes()),
                    a.getResidencyYears(),
                    Csv.toList(a.getUnitTypePreferences())));
        }

        String ruleSetHash = ruleSets.entity(effective.version()).getRuleSetHash();
        Roll.DrawRoll roll = RollHasher.freeze(rollId, schemeCode, effective.version(),
                ruleSetHash, clock.instant(), rollEntries);

        DrawRollEntity entity = new DrawRollEntity();
        entity.setRollId(roll.rollId());
        entity.setSchemeCode(schemeCode);
        entity.setRuleSetVersion(roll.ruleSetVersion());
        entity.setRuleSetHash(roll.ruleSetHash());
        entity.setFrozenAt(roll.frozenAt());
        entity.setFrozenBy(actor.id());
        entity.setRollHash(roll.rollHash());
        entity.setEntryCount(roll.entryCount());
        entity.setInventoryCount(inventory);
        entity.setExclusionSummary(exclusionSummary(schemeCode));
        rolls.save(entity);

        for (Roll.RollEntry e : roll.entries()) {
            RollEntryEntity row = new RollEntryEntity();
            row.setEntryId(RollEntryEntity.idFor(rollId, e.applicationId()));
            row.setRollId(rollId);
            row.setApplicationId(e.applicationId());
            row.setClusterId(e.clusterId());
            row.setVerticalCode(e.verticalCode());
            row.setHorizontalCodes(Csv.join(e.horizontalCodes()));
            row.setResidencyYears(e.residencyYears());
            row.setUnitTypePreferences(Csv.join(e.unitTypePreferences()));
            entries.save(row);
        }

        schemes.recordActiveRoll(schemeCode, rollId);
        schemes.advance(actor, schemeCode, SchemePhase.ROLL_FROZEN);

        audit.append(actor, "ROLL_FROZEN", "ROLL", rollId,
                "rollHash=" + roll.rollHash()
                        + " entries=" + roll.entryCount()
                        + " inventory=" + inventory
                        + " ruleSet=" + roll.ruleSetVersion()
                        + " ruleSetHash=" + ruleSetHash
                        + " frozenAt=" + roll.frozenAt());
        return entity;
    }

    /**
     * Rebuilds the core roll object from the database and checks it still hashes to the
     * value recorded at freeze time. Every read goes through here, so a roll that was
     * edited in the database fails loudly at the point of use rather than producing a
     * quietly different draw.
     */
    @Transactional(readOnly = true)
    public Roll.DrawRoll load(String rollId) {
        DrawRollEntity entity = entity(rollId);
        List<RollEntryEntity> rows = entries.findByRollIdOrderByApplicationIdAsc(rollId);

        List<Roll.RollEntry> rollEntries = new ArrayList<>(rows.size());
        for (RollEntryEntity r : rows) {
            rollEntries.add(new Roll.RollEntry(
                    r.getApplicationId(), r.getClusterId(), r.getVerticalCode(),
                    Csv.toSortedSet(r.getHorizontalCodes()), r.getResidencyYears(),
                    Csv.toList(r.getUnitTypePreferences())));
        }

        Roll.DrawRoll roll = new Roll.DrawRoll(entity.getRollId(), entity.getSchemeCode(),
                entity.getRuleSetVersion(), entity.getRuleSetHash(), entity.getFrozenAt(),
                rollEntries, entity.getRollHash());

        if (!RollHasher.verify(roll)) {
            throw new IllegalStateException("roll " + rollId + " no longer matches the hash recorded when it "
                    + "was frozen (" + entity.getRollHash() + "). It has been altered in storage and must "
                    + "not be used for a draw.");
        }
        return roll;
    }

    @Transactional(readOnly = true)
    public DrawRollEntity entity(String rollId) {
        return rolls.findById(rollId)
                .orElseThrow(() -> new NotFoundException("no such roll: " + rollId));
    }

    @Transactional(readOnly = true)
    public String canonicalText(String rollId) {
        return RollHasher.encode(load(rollId));
    }

    @Transactional(readOnly = true)
    public List<DrawRollEntity> history(String schemeCode) {
        return rolls.findBySchemeCodeOrderByFrozenAtDesc(schemeCode);
    }

    /** Counts of what was left off the roll and why, recorded with the roll itself. */
    private String exclusionSummary(String schemeCode) {
        Map<ApplicationStatus, Long> counts = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status, applications.countBySchemeCodeAndStatus(schemeCode, status));
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((status, count) -> sb.append(status).append('=').append(count).append('\n'));
        return sb.toString();
    }
}
