package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import dev.harshith.housing.persistence.SchemeEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.ApplicationRepository;
import dev.harshith.housing.support.Csv;
import dev.harshith.housing.support.GovernmentIdHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.harshith.housing.web.dto.IntakeRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Accepts applications from both channels and applies corrections before the freeze. */
@Service
public class IntakeService {

    private final ApplicationRepository repository;
    private final SchemeService schemes;
    private final RuleSetService ruleSets;
    private final GovernmentIdHasher idHasher;
    private final AuditService audit;
    private final Clock clock;

    public IntakeService(ApplicationRepository repository,
                         SchemeService schemes,
                         RuleSetService ruleSets,
                         GovernmentIdHasher idHasher,
                         AuditService audit,
                         Clock clock) {
        this.repository = repository;
        this.schemes = schemes;
        this.ruleSets = ruleSets;
        this.idHasher = idHasher;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param created false when an idempotency key matched an application already on
     *                record, so the caller can distinguish "created" from "you already
     *                sent this" without the controller having to count rows
     */
    public record Intake(ApplicationEntity application, boolean created) {
    }

    @Transactional
    public Intake submit(Actor actor, IntakeRequest request) {
        actor.require(Role.APPLICANT, Role.DATA_ENTRY, Role.SCHEME_ADMIN);
        schemes.requirePhase(request.schemeCode(), SchemePhase.INTAKE_OPEN);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existing = repository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                // Not an error. The caller retried; give them back the same application.
                return new Intake(existing.get(), false);
            }
        }

        Rules.RuleSet rules = ruleSets.active(request.schemeCode());
        validateCodes(rules, request.verticalCode(), request.horizontalCodes());

        ApplicationEntity entity = new ApplicationEntity();
        entity.setApplicationId(newApplicationId());
        entity.setSchemeCode(request.schemeCode());
        entity.setChannel(request.channel());
        entity.setSubmittedAt(request.submittedAt() == null ? clock.instant() : request.submittedAt());
        entity.setBatchId(request.batchId());
        entity.setIdempotencyKey(blankToNull(request.idempotencyKey()));
        entity.setFullName(request.fullName().trim());
        entity.setRelativeName(request.relativeName());
        entity.setGovernmentIdHash(idHasher.hash(request.governmentId()));
        entity.setGovernmentIdLast4(idHasher.last4(request.governmentId()));
        entity.setPhone(request.phone());
        entity.setDateOfBirth(request.dateOfBirth());
        entity.setAddressLine(request.addressLine());
        entity.setWardCode(request.wardCode() == null ? null : request.wardCode().trim().toUpperCase(Locale.ROOT));
        entity.setResidencyYears(request.residencyYears());
        entity.setVerticalCode(request.verticalCode());
        entity.setHorizontalCodes(Csv.join(new TreeSet<>(
                request.horizontalCodes() == null ? Set.of() : request.horizontalCodes())));
        entity.setUnitTypePreferences(Csv.join(
                request.unitTypePreferences() == null ? List.of() : request.unitTypePreferences()));
        entity.setStatus(ApplicationStatus.RECEIVED);
        entity.setReceivedBy(actor.id());
        entity.setCreatedAt(clock.instant());
        entity.setUpdatedAt(clock.instant());
        repository.save(entity);

        audit.append(actor, "APPLICATION_RECEIVED", "APPLICATION", entity.getApplicationId(),
                "channel=" + entity.getChannel()
                        + " batch=" + (entity.getBatchId() == null ? "-" : entity.getBatchId())
                        + " vertical=" + entity.getVerticalCode()
                        + " horizontal=" + entity.getHorizontalCodes()
                        + " ward=" + entity.getWardCode()
                        + " submittedAt=" + entity.getSubmittedAt());
        return new Intake(entity, true);
    }

    /**
     * Corrects a keying error. Permitted only before the roll is frozen, only by somebody
     * other than the clerk who keyed the form, and always with a stated reason. Every
     * field that changed is named in the audit entry with its old and new value, because
     * a correction to a ward code or a category is a change to somebody's odds.
     */
    @Transactional
    public ApplicationEntity correct(Actor actor,
                                     String applicationId,
                                     IntakeRequest correction,
                                     String reason) {
        actor.require(Role.DATA_ENTRY, Role.VERIFIER, Role.SCHEME_ADMIN);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a correction requires a stated reason");
        }
        ApplicationEntity entity = get(applicationId);
        SchemeEntity scheme = schemes.get(entity.getSchemeCode());
        ruleSets.assertMutable(scheme.getPhase());

        Rules.RuleSet rules = ruleSets.active(entity.getSchemeCode());
        validateCodes(rules, correction.verticalCode(), correction.horizontalCodes());

        StringBuilder changes = new StringBuilder();
        changes.append(diff("fullName", entity.getFullName(), correction.fullName()));
        changes.append(diff("phone", entity.getPhone(), correction.phone()));
        changes.append(diff("dateOfBirth", entity.getDateOfBirth(), correction.dateOfBirth()));
        changes.append(diff("wardCode", entity.getWardCode(), correction.wardCode()));
        changes.append(diff("residencyYears", entity.getResidencyYears(), correction.residencyYears()));
        changes.append(diff("verticalCode", entity.getVerticalCode(), correction.verticalCode()));

        entity.setFullName(correction.fullName().trim());
        entity.setRelativeName(correction.relativeName());
        if (correction.governmentId() != null && !correction.governmentId().isBlank()) {
            entity.setGovernmentIdHash(idHasher.hash(correction.governmentId()));
            entity.setGovernmentIdLast4(idHasher.last4(correction.governmentId()));
            changes.append("governmentId:changed ");
        }
        entity.setPhone(correction.phone());
        entity.setDateOfBirth(correction.dateOfBirth());
        entity.setAddressLine(correction.addressLine());
        entity.setWardCode(correction.wardCode() == null
                ? null : correction.wardCode().trim().toUpperCase(Locale.ROOT));
        entity.setResidencyYears(correction.residencyYears());
        entity.setVerticalCode(correction.verticalCode());
        entity.setHorizontalCodes(Csv.join(new TreeSet<>(
                correction.horizontalCodes() == null ? Set.of() : correction.horizontalCodes())));
        entity.setUnitTypePreferences(Csv.join(
                correction.unitTypePreferences() == null ? List.of() : correction.unitTypePreferences()));
        entity.setUpdatedAt(clock.instant());
        repository.save(entity);

        audit.append(actor, "APPLICATION_CORRECTED", "APPLICATION", applicationId,
                "reason=" + reason + " changes=[" + changes.toString().trim() + "]");
        return entity;
    }

    @Transactional(readOnly = true)
    public ApplicationEntity get(String applicationId) {
        return repository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("no such application: " + applicationId));
    }

    /**
     * Application ids are random rather than sequential.
     *
     * <p>A sequential id leaks the order in which forms arrived, and the ticket is derived
     * from the id, so a sequential id would also let a well-informed applicant reason about
     * other people's tickets relative to their own. Randomness costs nothing here and the
     * id stays short enough to read out over a counter.
     */
    private String newApplicationId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "APP-" + LocalDate.now(clock).getYear() + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            if (!repository.existsById(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not allocate an unused application id after 10 attempts");
    }

    private void validateCodes(Rules.RuleSet rules, String verticalCode, Set<String> horizontalCodes) {
        if (!rules.knowsVertical(verticalCode)) {
            throw new IllegalArgumentException("category " + verticalCode
                    + " is not defined in rule set " + rules.version()
                    + "; defined categories are " + rules.verticalCodes());
        }
        if (horizontalCodes == null) {
            return;
        }
        for (String code : horizontalCodes) {
            if (!rules.knowsHorizontal(code)) {
                throw new IllegalArgumentException("horizontal quota " + code
                        + " is not defined in rule set " + rules.version());
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String diff(String field, Object before, Object after) {
        String from = String.valueOf(before);
        String to = String.valueOf(after);
        return from.equals(to) ? "" : field + ":" + from + "->" + to + " ";
    }
}
