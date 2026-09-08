package dev.harshith.housing.demo;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.Role;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.core.sim.SyntheticData;
import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawRollEntity;
import dev.harshith.housing.persistence.DuplicateLinkEntity;
import dev.harshith.housing.persistence.ReviewDecision;
import dev.harshith.housing.persistence.SchemePhase;
import dev.harshith.housing.service.AllotmentService;
import dev.harshith.housing.service.AuditService;
import dev.harshith.housing.service.DeduplicationService;
import dev.harshith.housing.service.DrawService;
import dev.harshith.housing.service.RollService;
import dev.harshith.housing.service.RuleSetService;
import dev.harshith.housing.service.SchemeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Builds a complete, published scheme at startup under the {@code dev} profile, so that
 * every endpoint has something real behind it the moment the application is up.
 *
 * <p>The pipeline runs with <b>four distinct actors</b>, which is not decoration: the
 * maker–checker rules are real, and a single-actor run would fail at the point where the
 * officer who committed the seed tries to execute the draw. Watching it work with four
 * identities is a more useful demonstration than a comment claiming it does.
 */
@Component
@ConditionalOnProperty(name = "housing.demo.enabled", havingValue = "true")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private static final String SCHEME = "SCH-2026-01";
    private static final String RULE_SET_VERSION = "2026-PHASE-1";
    private static final String ROLL_ID = "ROLL-2026-01";
    private static final String DRAW_ID = "DRAW-2026-01";

    private static final Actor REGISTRAR = new Actor("registrar@scheme", Role.SCHEME_ADMIN);
    private static final Actor RETURNING_OFFICER = new Actor("returning.officer@scheme", Role.SCHEME_ADMIN);
    private static final Actor REVIEWER = new Actor("reviewer.02@scheme", Role.VERIFIER);

    private final SchemeService schemes;
    private final RuleSetService ruleSets;
    private final DemoDataService fixture;
    private final DeduplicationService deduplication;
    private final RollService rolls;
    private final DrawService draws;
    private final AllotmentService allotments;
    private final AuditService audit;

    @Value("${housing.demo.households:3600}")
    private int households;
    @Value("${housing.demo.duplicates:420}")
    private int duplicates;
    @Value("${housing.demo.units:600}")
    private int unitCount;
    @Value("${housing.demo.seed:20260401}")
    private long dataSeed;
    @Value("${housing.demo.run-full-pipeline:true}")
    private boolean runFullPipeline;

    public DemoDataLoader(SchemeService schemes,
                          RuleSetService ruleSets,
                          DemoDataService fixture,
                          DeduplicationService deduplication,
                          RollService rolls,
                          DrawService draws,
                          AllotmentService allotments,
                          AuditService audit) {
        this.schemes = schemes;
        this.ruleSets = ruleSets;
        this.fixture = fixture;
        this.deduplication = deduplication;
        this.rolls = rolls;
        this.draws = draws;
        this.allotments = allotments;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.currentTimeMillis();
        log.info("loading demo scheme: {} applications for {} flats",
                households + duplicates, unitCount);

        schemes.create(REGISTRAR, SCHEME, "Phase 1 housing scheme, 2026");
        ruleSets.publish(REGISTRAR, rules(unitCount));

        SyntheticData.Dataset data = SyntheticData.generate(dataSeed, households, duplicates, unitCount);
        fixture.loadUnits(REGISTRAR, SCHEME, data);
        fixture.loadApplications(REGISTRAR, SCHEME, data);

        if (!runFullPipeline) {
            log.info("demo data loaded; scheme left in {} for manual driving of the API",
                    schemes.get(SCHEME).getPhase());
            return;
        }

        schemes.advance(REGISTRAR, SCHEME, SchemePhase.INTAKE_CLOSED);
        schemes.advance(REGISTRAR, SCHEME, SchemePhase.DEDUPLICATION);

        DeduplicationService.Summary dedup = deduplication.run(REVIEWER, SCHEME);
        log.info("deduplication: {} comparisons after blocking, {} auto-linked, {} queued, {} clusters",
                dedup.comparisons(), dedup.autoLinked(), dedup.queuedForReview(), dedup.clusters());

        // A reviewer works the queue. The threshold used here is a demo convenience; in a
        // real scheme each of these is a person looking at two forms.
        int merged = 0;
        for (DuplicateLinkEntity link : List.copyOf(deduplication.reviewQueue(SCHEME))) {
            ReviewDecision decision = link.getScore() >= 0.86
                    ? ReviewDecision.SAME_HOUSEHOLD
                    : ReviewDecision.DIFFERENT_HOUSEHOLDS;
            deduplication.decide(REVIEWER, link.getLinkId(), decision,
                    "demo review at score " + String.format(java.util.Locale.ROOT, "%.4f", link.getScore()));
            if (decision == ReviewDecision.SAME_HOUSEHOLD) {
                merged++;
            }
        }
        log.info("review queue resolved: {} merged, {} kept distinct",
                merged, dedup.queuedForReview() - merged);

        schemes.advance(REGISTRAR, SCHEME, SchemePhase.VERIFICATION);
        long eligible = fixture.verifyAll(REVIEWER, SCHEME, data);
        log.info("verification recorded: {} eligible applications", eligible);

        DrawRollEntity roll = rolls.freeze(REGISTRAR, SCHEME, ROLL_ID);
        log.info("roll frozen: {} entries, rollHash={}", roll.getEntryCount(), roll.getRollHash());

        DrawEntity committed = draws.commitSeed(REGISTRAR, SCHEME, DRAW_ID,
                "Six-digit winning number of the State lottery draw of 25 May 2026, "
                        + "as published in the official gazette");
        log.info("seed committed: {}", committed.getCommitmentHex());

        // A different officer executes the draw: the same one is refused by design.
        Draw.DrawOutcome outcome = draws.execute(RETURNING_OFFICER, DRAW_ID,
                "STATE-LOTTERY-2026-05-25:481902");
        log.info("draw executed: seed={} resultHash={} selected={}",
                outcome.seedHex(), outcome.resultHash(), outcome.selectedCount());

        draws.publish(REGISTRAR, DRAW_ID);
        allotments.assignUnits(REGISTRAR, DRAW_ID);

        log.info("audit chain: {} events, head={}, verification={}",
                audit.eventCount(), audit.headHash(), audit.verifyChain().intact());

        String sample = outcome.selections().stream()
                .filter(s -> s.outcome() == Draw.Outcome.NOT_SELECTED)
                .map(Draw.Selection::applicationId)
                .findFirst()
                .orElse(outcome.selections().get(0).applicationId());

        log.info("""

                ================================================================
                Demo scheme ready in {}ms. Try:

                  # what the public can see, with no credentials at all
                  curl localhost:8080/api/public/draws/{}
                  curl localhost:8080/api/public/draws/{}/result | head -40
                  curl -O -J localhost:8080/api/public/draws/{}/verification-bundle

                  # why one applicant did not get a flat
                  curl localhost:8080/api/public/applications/{}/explanation

                  # the audit head, meant to be quoted outside this system
                  curl localhost:8080/api/public/audit/head

                  # verify the whole draw independently
                  unzip -o {}-verification-bundle.zip -d bundle && python3 verify/verify.py bundle

                Actor headers for the admin endpoints:
                  -H 'X-Actor-Id: registrar@scheme' -H 'X-Actor-Role: SCHEME_ADMIN'
                ================================================================
                """,
                System.currentTimeMillis() - startedAt, DRAW_ID, DRAW_ID, DRAW_ID, sample, DRAW_ID);
    }

    private Rules.RuleSet rules(int totalUnits) {
        return new Rules.RuleSet(
                RULE_SET_VERSION,
                SCHEME,
                totalUnits,
                "OPEN",
                List.of(
                        new Rules.ReservedQuota("CAT_A", "Reserved category A", new BigDecimal("15.0")),
                        new Rules.ReservedQuota("CAT_B", "Reserved category B", new BigDecimal("7.5")),
                        new Rules.ReservedQuota("CAT_C", "Reserved category C", new BigDecimal("27.0"))),
                List.of(
                        new Rules.HorizontalQuota("WOMEN", "Women applicants", new BigDecimal("30.0")),
                        new Rules.HorizontalQuota("PWD", "Persons with disability", new BigDecimal("5.0")),
                        new Rules.HorizontalQuota("EX_SERVICE", "Ex-servicemen", new BigDecimal("3.0")),
                        new Rules.HorizontalQuota("SENIOR", "Senior citizens", new BigDecimal("5.0"))),
                new Rules.ResidencyRule(Rules.ResidencyMode.PRIORITY_TIER, 3),
                Rules.LapsePolicy.LAPSE_TO_OPEN,
                150,
                "https://example.gov/schemes/SCH-2026-01/rules.pdf");
    }
}
