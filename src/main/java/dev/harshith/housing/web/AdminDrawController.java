package dev.harshith.housing.web;

import dev.harshith.housing.core.draw.WaitlistPromoter;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.units.Units;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawSelectionEntity;
import dev.harshith.housing.persistence.UnitAllotmentEntity;
import dev.harshith.housing.service.AllotmentService;
import dev.harshith.housing.service.DrawService;
import dev.harshith.housing.web.dto.Requests;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The draw itself, and the allotment that follows it. */
@RestController
@RequestMapping("/api/admin")
public class AdminDrawController {

    private final DrawService draws;
    private final AllotmentService allotments;

    public AdminDrawController(DrawService draws, AllotmentService allotments) {
        this.draws = draws;
        this.allotments = allotments;
    }

    /**
     * Publishes the seed commitment. The response deliberately does not contain the nonce:
     * the whole protocol rests on the nonce staying secret until the draw, so it is not
     * returned here, not logged, and not readable through any endpoint until execution.
     */
    @PostMapping("/schemes/{schemeCode}/draws")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> commit(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                      @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                      @PathVariable String schemeCode,
                                      @Valid @RequestBody Requests.CommitSeed request) {
        DrawEntity draw = draws.commitSeed(CallerActor.of(actorId, actorRole), schemeCode,
                request.drawId(), request.entropySourceDescription());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawId", draw.getDrawId());
        body.put("rollId", draw.getRollId());
        body.put("rollHash", draw.getRollHash());
        body.put("commitmentHex", draw.getCommitmentHex());
        body.put("committedAt", draw.getCommittedAt());
        body.put("committedBy", draw.getCommittedBy());
        body.put("entropySourceDescription", draw.getEntropySourceDescription());
        body.put("publishThisNow", "Publish the roll hash and the commitment before the entropy value "
                + "exists. The nonce behind the commitment is withheld until the draw is executed.");
        return body;
    }

    /**
     * Reveals the nonce, derives the seed and runs the draw. Executable once, and not by
     * the officer who committed the seed.
     */
    @PostMapping("/draws/{drawId}/execute")
    public Map<String, Object> execute(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                       @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                       @PathVariable String drawId,
                                       @Valid @RequestBody Requests.ExecuteDraw request) {
        Draw.DrawOutcome outcome = draws.execute(CallerActor.of(actorId, actorRole),
                drawId, request.publicEntropy());
        DrawEntity draw = draws.entity(drawId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawId", drawId);
        body.put("rollHash", outcome.rollHash());
        body.put("publicEntropy", draw.getPublicEntropy());
        body.put("revealedNonce", draw.getNonce());
        body.put("seedHex", outcome.seedHex());
        body.put("resultHash", outcome.resultHash());
        body.put("candidates", outcome.selections().size());
        body.put("selected", outcome.selectedCount());
        body.put("pools", poolView(outcome));
        body.put("seedDerivation", "seedHex = SHA256(\"seed/1|" + outcome.rollHash() + "|"
                + draw.getPublicEntropy() + "|" + draw.getNonce() + "\")");
        return body;
    }

    @PostMapping("/draws/{drawId}/publish")
    public DrawEntity publish(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                              @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                              @PathVariable String drawId) {
        return draws.publish(CallerActor.of(actorId, actorRole), drawId);
    }

    @PostMapping("/draws/{drawId}/annul")
    public DrawEntity annul(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                            @PathVariable String drawId,
                            @Valid @RequestBody Requests.Reason request) {
        return draws.annul(CallerActor.of(actorId, actorRole), drawId, request.reason());
    }

    @GetMapping("/draws/{drawId}")
    public DrawEntity draw(@PathVariable String drawId) {
        return draws.entity(drawId);
    }

    @GetMapping("/schemes/{schemeCode}/draws")
    public List<DrawEntity> history(@PathVariable String schemeCode) {
        return draws.history(schemeCode);
    }

    @GetMapping("/draws/{drawId}/waitlist")
    public List<DrawSelectionEntity> waitlist(@PathVariable String drawId,
                                              @RequestParam String pool) {
        return draws.waitlist(drawId, pool);
    }

    // ---------------------------------------------------------------- allotment

    @PostMapping("/draws/{drawId}/allotments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignUnits(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                           @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                           @PathVariable String drawId) {
        Units.AllotmentPlan plan = allotments.assignUnits(CallerActor.of(actorId, actorRole), drawId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawId", drawId);
        body.put("assigned", plan.allotments().size());
        body.put("applicantsWithoutUnit", plan.applicantsWithoutUnit());
        body.put("unassignedUnits", plan.unassignedUnitIds());
        body.put("allotmentHash", plan.allotmentHash());
        body.put("workings", plan.workings());
        return body;
    }

    @GetMapping("/draws/{drawId}/allotments")
    public List<UnitAllotmentEntity> allotmentsFor(@PathVariable String drawId) {
        return allotments.forDraw(drawId);
    }

    @PostMapping("/allotments/{allotmentId}/accept")
    public UnitAllotmentEntity accept(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                      @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                      @PathVariable String allotmentId) {
        return allotments.accept(CallerActor.of(actorId, actorRole), allotmentId);
    }

    @PostMapping("/allotments/{allotmentId}/forfeit")
    public UnitAllotmentEntity forfeit(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                       @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                       @PathVariable String allotmentId,
                                       @Valid @RequestBody Requests.Reason request) {
        return allotments.forfeit(CallerActor.of(actorId, actorRole), allotmentId, request.reason());
    }

    /** Fills every vacant seat from the published waitlist. No discretion, idempotent. */
    @PostMapping("/draws/{drawId}/waitlist-promotions")
    public List<WaitlistPromoter.Promotion> promote(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String drawId) {
        return allotments.promoteWaitlist(CallerActor.of(actorId, actorRole), drawId);
    }

    private List<Map<String, Object>> poolView(Draw.DrawOutcome outcome) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Draw.PoolResult p : outcome.pools()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("poolCode", p.poolCode());
            row.put("category", p.verticalCode());
            row.put("seats", p.seats());
            row.put("candidates", p.candidateCount());
            row.put("selected", p.selectedApplicationIds().size());
            row.put("unfilledSeats", p.unfilledSeats());
            List<Map<String, Object>> topUps = new java.util.ArrayList<>();
            for (Draw.TopUpNote n : p.topUps()) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("horizontalCode", n.horizontalCode());
                note.put("requiredSeats", n.requiredSeats());
                note.put("satisfiedBeforeTopUp", n.satisfiedBeforeTopUp());
                note.put("promoted", n.promotedApplicationIds());
                note.put("displaced", n.displacedApplicationIds());
                note.put("note", n.note());
                topUps.add(note);
            }
            row.put("horizontalTopUps", topUps);
            out.add(row);
        }
        return out;
    }
}
