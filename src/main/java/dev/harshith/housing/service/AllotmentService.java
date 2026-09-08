package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.draw.WaitlistPromoter;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.units.UnitAssigner;
import dev.harshith.housing.core.units.Units;
import dev.harshith.housing.persistence.AllotmentStatus;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.FlatUnitEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.UnitAllotmentEntity;
import dev.harshith.housing.persistence.repo.FlatUnitRepository;
import dev.harshith.housing.persistence.repo.UnitAllotmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns flats to winners, and refills seats that come free afterwards.
 *
 * <p>The post-draw period is where allotment schemes are actually corrupted. The lottery
 * itself is public, watched and now verifiable; the reallocation of thirty flats over the
 * following six months is none of those things. Everything here is therefore either a pure
 * function of the published draw or an explicitly recorded human decision with a reason,
 * and there is deliberately no operation that grants a flat to a named person.
 */
@Service
public class AllotmentService {

    private final UnitAllotmentRepository allotments;
    private final FlatUnitRepository units;
    private final DrawService draws;
    private final RollService rolls;
    private final SchemeService schemes;
    private final AuditService audit;
    private final Clock clock;

    public AllotmentService(UnitAllotmentRepository allotments,
                            FlatUnitRepository units,
                            DrawService draws,
                            RollService rolls,
                            SchemeService schemes,
                            AuditService audit,
                            Clock clock) {
        this.allotments = allotments;
        this.units = units;
        this.draws = draws;
        this.rolls = rolls;
        this.schemes = schemes;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Units.AllotmentPlan assignUnits(Actor actor, String drawId) {
        actor.require(Role.SCHEME_ADMIN);
        DrawEntity draw = draws.entity(drawId);
        schemes.requirePhase(draw.getSchemeCode(), SchemePhase.RESULT_PUBLISHED);
        // A read-then-write check, which is not a lock. It exists to give an operator a
        // clear message; the actual guarantee is the unique index on
        // UnitAllotmentEntity.liveUnitKey, which turns a concurrent second call into a
        // constraint violation (rendered as 409) rather than two offers on one flat.
        if (allotments.countByDrawId(drawId) > 0) {
            throw new PhaseViolationException("units have already been assigned for draw " + drawId);
        }

        Draw.DrawOutcome outcome = draws.outcome(drawId);
        Roll.DrawRoll roll = rolls.load(draw.getRollId());

        List<Units.UnitInventoryItem> inventory = new ArrayList<>();
        for (FlatUnitEntity u : units.findBySchemeCodeAndWithdrawnFalseOrderByUnitIdAsc(draw.getSchemeCode())) {
            inventory.add(new Units.UnitInventoryItem(
                    u.getUnitId(), u.getBlock(), u.getUnitType(), u.getFloorNumber()));
        }

        Units.AllotmentPlan plan = UnitAssigner.assign(outcome, roll, inventory);

        for (Units.UnitAllotment a : plan.allotments()) {
            UnitAllotmentEntity row = new UnitAllotmentEntity();
            row.setAllotmentId(drawId + ":" + a.applicationId());
            row.setDrawId(drawId);
            row.setApplicationId(a.applicationId());
            row.setUnitId(a.unitId());
            row.setUnitType(a.unitType());
            row.setBlock(a.block());
            row.setPickOrder(a.pickOrder());
            row.setPreferenceRankHonoured(a.preferenceRankHonoured());
            row.setBasis(a.basis());
            row.setStatus(AllotmentStatus.OFFERED);
            row.setOfferedAt(clock.instant());
            row.setLiveUnitKey(UnitAllotmentEntity.liveKeyFor(drawId, a.unitId()));
            allotments.save(row);
        }

        schemes.advance(actor, draw.getSchemeCode(), SchemePhase.ALLOTMENT);
        audit.append(actor, "UNITS_ASSIGNED", "DRAW", drawId,
                "allotments=" + plan.allotments().size()
                        + " unhoused=" + plan.applicantsWithoutUnit().size()
                        + " unassignedUnits=" + plan.unassignedUnitIds().size()
                        + " allotmentHash=" + plan.allotmentHash()
                        + " rule=serial-dictatorship-in-ticket-order");
        return plan;
    }

    @Transactional
    public UnitAllotmentEntity accept(Actor actor, String allotmentId) {
        actor.require(Role.SCHEME_ADMIN, Role.VERIFIER);
        UnitAllotmentEntity row = require(allotmentId);
        requireLive(row);
        row.setStatus(AllotmentStatus.ACCEPTED);
        row.setDecidedAt(clock.instant());
        row.setDecidedBy(actor.id());
        row.setDecisionReason("Offer accepted");
        allotments.save(row);
        audit.append(actor, "ALLOTMENT_ACCEPTED", "ALLOTMENT", allotmentId,
                "application=" + row.getApplicationId() + " unit=" + row.getUnitId());
        return row;
    }

    /**
     * Declares a seat vacant. This is the only discretionary act in the post-draw process,
     * so it demands a reason, records the officer, and never chooses who gets the flat
     * next — that is {@link #promoteWaitlist}, which has no discretion at all.
     */
    @Transactional
    public UnitAllotmentEntity forfeit(Actor actor, String allotmentId, String reason) {
        actor.require(Role.SCHEME_ADMIN, Role.VERIFIER);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("declaring a seat vacant requires a stated reason");
        }
        UnitAllotmentEntity row = require(allotmentId);
        requireLive(row);
        row.setStatus(AllotmentStatus.FORFEITED);
        row.setDecidedAt(clock.instant());
        row.setDecidedBy(actor.id());
        row.setDecisionReason(reason);
        // Releases the unique live-offer claim on the flat so a promotion can take it,
        // while the forfeited row itself stays on record.
        row.setLiveUnitKey(null);
        allotments.saveAndFlush(row);
        audit.append(actor, "ALLOTMENT_FORFEITED", "ALLOTMENT", allotmentId,
                "application=" + row.getApplicationId() + " unit=" + row.getUnitId() + " reason=" + reason);
        return row;
    }

    /**
     * Fills every vacant seat from the published waitlist of the pool that awarded it.
     *
     * <p>Idempotent: a vacancy already filled by an earlier promotion is skipped, so the
     * endpoint can be called after every batch of forfeitures without producing duplicate
     * offers.
     */
    @Transactional
    public List<WaitlistPromoter.Promotion> promoteWaitlist(Actor actor, String drawId) {
        actor.require(Role.SCHEME_ADMIN);
        DrawEntity draw = draws.entity(drawId);
        schemes.requirePhase(draw.getSchemeCode(), SchemePhase.ALLOTMENT);
        Draw.DrawOutcome outcome = draws.outcome(drawId);

        List<UnitAllotmentEntity> all = allotments.findByDrawIdOrderByPickOrderAsc(drawId);
        Set<String> alreadyFilled = new LinkedHashSet<>();
        Set<String> holders = new LinkedHashSet<>();
        Set<String> declined = new LinkedHashSet<>();
        Map<String, UnitAllotmentEntity> vacancies = new LinkedHashMap<>();

        for (UnitAllotmentEntity row : all) {
            if (row.getFillsVacancyOf() != null) {
                alreadyFilled.add(row.getFillsVacancyOf());
            }
            switch (row.getStatus()) {
                case OFFERED, ACCEPTED, OFFERED_ON_PROMOTION -> holders.add(row.getApplicationId());
                case FORFEITED -> {
                    vacancies.put(row.getAllotmentId(), row);
                    declined.add(row.getApplicationId());
                }
            }
        }

        List<UnitAllotmentEntity> open = new ArrayList<>();
        List<String> forfeitedApplicants = new ArrayList<>();
        for (Map.Entry<String, UnitAllotmentEntity> e : vacancies.entrySet()) {
            if (alreadyFilled.contains(e.getKey())) {
                continue;
            }
            open.add(e.getValue());
            forfeitedApplicants.add(e.getValue().getApplicationId());
        }
        if (open.isEmpty()) {
            return List.of();
        }

        List<WaitlistPromoter.Promotion> promotions =
                WaitlistPromoter.promote(outcome, forfeitedApplicants, holders, declined);

        Map<String, UnitAllotmentEntity> vacancyByApplicant = new LinkedHashMap<>();
        open.forEach(row -> vacancyByApplicant.put(row.getApplicationId(), row));

        for (WaitlistPromoter.Promotion promotion : promotions) {
            UnitAllotmentEntity vacancy = vacancyByApplicant.get(promotion.vacatedByApplicationId());
            if (vacancy == null) {
                continue;
            }
            if (promotion.promotedApplicationId() == null) {
                audit.append(actor, "PROMOTION_EXHAUSTED", "ALLOTMENT", vacancy.getAllotmentId(),
                        "pool=" + promotion.poolCode() + " basis=" + promotion.basis());
                continue;
            }
            UnitAllotmentEntity row = new UnitAllotmentEntity();
            // Suffixed with the vacancy it fills: an applicant who forfeited an earlier
            // offer already owns the plain id, and overwriting it would erase the record
            // of that forfeiture.
            row.setAllotmentId(drawId + ":" + promotion.promotedApplicationId()
                    + ":P" + vacancy.getPickOrder());
            row.setDrawId(drawId);
            row.setApplicationId(promotion.promotedApplicationId());
            row.setUnitId(vacancy.getUnitId());
            row.setUnitType(vacancy.getUnitType());
            row.setBlock(vacancy.getBlock());
            row.setPickOrder(vacancy.getPickOrder());
            row.setPreferenceRankHonoured(0);
            row.setBasis(promotion.basis());
            row.setStatus(AllotmentStatus.OFFERED_ON_PROMOTION);
            row.setOfferedAt(clock.instant());
            row.setFillsVacancyOf(vacancy.getAllotmentId());
            row.setLiveUnitKey(UnitAllotmentEntity.liveKeyFor(drawId, vacancy.getUnitId()));
            allotments.save(row);

            audit.append(actor, "WAITLIST_PROMOTED", "ALLOTMENT", row.getAllotmentId(),
                    "promoted=" + promotion.promotedApplicationId()
                            + " pool=" + promotion.poolCode()
                            + " waitlistPosition=" + promotion.waitlistPosition()
                            + " unit=" + vacancy.getUnitId()
                            + " vacatedBy=" + promotion.vacatedByApplicationId());
        }
        return promotions;
    }

    @Transactional(readOnly = true)
    public List<UnitAllotmentEntity> forDraw(String drawId) {
        return allotments.findByDrawIdOrderByPickOrderAsc(drawId);
    }

    @Transactional(readOnly = true)
    public List<UnitAllotmentEntity> forApplication(String applicationId) {
        return allotments.findByApplicationIdOrderByOfferedAtAsc(applicationId);
    }

    private UnitAllotmentEntity require(String allotmentId) {
        return allotments.findById(allotmentId)
                .orElseThrow(() -> new NotFoundException("no such allotment: " + allotmentId));
    }

    private void requireLive(UnitAllotmentEntity row) {
        if (row.getStatus() == AllotmentStatus.FORFEITED || row.getStatus() == AllotmentStatus.ACCEPTED) {
            throw new PhaseViolationException("allotment " + row.getAllotmentId() + " is already "
                    + row.getStatus() + " and cannot be decided again");
        }
    }
}
