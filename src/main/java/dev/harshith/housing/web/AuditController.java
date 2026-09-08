package dev.harshith.housing.web;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.audit.Audit;
import dev.harshith.housing.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read access to the audit chain. */
@RestController
@RequestMapping("/api")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /**
     * The head hash and the event count, open to everybody without credentials.
     *
     * <p>Deliberately public. A hash chain is only tamper-evident if somebody outside the
     * system has a copy of an earlier head hash to compare against, so the head has to be
     * freely quotable — printed in the notification, archived by a journalist, read out at
     * the draw. Keeping it behind an auditor login would defeat the mechanism it exists to
     * support.
     */
    @GetMapping("/public/audit/head")
    public Map<String, Object> head() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventCount", audit.eventCount());
        body.put("headHash", audit.headHash());
        body.put("note", "Quote this value somewhere outside this system - a notification, a newspaper, "
                + "a notarised print - so that a later rewrite of the log can be detected by comparison.");
        return body;
    }

    /** Replays the whole chain from the genesis hash. Auditors and administrators. */
    @GetMapping("/audit/verify")
    public Audit.ChainVerification verify(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                          @RequestHeader(CallerActor.ROLE_HEADER) String actorRole) {
        Actor actor = CallerActor.of(actorId, actorRole);
        actor.require(Role.AUDITOR, Role.SCHEME_ADMIN);
        return audit.verifyChain();
    }

    /** Everything ever recorded against one entity, in order. */
    @GetMapping("/audit/trail/{entityType}/{entityId}")
    public List<Audit.AuditEvent> trail(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                        @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                        @PathVariable String entityType,
                                        @PathVariable String entityId) {
        Actor actor = CallerActor.of(actorId, actorRole);
        actor.require(Role.AUDITOR, Role.SCHEME_ADMIN, Role.VERIFIER);
        return audit.forEntity(entityType, entityId);
    }

    @GetMapping("/audit/events")
    public List<Audit.AuditEvent> range(@RequestHeader(CallerActor.ID_HEADER) String actorId,
                                        @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
                                        @RequestParam(defaultValue = "1") long from,
                                        @RequestParam(defaultValue = "200") long to) {
        Actor actor = CallerActor.of(actorId, actorRole);
        actor.require(Role.AUDITOR, Role.SCHEME_ADMIN);
        return audit.range(from, to);
    }
}
