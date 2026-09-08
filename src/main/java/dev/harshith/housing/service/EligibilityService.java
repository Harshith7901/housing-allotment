package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.EligibilityCheckEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.persistence.repo.EligibilityCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Records evidenced eligibility decisions and derives an application's status from them.
 *
 * <p>Status is <em>derived</em>, never set directly. An application is ineligible because
 * a named officer recorded a specific failed condition against a specific document, and
 * removing that record is the only way to change the status. There is no endpoint that
 * simply writes {@code INELIGIBLE}, because such an endpoint is the one an operator
 * reaches for at 6pm on the day of the freeze.
 */
@Service
public class EligibilityService {

    private final EligibilityCheckRepository checks;
    private final ApplicationRepository applications;
    private final SchemeService schemes;
    private final AuditService audit;
    private final Clock clock;

    public EligibilityService(EligibilityCheckRepository checks,
                              ApplicationRepository applications,
                              SchemeService schemes,
                              AuditService audit,
                              Clock clock) {
        this.checks = checks;
        this.applications = applications;
        this.schemes = schemes;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ApplicationEntity record(Actor actor,
                                    String applicationId,
                                    String checkCode,
                                    boolean passed,
                                    String reason,
                                    String evidenceRef) {
        actor.require(Role.VERIFIER, Role.SCHEME_ADMIN);
        ApplicationEntity application = applications.findById(applicationId)
                .orElseThrow(() -> new dev.harshith.housing.api.NotFoundException(
                        "no such application: " + applicationId));
        schemes.requirePhase(application.getSchemeCode(),
                SchemePhase.DEDUPLICATION, SchemePhase.VERIFICATION);

        // Maker-checker. The clerk who keyed the form cannot be the officer who verifies it.
        actor.requireDifferentFrom(application.getReceivedBy(), "eligibility verification");

        if (!passed && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "a failed eligibility check requires a reason that can be shown to the applicant");
        }

        EligibilityCheckEntity check = new EligibilityCheckEntity();
        check.setCheckId("ELG-" + UUID.randomUUID());
        check.setApplicationId(applicationId);
        check.setCheckCode(checkCode);
        check.setPassed(passed);
        check.setReason(reason);
        check.setEvidenceRef(evidenceRef);
        check.setDecidedBy(actor.id());
        check.setDecidedAt(clock.instant());
        checks.save(check);

        ApplicationEntity updated = recomputeStatus(application);

        audit.append(actor, "ELIGIBILITY_RECORDED", "APPLICATION", applicationId,
                "check=" + checkCode + " passed=" + passed
                        + " evidence=" + (evidenceRef == null ? "-" : evidenceRef)
                        + " reason=" + (reason == null ? "-" : reason)
                        + " resultingStatus=" + updated.getStatus());
        return updated;
    }

    /**
     * A superseded application is left alone: it carries no ticket, so its eligibility is
     * moot, and overwriting the supersession would lose the reason it holds no ticket.
     * Anything with a failed check becomes ineligible with that reason attached. Anything
     * with at least one recorded check and no failures becomes eligible.
     */
    private ApplicationEntity recomputeStatus(ApplicationEntity application) {
        if (application.getStatus() == ApplicationStatus.SUPERSEDED) {
            return application;
        }
        List<EligibilityCheckEntity> recorded =
                checks.findByApplicationIdOrderByDecidedAtAsc(application.getApplicationId());

        EligibilityCheckEntity failed = recorded.stream()
                .filter(c -> !c.isPassed())
                .findFirst()
                .orElse(null);

        if (failed != null) {
            application.setStatus(ApplicationStatus.INELIGIBLE);
            application.setStatusReason(failed.getCheckCode() + ": " + failed.getReason());
        } else if (!recorded.isEmpty()) {
            application.setStatus(ApplicationStatus.ELIGIBLE);
            application.setStatusReason("All " + recorded.size() + " recorded eligibility checks passed");
        }
        application.setUpdatedAt(clock.instant());
        return applications.save(application);
    }

    @Transactional(readOnly = true)
    public List<EligibilityCheckEntity> forApplication(String applicationId) {
        return checks.findByApplicationIdOrderByDecidedAtAsc(applicationId);
    }
}
