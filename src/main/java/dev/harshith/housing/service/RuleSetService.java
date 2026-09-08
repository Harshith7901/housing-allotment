package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.draw.QuotaCalculator;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.core.model.RulesCodec;
import dev.harshith.housing.persistence.RuleSetEntity;
import dev.harshith.housing.persistence.SchemeEntity;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.persistence.repo.RuleSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Publishes and reads rule sets.
 *
 * <p>Publishing runs the apportionment immediately, before the rule set is stored, and
 * refuses the rule set if it cannot be satisfied — quotas that sum above 100%, horizontal
 * minima that cannot fit inside a category. Finding that out at draw time, with the
 * applicants already on the roll and a public announcement scheduled, is how schemes end
 * up making an indefensible decision under time pressure. The right moment to reject an
 * unsatisfiable rule is before anybody has applied under it.
 */
@Service
public class RuleSetService {

    private final RuleSetRepository repository;
    private final SchemeService schemes;
    private final AuditService audit;
    private final Clock clock;

    public RuleSetService(RuleSetRepository repository,
                          SchemeService schemes,
                          AuditService audit,
                          Clock clock) {
        this.repository = repository;
        this.schemes = schemes;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RuleSetEntity publish(Actor actor, Rules.RuleSet ruleSet) {
        actor.require(Role.SCHEME_ADMIN);
        SchemeEntity scheme = schemes.get(ruleSet.schemeCode());
        if (scheme.getPhase().isFrozen()) {
            throw new PhaseViolationException("scheme " + scheme.getSchemeCode() + " is in phase "
                    + scheme.getPhase() + "; a rule set cannot be published once a roll is frozen");
        }
        if (repository.existsById(ruleSet.version())) {
            throw new PhaseViolationException("rule set " + ruleSet.version()
                    + " is already published and rule sets are immutable; publish a new version instead");
        }

        // Prove the rules are arithmetically satisfiable before accepting them.
        Draw.SeatPlan plan = QuotaCalculator.plan(ruleSet);

        String encoded = RulesCodec.encode(ruleSet);
        RuleSetEntity entity = new RuleSetEntity();
        entity.setVersion(ruleSet.version());
        entity.setSchemeCode(ruleSet.schemeCode());
        entity.setEncoded(encoded);
        entity.setRuleSetHash(RulesCodec.hash(ruleSet));
        entity.setPublishedAt(clock.instant());
        entity.setPublishedBy(actor.id());
        entity.setPublishedRulesUri(ruleSet.publishedRulesUri());
        repository.save(entity);

        schemes.recordActiveRuleSet(ruleSet.schemeCode(), ruleSet.version());
        audit.append(actor, "RULESET_PUBLISHED", "RULE_SET", ruleSet.version(),
                "hash=" + entity.getRuleSetHash()
                        + " totalUnits=" + ruleSet.totalUnits()
                        + " seatPlan=" + summarise(plan));
        return entity;
    }

    @Transactional(readOnly = true)
    public RuleSetEntity entity(String version) {
        return repository.findById(version)
                .orElseThrow(() -> new NotFoundException("no such rule set version: " + version));
    }

    /** Decodes the stored canonical text. The text is the record; this is the derived view. */
    @Transactional(readOnly = true)
    public Rules.RuleSet load(String version) {
        return RulesCodec.decode(entity(version).getEncoded());
    }

    @Transactional(readOnly = true)
    public Rules.RuleSet active(String schemeCode) {
        SchemeEntity scheme = schemes.get(schemeCode);
        if (scheme.getActiveRuleSetVersion() == null) {
            throw new PhaseViolationException("scheme " + schemeCode + " has no published rule set yet");
        }
        return load(scheme.getActiveRuleSetVersion());
    }

    @Transactional(readOnly = true)
    public List<RuleSetEntity> history(String schemeCode) {
        return repository.findBySchemeCodeOrderByPublishedAtDesc(schemeCode);
    }

    /**
     * Produces an amended rule set for a changed inventory, ready to be published as a new
     * version.
     *
     * <p>The number of flats is part of the published rules, so it cannot simply be
     * adjusted at freeze time when units are withdrawn for a construction defect or an
     * injunction. This builds the amendment; publishing it is a separate, recorded act
     * under a new version number, and the roll freeze refuses to proceed until the rule
     * set and the actual inventory agree.
     */
    public Rules.RuleSet amendInventory(Rules.RuleSet ruleSet, String newVersion, int totalUnits) {
        return new Rules.RuleSet(newVersion, ruleSet.schemeCode(), totalUnits, ruleSet.openCode(),
                ruleSet.reservedQuotas(), ruleSet.horizontalQuotas(), ruleSet.residency(),
                ruleSet.lapsePolicy(), ruleSet.waitlistSize(), ruleSet.publishedRulesUri());
    }

    private String summarise(Draw.SeatPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Draw.VerticalSeats v : plan.verticals()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v.code()).append('=').append(v.seats());
        }
        return sb.toString();
    }

    /** Guards against a scheme phase that forbids changing anything on an application. */
    public void assertMutable(SchemePhase phase) {
        if (phase.isFrozen()) {
            throw new PhaseViolationException("the roll is frozen (phase " + phase
                    + "); applications are read-only. A correction after freezing requires a new roll.");
        }
    }
}
