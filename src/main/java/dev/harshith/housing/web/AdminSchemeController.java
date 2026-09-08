package dev.harshith.housing.web;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.draw.QuotaCalculator;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.DrawRollEntity;
import dev.harshith.housing.persistence.DuplicateLinkEntity;
import dev.harshith.housing.persistence.FlatUnitEntity;
import dev.harshith.housing.persistence.RuleSetEntity;
import dev.harshith.housing.persistence.SchemeEntity;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.persistence.repo.FlatUnitRepository;
import dev.harshith.housing.service.AuditService;
import dev.harshith.housing.service.DeduplicationService;
import dev.harshith.housing.service.RollService;
import dev.harshith.housing.service.RuleSetService;
import dev.harshith.housing.service.SchemeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.harshith.housing.web.dto.Requests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scheme lifecycle: rules, inventory, deduplication, roll freeze. */
@RestController
@RequestMapping("/api/admin")
public class AdminSchemeController {

    private final SchemeService schemes;
    private final RuleSetService ruleSets;
    private final DeduplicationService deduplication;
    private final RollService rolls;
    private final FlatUnitRepository units;
    private final ApplicationRepository applications;
    private final AuditService audit;

    public AdminSchemeController(SchemeService schemes,
                                 RuleSetService ruleSets,
                                 DeduplicationService deduplication,
                                 RollService rolls,
                                 FlatUnitRepository units,
                                 ApplicationRepository applications,
                                 AuditService audit) {
        this.schemes = schemes;
        this.ruleSets = ruleSets;
        this.deduplication = deduplication;
        this.rolls = rolls;
        this.units = units;
        this.applications = applications;
        this.audit = audit;
    }

    @PostMapping("/schemes")
    @ResponseStatus(HttpStatus.CREATED)
    public SchemeEntity createScheme(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                     @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                     @Valid @RequestBody Requests.CreateScheme request) {
        return schemes.create(CallerActor.of(actorId, actorRole), request.schemeCode(), request.name());
    }

    @GetMapping("/schemes/{schemeCode}")
    public SchemeEntity scheme(@PathVariable String schemeCode) {
        return schemes.get(schemeCode);
    }

    /**
     * Everything an operator needs before deciding whether the roll can be frozen: the
     * phase, the application counts by status, how many duplicate reviews are still open,
     * and whether the published unit count matches the units that actually exist.
     */
    @GetMapping("/schemes/{schemeCode}/status")
    public Map<String, Object> status(@PathVariable String schemeCode) {
        SchemeEntity scheme = schemes.get(schemeCode);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status.name(), applications.countBySchemeCodeAndStatus(schemeCode, status));
        }

        long available = units.countBySchemeCodeAndWithdrawnFalse(schemeCode);
        Integer publishedUnits = scheme.getActiveRuleSetVersion() == null
                ? null
                : ruleSets.load(scheme.getActiveRuleSetVersion()).totalUnits();
        long pendingReviews = deduplication.pendingReviewCount(schemeCode);

        List<String> blockers = new ArrayList<>();
        if (pendingReviews > 0) {
            blockers.add(pendingReviews + " duplicate match(es) awaiting review");
        }
        long undecided = counts.get(ApplicationStatus.RECEIVED.name())
                + counts.get(ApplicationStatus.PENDING_DUPLICATE_REVIEW.name());
        if (undecided > 0) {
            blockers.add(undecided + " application(s) with no recorded eligibility decision");
        }
        if (publishedUnits != null && publishedUnits != available) {
            blockers.add("the rule set apportions " + publishedUnits + " units but " + available
                    + " are available; publish an amended rule set version");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemeCode", schemeCode);
        body.put("phase", scheme.getPhase());
        body.put("allowedNextPhases", scheme.getPhase().allowedNext());
        body.put("activeRuleSetVersion", scheme.getActiveRuleSetVersion());
        body.put("activeRollId", scheme.getActiveRollId());
        body.put("applications", applications.countBySchemeCode(schemeCode));
        body.put("applicationsByStatus", counts);
        body.put("pendingDuplicateReviews", pendingReviews);
        body.put("unitsAvailable", available);
        body.put("unitsInRuleSet", publishedUnits);
        body.put("rollFreezeBlockers", blockers);
        return body;
    }

    @PostMapping("/schemes/{schemeCode}/phase")
    public SchemeEntity advance(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                @PathVariable String schemeCode,
                                @Valid @RequestBody Requests.AdvancePhase request) {
        return schemes.advance(CallerActor.of(actorId, actorRole), schemeCode, request.to());
    }

    // ---------------------------------------------------------------- rule sets

    @PostMapping("/rule-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> publishRuleSet(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                              @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                              @Valid @RequestBody Requests.PublishRuleSet request) {
        Rules.RuleSet ruleSet = request.toRuleSet();
        RuleSetEntity entity = ruleSets.publish(CallerActor.of(actorId, actorRole), ruleSet);
        Draw.SeatPlan plan = QuotaCalculator.plan(ruleSet);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", entity.getVersion());
        body.put("ruleSetHash", entity.getRuleSetHash());
        body.put("canonicalText", entity.getEncoded());
        body.put("seatPlan", seatPlanView(plan));
        body.put("apportionmentWorkings", plan.workings());
        return body;
    }

    @GetMapping("/rule-sets/{version}")
    public Map<String, Object> ruleSet(@PathVariable String version) {
        RuleSetEntity entity = ruleSets.entity(version);
        Draw.SeatPlan plan = QuotaCalculator.plan(ruleSets.load(version));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", entity.getVersion());
        body.put("ruleSetHash", entity.getRuleSetHash());
        body.put("publishedAt", entity.getPublishedAt());
        body.put("publishedBy", entity.getPublishedBy());
        body.put("canonicalText", entity.getEncoded());
        body.put("seatPlan", seatPlanView(plan));
        body.put("apportionmentWorkings", plan.workings());
        return body;
    }

    @GetMapping("/schemes/{schemeCode}/rule-sets")
    public List<RuleSetEntity> ruleSetHistory(@PathVariable String schemeCode) {
        return ruleSets.history(schemeCode);
    }

    // ---------------------------------------------------------------- inventory

    @PostMapping("/schemes/{schemeCode}/units")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addUnits(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                        @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                        @PathVariable String schemeCode,
                                        @Valid @RequestBody Requests.AddUnits request) {
        Actor actor = CallerActor.of(actorId, actorRole);
        actor.require(Role.SCHEME_ADMIN);
        schemes.get(schemeCode);

        List<FlatUnitEntity> saved = new ArrayList<>();
        for (Requests.Unit u : request.units()) {
            FlatUnitEntity entity = new FlatUnitEntity();
            entity.setUnitId(u.unitId());
            entity.setSchemeCode(schemeCode);
            entity.setBlock(u.block());
            entity.setUnitType(u.unitType());
            entity.setFloorNumber(u.floor());
            entity.setWithdrawn(false);
            saved.add(units.save(entity));
        }
        audit.append(actor, "UNITS_REGISTERED", "SCHEME", schemeCode, "count=" + saved.size());
        return Map.of("registered", saved.size(),
                "availableUnits", units.countBySchemeCodeAndWithdrawnFalse(schemeCode));
    }

    @PostMapping("/units/{unitId}/withdraw")
    @Transactional
    public FlatUnitEntity withdrawUnit(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                       @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                       @PathVariable String unitId,
                                       @Valid @RequestBody Requests.Reason request) {
        Actor actor = CallerActor.of(actorId, actorRole);
        actor.require(Role.SCHEME_ADMIN);
        FlatUnitEntity unit = units.findById(unitId)
                .orElseThrow(() -> new dev.harshith.housing.api.NotFoundException("no such unit: " + unitId));
        unit.setWithdrawn(true);
        unit.setWithdrawnReason(request.reason());
        units.save(unit);
        audit.append(actor, "UNIT_WITHDRAWN", "UNIT", unitId, "reason=" + request.reason());
        return unit;
    }

    // ---------------------------------------------------------------- deduplication

    @PostMapping("/schemes/{schemeCode}/deduplication")
    public DeduplicationService.Summary deduplicate(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String schemeCode) {
        return deduplication.run(CallerActor.of(actorId, actorRole), schemeCode);
    }

    @GetMapping("/schemes/{schemeCode}/duplicate-review-queue")
    public List<DuplicateLinkEntity> reviewQueue(@PathVariable String schemeCode) {
        return deduplication.reviewQueue(schemeCode);
    }

    @PostMapping("/duplicate-links/{linkId}/review")
    public DuplicateLinkEntity review(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                      @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                      @PathVariable String linkId,
                                      @Valid @RequestBody Requests.ReviewDuplicate request) {
        return deduplication.decide(CallerActor.of(actorId, actorRole), linkId,
                request.decision(), request.note());
    }

    // ---------------------------------------------------------------- roll

    @PostMapping("/schemes/{schemeCode}/rolls")
    @ResponseStatus(HttpStatus.CREATED)
    public DrawRollEntity freeze(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                 @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                 @PathVariable String schemeCode,
                                 @Valid @RequestBody Requests.FreezeRoll request) {
        return rolls.freeze(CallerActor.of(actorId, actorRole), schemeCode, request.rollId());
    }

    @GetMapping("/rolls/{rollId}")
    public DrawRollEntity roll(@PathVariable String rollId) {
        return rolls.entity(rollId);
    }

    /** The canonical text whose SHA-256 is the roll hash. */
    @GetMapping(value = "/rolls/{rollId}/canonical", produces = MediaType.TEXT_PLAIN_VALUE)
    public String rollText(@PathVariable String rollId) {
        return rolls.canonicalText(rollId);
    }

    private List<Map<String, Object>> seatPlanView(Draw.SeatPlan plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Draw.VerticalSeats v : plan.verticals()) {
            Map<String, Object> minima = new LinkedHashMap<>();
            v.minima().forEach(m -> minima.put(m.code(), m.minimumSeats()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", v.code());
            row.put("seats", v.seats());
            row.put("horizontalMinima", minima);
            out.add(row);
        }
        return out;
    }
}
