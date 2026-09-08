package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.persistence.SchemeEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.SchemeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/** Owns the scheme record and every phase transition. */
@Service
public class SchemeService {

    private final SchemeRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public SchemeService(SchemeRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public SchemeEntity create(Actor actor, String schemeCode, String name) {
        actor.require(Role.SCHEME_ADMIN);
        if (repository.existsById(schemeCode)) {
            throw new PhaseViolationException("scheme " + schemeCode + " already exists");
        }
        SchemeEntity scheme = new SchemeEntity(schemeCode, name, SchemePhase.INTAKE_OPEN, clock.instant());
        repository.save(scheme);
        audit.append(actor, "SCHEME_CREATED", "SCHEME", schemeCode, "name=" + name);
        return scheme;
    }

    @Transactional(readOnly = true)
    public SchemeEntity get(String schemeCode) {
        return repository.findById(schemeCode)
                .orElseThrow(() -> new NotFoundException("no such scheme: " + schemeCode));
    }

    /**
     * Advances the scheme one step. There is no "set phase" operation and no way to go
     * backwards: a scheme that has frozen its roll cannot quietly return to verification,
     * because that is precisely the manoeuvre by which a published list gets edited.
     */
    @Transactional
    public SchemeEntity advance(Actor actor, String schemeCode, SchemePhase next) {
        actor.require(Role.SCHEME_ADMIN);
        SchemeEntity scheme = get(schemeCode);
        if (!scheme.getPhase().canAdvanceTo(next)) {
            throw new PhaseViolationException("scheme " + schemeCode + " is in phase " + scheme.getPhase()
                    + " and may only advance to " + scheme.getPhase().allowedNext()
                    + "; " + next + " was requested");
        }
        SchemePhase from = scheme.getPhase();
        scheme.setPhase(next);
        scheme.setPhaseChangedAt(clock.instant());
        scheme.setPhaseChangedBy(actor.id());
        repository.save(scheme);
        audit.append(actor, "PHASE_ADVANCED", "SCHEME", schemeCode, "from=" + from + " to=" + next);
        return scheme;
    }

    /** Throws unless the scheme is currently in one of {@code allowed}. */
    @Transactional(readOnly = true)
    public SchemeEntity requirePhase(String schemeCode, SchemePhase... allowed) {
        SchemeEntity scheme = get(schemeCode);
        List<SchemePhase> permitted = Arrays.asList(allowed);
        if (!permitted.contains(scheme.getPhase())) {
            throw new PhaseViolationException("this action requires the scheme to be in one of " + permitted
                    + " but " + schemeCode + " is in " + scheme.getPhase());
        }
        return scheme;
    }

    @Transactional
    public void recordActiveRuleSet(String schemeCode, String version) {
        SchemeEntity scheme = get(schemeCode);
        scheme.setActiveRuleSetVersion(version);
        repository.save(scheme);
    }

    @Transactional
    public void recordActiveRoll(String schemeCode, String rollId) {
        SchemeEntity scheme = get(schemeCode);
        scheme.setActiveRollId(rollId);
        repository.save(scheme);
    }
}
