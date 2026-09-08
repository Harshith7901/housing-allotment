package dev.harshith.housing.web;

import dev.harshith.housing.api.Role;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.EligibilityCheckEntity;
import dev.harshith.housing.service.EligibilityService;
import dev.harshith.housing.service.IntakeService;
import dev.harshith.housing.web.dto.IntakeRequest;
import dev.harshith.housing.web.dto.Requests;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Intake, correction and eligibility for individual applications. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final IntakeService intake;
    private final EligibilityService eligibility;

    public ApplicationController(IntakeService intake, EligibilityService eligibility) {
        this.intake = intake;
        this.eligibility = eligibility;
    }

    /**
     * Accepts an application from either channel.
     *
     * <p>Returns 200 rather than 201 when an {@code idempotencyKey} matched an existing
     * application, so a client that retried can tell the difference between "created" and
     * "you already sent this".
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @Valid @RequestBody IntakeRequest request) {

        IntakeService.Intake result = intake.submit(CallerActor.of(actorId, actorRole), request);
        ApplicationEntity application = result.application();
        boolean created = result.created();

        Map<String, Object> body = Map.of(
                "applicationId", application.getApplicationId(),
                "status", application.getStatus().name(),
                "submittedAt", application.getSubmittedAt().toString(),
                "channel", application.getChannel().name(),
                "deduplicated", !created,
                "message", created
                        ? "Application received. Keep this application id: it is what your lottery "
                            + "ticket is derived from and how you check your result."
                        : "This submission matched an idempotency key already on record; the original "
                            + "application is returned unchanged.");
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/{applicationId}")
    public ApplicationEntity get(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String applicationId) {
        requireReader(actorId, actorRole);
        return intake.get(applicationId);
    }

    /** Corrects a keying error. Refused once the roll is frozen. */
    @PutMapping("/{applicationId}")
    public ApplicationEntity correct(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String applicationId,
            @Valid @RequestBody Requests.Correction correction) {
        return intake.correct(CallerActor.of(actorId, actorRole), applicationId,
                correction.application(), correction.reason());
    }

    @PostMapping("/{applicationId}/eligibility-checks")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ApplicationEntity recordEligibility(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String applicationId,
            @Valid @RequestBody Requests.RecordEligibility request) {
        return eligibility.record(CallerActor.of(actorId, actorRole), applicationId,
                request.checkCode(), request.passed(), request.reason(), request.evidenceRef());
    }

    @GetMapping("/{applicationId}/eligibility-checks")
    public List<EligibilityCheckEntity> eligibilityChecks(
            @RequestHeader(CallerActor.ID_HEADER) String actorId,
            @RequestHeader(CallerActor.ROLE_HEADER) String actorRole,
            @PathVariable String applicationId) {
        requireReader(actorId, actorRole);
        return eligibility.forApplication(applicationId);
    }

    /**
     * Reads of an application are staff-only, because this is the one place the system
     * hands back the identifying details behind an application id -- name, relative's
     * name, phone, date of birth, address, and the last four digits of the government
     * identifier. An applicant's own route to their case is the public explanation
     * endpoint, which is deliberately built to carry no personal data at all.
     */
    private static void requireReader(String actorId, String actorRole) {
        CallerActor.of(actorId, actorRole)
                .require(Role.DATA_ENTRY, Role.VERIFIER, Role.SCHEME_ADMIN, Role.AUDITOR);
    }

}
