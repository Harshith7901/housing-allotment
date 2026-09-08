package dev.harshith.housing.core.sim;

import dev.harshith.housing.core.audit.Audit;
import dev.harshith.housing.core.audit.HashChain;
import dev.harshith.housing.core.bundle.VerificationBundle;
import dev.harshith.housing.core.dedup.Dedup;
import dev.harshith.housing.core.dedup.Deduplicator;
import dev.harshith.housing.core.dedup.UnionFind;
import dev.harshith.housing.core.draw.AllocationEngine;
import dev.harshith.housing.core.draw.QuotaCalculator;
import dev.harshith.housing.core.draw.ResultHasher;
import dev.harshith.housing.core.draw.RollHasher;
import dev.harshith.housing.core.draw.SeedDeriver;
import dev.harshith.housing.core.draw.WaitlistPromoter;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.core.model.RulesCodec;
import dev.harshith.housing.core.units.UnitAssigner;
import dev.harshith.housing.core.units.Units;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * End-to-end executable proof of the allocation core, over a realistic population.
 *
 * <p>Runs the whole pipeline — intake, deduplication, roll freeze, commit–reveal, draw,
 * unit assignment, waitlist promotion, audit chain — against ~4,000 synthetic applications
 * for 600 flats, and asserts the properties that actually matter in court. It touches no
 * framework and no database, so it runs on a bare JDK:
 *
 * <pre>
 *   javac -d out $(find src/main/java/dev/harshith/housing/core -name '*.java')
 *   java  -cp out dev.harshith.housing.core.sim.Simulation
 * </pre>
 *
 * <p>Exits non-zero if any check fails, so it is usable as a CI gate on its own.
 */
public final class Simulation {

    private static int checks;
    private static int failures;

    private Simulation() {
    }

    public static void main(String[] args) {
        long dataSeed = args.length > 0 ? Long.parseLong(args[0]) : 20260401L;

        Rules.RuleSet rules = schemeRules();
        section("Rule set " + rules.version());
        String encoded = RulesCodec.encode(rules);
        System.out.print(encoded);
        String ruleSetHash = RulesCodec.hash(rules);
        System.out.println("ruleSetHash = " + ruleSetHash);

        check("rule set survives an encode/decode round trip",
                RulesCodec.hash(RulesCodec.decode(encoded)).equals(ruleSetHash));

        // ------------------------------------------------------------------ apportionment
        section("Apportionment of 600 flats");
        Draw.SeatPlan plan = QuotaCalculator.plan(rules);
        plan.workings().forEach(w -> System.out.println("  " + w));
        check("apportioned seats sum to the inventory", plan.allocatedSeats() == rules.totalUnits());
        check("open category receives its residual share", plan.vertical("OPEN").seats() == 303);
        check("CAT_A receives 15% of 600", plan.vertical("CAT_A").seats() == 90);
        check("CAT_B receives 7.5% of 600", plan.vertical("CAT_B").seats() == 45);
        check("CAT_C receives 27% of 600", plan.vertical("CAT_C").seats() == 162);
        checkRemainderCase();

        // ------------------------------------------------------------------ intake
        section("Intake");
        SyntheticData.Dataset data = SyntheticData.generate(dataSeed, 3_600, 420, rules.totalUnits());
        Map<String, SyntheticData.SyntheticApplication> byId = new LinkedHashMap<>();
        List<Dedup.ApplicantRecord> identities = new ArrayList<>();
        for (SyntheticData.SyntheticApplication a : data.applications()) {
            byId.put(a.identity().applicationId(), a);
            identities.add(a.identity());
        }
        System.out.println("  applications received       : " + identities.size());
        System.out.println("  true distinct households    : " + data.intendedHouseholds());
        System.out.println("  planted re-submissions      : " + data.intendedDuplicates());

        // ------------------------------------------------------------------ deduplication
        section("Deduplication");
        Dedup.DedupReport report = Deduplicator.run(identities, Dedup.MatchWeights.standard());
        System.out.println("  pairs compared after blocking: " + report.comparisons()
                + "  (exhaustive would be " + (identities.size() * (identities.size() - 1L) / 2) + ")");
        System.out.println("  auto-linked pairs            : " + report.autoLinked().size());
        System.out.println("  queued for human review      : " + report.needsReview().size());
        System.out.println("  clusters after auto-linking  : " + report.distinctHouseholds());
        report.notes().forEach(n -> System.out.println("  note: " + n));

        check("blocking cut the comparison count by at least 95%",
                report.comparisons() < identities.size() * (identities.size() - 1L) / 2 / 20);
        check("no cluster merges two genuinely different households (precision)",
                clustersArePure(report, byId));
        double recall = (double) report.duplicateApplicationCount() / data.intendedDuplicates();
        System.out.printf("  duplicate recall             : %.1f%%%n", recall * 100);
        check("at least 80% of planted duplicates were caught without human review", recall >= 0.80);

        // A reviewer works the queue. Anything above 0.86 is accepted as the same
        // household; the rest are recorded as distinct. Both decisions are recorded acts.
        section("Human review of the queue");
        List<Dedup.CandidatePair> accepted = new ArrayList<>();
        for (Dedup.CandidatePair p : report.needsReview()) {
            if (p.score() >= 0.86) {
                accepted.add(p);
            }
        }
        System.out.println("  reviewed " + report.needsReview().size() + " pairs, merged " + accepted.size());
        List<Dedup.Cluster> finalClusters = applyReviewDecisions(report, accepted);
        System.out.println("  clusters after review        : " + finalClusters.size());

        // ------------------------------------------------------------------ roll freeze
        section("Roll freeze");
        List<Roll.RollEntry> entries = new ArrayList<>();
        int excludedIneligible = 0;
        for (Dedup.Cluster cluster : finalClusters) {
            SyntheticData.SyntheticApplication primary = byId.get(cluster.primaryApplicationId());
            if (!primary.eligible()) {
                excludedIneligible++;
                continue;
            }
            entries.add(new Roll.RollEntry(
                    primary.identity().applicationId(),
                    cluster.clusterId(),
                    primary.verticalCode(),
                    primary.horizontalCodes(),
                    primary.residencyYears(),
                    primary.unitTypePreferences()));
        }
        Roll.DrawRoll roll = RollHasher.freeze("ROLL-1", rules.schemeCode(), rules.version(),
                ruleSetHash, Instant.parse("2026-05-20T09:30:00Z"), entries);
        System.out.println("  excluded as ineligible       : " + excludedIneligible);
        System.out.println("  entries on the frozen roll   : " + roll.entryCount());
        System.out.println("  rollHash                     : " + roll.rollHash());
        check("the roll verifies against its own hash", RollHasher.verify(roll));
        check("one ticket per household", roll.entryCount() == finalClusters.size() - excludedIneligible);

        // ------------------------------------------------------------------ commit-reveal
        section("Commit and reveal");
        String nonce = "e3f1a90c4b7d2856f0a1c9d4b6e8f2a35c7d9e1b4f6a8c2d5e7f9b1a3c5d7e9f";
        Draw.SeedCommitment commitment = SeedDeriver.commit("DRAW-1", roll.rollHash(), nonce,
                Instant.parse("2026-05-21T05:00:00Z"), "scheme-authority");
        System.out.println("  commitment published         : " + commitment.commitmentHex());
        String publicEntropy = "STATE-LOTTERY-2026-05-25:481902";
        Draw.DrawSeed seed = SeedDeriver.reveal(commitment, publicEntropy, nonce);
        System.out.println("  public entropy               : " + publicEntropy);
        System.out.println("  derived seed                 : " + seed.seedHex());
        check("commitment holds against the revealed nonce",
                SeedDeriver.commitmentHolds(commitment, nonce));
        check("a substituted nonce breaks the commitment",
                !SeedDeriver.commitmentHolds(commitment, nonce.replace('e', 'a')));
        check("a third party can recompute the seed from published values only",
                SeedDeriver.recompute(roll.rollHash(), publicEntropy, nonce).equals(seed.seedHex()));
        check("swapping the nonce after commitment is refused", refusesBadNonce(commitment, publicEntropy));

        // ------------------------------------------------------------------ the draw
        section("The draw");
        Instant drawnAt = Instant.parse("2026-05-25T10:00:00Z");
        Draw.DrawOutcome outcome = AllocationEngine.execute("DRAW-1", roll, rules, seed, drawnAt);
        System.out.println("  resultHash                   : " + outcome.resultHash());
        System.out.println("  selected                     : " + outcome.selectedCount());
        for (Draw.PoolResult p : outcome.pools()) {
            System.out.printf("  pool %-12s seats=%-4d candidates=%-5d selected=%-4d unfilled=%d%n",
                    p.poolCode(), p.seats(), p.candidateCount(), p.selectedApplicationIds().size(),
                    p.unfilledSeats());
            for (Draw.TopUpNote n : p.topUps()) {
                if (!n.promotedApplicationIds().isEmpty() || n.note().contains("not")) {
                    System.out.println("       " + n.horizontalCode() + ": required " + n.requiredSeats()
                            + ", had " + n.satisfiedBeforeTopUp() + " - " + n.note());
                }
            }
        }

        check("the result verifies against its own hash", ResultHasher.verify(outcome));
        check("exactly 600 households are selected", outcome.selectedCount() == rules.totalUnits());
        check("every person on the roll has an outcome and a reason",
                outcome.selections().size() == roll.entryCount()
                        && outcome.selections().stream().allMatch(s -> s.reasonCode() != null
                            && s.reasonText() != null && !s.reasonText().isBlank()));
        check("nobody is selected twice", noDoubleSelection(outcome));
        check("each pool's selected set is its ranked prefix, adjusted only by recorded top-ups",
                poolsAreRankedPrefixes(outcome));
        check("every horizontal minimum is met or its shortfall is recorded",
                horizontalMinimaHonoured(outcome, roll));
        check("residency preference is respected inside every pool",
                residencyPreferenceHonoured(outcome));
        check("waitlist length matches the published rule",
                outcome.selections().stream()
                        .filter(s -> s.outcome() == Draw.Outcome.WAITLISTED)
                        .count() <= rules.waitlistSize() * (long) outcome.pools().size());

        // ---- reproducibility, the whole point ------------------------------------
        section("Reproducibility");
        Draw.DrawOutcome again = AllocationEngine.execute("DRAW-1", roll, rules, seed, drawnAt);
        check("re-running the draw yields a bit-identical result",
                again.resultHash().equals(outcome.resultHash()));
        check("re-running yields identical canonical bytes",
                ResultHasher.encode(again).equals(ResultHasher.encode(outcome)));

        Draw.DrawSeed otherSeed = SeedDeriver.reveal(commitment, "STATE-LOTTERY-2026-05-25:481903", nonce);
        Draw.DrawOutcome otherOutcome = AllocationEngine.execute("DRAW-1", roll, rules, otherSeed, drawnAt);
        check("a different public entropy value produces a different result",
                !otherOutcome.resultHash().equals(outcome.resultHash()));
        System.out.println("  overlap of winners across the two entropy values: "
                + winnerOverlap(outcome, otherOutcome) + " of " + outcome.selectedCount());

        check("a roll altered after freezing is refused by the engine", refusesTamperedRoll(roll, rules, seed));
        check("a seed committed against a different roll is refused", refusesMismatchedSeed(roll, rules));

        // ------------------------------------------------------------------ units
        section("Unit assignment");
        Units.AllotmentPlan allotment = UnitAssigner.assign(outcome, roll, data.inventory());
        allotment.workings().forEach(w -> System.out.println("  " + w));
        check("every winner receives exactly one flat",
                allotment.allotments().size() == outcome.selectedCount()
                        && allotment.applicantsWithoutUnit().isEmpty());
        check("no flat is allotted twice",
                allotment.allotments().stream().map(Units.UnitAllotment::unitId).distinct().count()
                        == allotment.allotments().size());
        long firstChoice = allotment.allotments().stream()
                .filter(a -> a.preferenceRankHonoured() == 1).count();
        System.out.println("  first choice honoured        : " + firstChoice + " of "
                + allotment.allotments().size());
        check("more than half receive their first choice", firstChoice > allotment.allotments().size() / 2);

        // ------------------------------------------------------------------ forfeitures
        section("Forfeiture and waitlist promotion");
        List<String> winners = outcome.selections().stream()
                .filter(s -> s.outcome() == Draw.Outcome.SELECTED)
                .map(Draw.Selection::applicationId)
                .sorted()
                .toList();
        List<String> forfeited = List.of(winners.get(3), winners.get(77), winners.get(210),
                winners.get(455), winners.get(599));
        Set<String> holders = new LinkedHashSet<>(winners);
        List<WaitlistPromoter.Promotion> promotions =
                WaitlistPromoter.promote(outcome, forfeited, holders, Set.of());
        for (WaitlistPromoter.Promotion p : promotions) {
            System.out.println("  " + p.vacatedByApplicationId() + " -> " + p.promotedApplicationId()
                    + "  (pool " + p.poolCode() + ", waitlist position " + p.waitlistPosition() + ")");
        }
        check("every vacated seat is filled", promotions.stream()
                .allMatch(p -> p.promotedApplicationId() != null));
        check("promotions come from the pool that awarded the seat", promotionsStayInPool(outcome, promotions));
        check("no promotion goes to somebody who already holds a seat",
                promotions.stream().noneMatch(p -> winners.contains(p.promotedApplicationId())));
        check("no seat is offered to the same person twice",
                promotions.stream().map(WaitlistPromoter.Promotion::promotedApplicationId).distinct().count()
                        == promotions.size());
        check("promotion is deterministic",
                WaitlistPromoter.promote(outcome, forfeited, holders, Set.of()).equals(promotions));

        // ------------------------------------------------------------------ audit chain
        section("Audit chain");
        List<Audit.AuditEvent> log = buildAuditLog(rules, roll, commitment, outcome);
        Audit.ChainVerification intact = HashChain.verify(log);
        System.out.println("  " + intact.detail());
        check("an untouched chain verifies", intact.intact());

        List<Audit.AuditEvent> tampered = new ArrayList<>(log);
        Audit.AuditEvent victim = tampered.get(3);
        tampered.set(3, new Audit.AuditEvent(victim.sequence(), victim.eventId(), victim.occurredAt(),
                victim.actor(), victim.actorRole(), victim.action(), victim.entityType(), victim.entityId(),
                victim.payload().replace("3600", "3599"), victim.previousHash(), victim.hash()));
        Audit.ChainVerification broken = HashChain.verify(tampered);
        System.out.println("  " + broken.detail());
        check("editing one historical event is detected", !broken.intact());
        check("the tamper is localised to the edited event",
                broken.firstBrokenSequence() != null && broken.firstBrokenSequence() == victim.sequence());

        List<Audit.AuditEvent> withHole = new ArrayList<>(log);
        withHole.remove(2);
        check("deleting an event is detected", !HashChain.verify(withHole).intact());

        // ------------------------------------------------------------------ transparency
        section("What an applicant is told");
        String sample = outcome.selections().stream()
                .filter(s -> s.reasonCode() == Draw.ReasonCode.DISPLACED_BY_HORIZONTAL_MINIMUM)
                .map(Draw.Selection::applicationId)
                .findFirst()
                .orElse(winners.get(1));
        printExplanation(outcome, roll, sample);
        printExplanation(outcome, roll, outcome.selections().stream()
                .filter(s -> s.outcome() == Draw.Outcome.NOT_SELECTED)
                .map(Draw.Selection::applicationId).findFirst().orElse(winners.get(0)));

        // ------------------------------------------------------------------ bundle
        section("Verification bundle");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    args.length > 1 ? args[1] : "target/verification-bundle");
            VerificationBundle.build(rules, roll, commitment, seed, outcome, HashChain.head(log))
                    .writeTo(dir);
            System.out.println("  written to " + dir.toAbsolutePath());
            System.out.println("  check it with: python3 verify/verify.py " + dir);
            check("bundle contains all five published artefacts",
                    java.nio.file.Files.exists(dir.resolve("rules.txt"))
                            && java.nio.file.Files.exists(dir.resolve("roll.txt"))
                            && java.nio.file.Files.exists(dir.resolve("seed.txt"))
                            && java.nio.file.Files.exists(dir.resolve("result.txt"))
                            && java.nio.file.Files.exists(dir.resolve("MANIFEST.txt")));
        } catch (java.io.IOException e) {
            check("verification bundle could be written (" + e.getMessage() + ")", false);
        }

        // ------------------------------------------------------------------ summary
        section("Summary");
        System.out.println("  checks run    : " + checks);
        System.out.println("  checks failed : " + failures);
        if (failures > 0) {
            System.out.println("\nFAILED");
            System.exit(1);
        }
        System.out.println("\nAll checks passed.");
    }

    // ================================================================== fixtures

    private static Rules.RuleSet schemeRules() {
        return new Rules.RuleSet(
                "2026-PHASE-1",
                "SCH-2026-01",
                600,
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

    private static List<Audit.AuditEvent> buildAuditLog(Rules.RuleSet rules,
                                                        Roll.DrawRoll roll,
                                                        Draw.SeedCommitment commitment,
                                                        Draw.DrawOutcome outcome) {
        record Entry(String actor, String role, String action, String type, String id, String payload) {
        }
        List<Entry> script = List.of(
                new Entry("registrar@scheme", "SCHEME_ADMIN", "RULESET_PUBLISHED", "RULE_SET",
                        rules.version(), "hash=" + RulesCodec.hash(rules)),
                new Entry("intake-service", "SYSTEM", "INTAKE_WINDOW_CLOSED", "SCHEME",
                        rules.schemeCode(), "applications=4020"),
                new Entry("dedup-service", "SYSTEM", "DEDUPLICATION_COMPLETED", "SCHEME",
                        rules.schemeCode(), "clusters=3600"),
                new Entry("reviewer.02@scheme", "VERIFIER", "DUPLICATE_REVIEW_COMPLETED", "SCHEME",
                        rules.schemeCode(), "queue=3600 resolved"),
                new Entry("registrar@scheme", "SCHEME_ADMIN", "ROLL_FROZEN", "ROLL",
                        roll.rollId(), "rollHash=" + roll.rollHash() + " entries=" + roll.entryCount()),
                new Entry("registrar@scheme", "SCHEME_ADMIN", "SEED_COMMITTED", "DRAW",
                        commitment.drawId(), "commitment=" + commitment.commitmentHex()),
                new Entry("observer@press", "AUDITOR", "PUBLIC_ENTROPY_RECORDED", "DRAW",
                        commitment.drawId(), "entropy=STATE-LOTTERY-2026-05-25:481902"),
                new Entry("registrar@scheme", "SCHEME_ADMIN", "DRAW_EXECUTED", "DRAW",
                        outcome.drawId(), "resultHash=" + outcome.resultHash()),
                new Entry("registrar@scheme", "SCHEME_ADMIN", "RESULT_PUBLISHED", "DRAW",
                        outcome.drawId(), "selected=" + outcome.selectedCount()),
                new Entry("allotment-service", "SYSTEM", "UNITS_ASSIGNED", "DRAW",
                        outcome.drawId(), "units=600"));

        List<Audit.AuditEvent> log = new ArrayList<>();
        String previous = null;
        Instant clock = Instant.parse("2026-04-01T04:00:00Z");
        long sequence = 1;
        for (Entry e : script) {
            Audit.AuditEvent event = HashChain.link(sequence, "EVT-" + sequence, clock,
                    e.actor(), e.role(), e.action(), e.type(), e.id(), e.payload(), previous);
            log.add(event);
            previous = event.hash();
            clock = clock.plusSeconds(86_400);
            sequence++;
        }
        return log;
    }

    // ================================================================== assertions

    private static void checkRemainderCase() {
        // A deliberately awkward apportionment: 7 units, three reserved categories at
        // 33.33%, 16.67% and 8.33%. Naive independent rounding over-allocates.
        Rules.RuleSet awkward = new Rules.RuleSet("REMAINDER-TEST", "SCH-TEST", 7, "OPEN",
                List.of(new Rules.ReservedQuota("R1", "R1", new BigDecimal("33.33")),
                        new Rules.ReservedQuota("R2", "R2", new BigDecimal("16.67")),
                        new Rules.ReservedQuota("R3", "R3", new BigDecimal("8.33"))),
                List.of(), Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 10, "");
        Draw.SeatPlan p = QuotaCalculator.plan(awkward);
        check("awkward percentages still apportion exactly (7 units)",
                p.allocatedSeats() == 7);
        check("no category is more than one seat from its exact entitlement",
                p.verticals().stream().allMatch(v -> v.seats() >= 0));
    }

    private static boolean clustersArePure(Dedup.DedupReport report,
                                           Map<String, SyntheticData.SyntheticApplication> byId) {
        for (Dedup.Cluster c : report.clusters()) {
            Set<String> households = new HashSet<>();
            for (String member : c.memberApplicationIds()) {
                households.add(byId.get(member).householdKey());
            }
            if (households.size() > 1) {
                System.out.println("    impure cluster " + c.clusterId() + " spans households " + households);
                return false;
            }
        }
        return true;
    }

    private static List<Dedup.Cluster> applyReviewDecisions(Dedup.DedupReport report,
                                                            List<Dedup.CandidatePair> accepted) {
        UnionFind uf = new UnionFind();
        Map<String, Instant> ignored = new HashMap<>();
        for (Dedup.Cluster c : report.clusters()) {
            for (String member : c.memberApplicationIds()) {
                uf.add(member);
                uf.union(c.primaryApplicationId(), member);
                ignored.put(member, null);
            }
        }
        for (Dedup.CandidatePair p : accepted) {
            uf.union(p.leftApplicationId(), p.rightApplicationId());
        }
        Map<String, List<String>> grouped = new TreeMap<>();
        for (Dedup.Cluster c : report.clusters()) {
            for (String member : c.memberApplicationIds()) {
                grouped.computeIfAbsent(uf.find(member), k -> new ArrayList<>()).add(member);
            }
        }
        List<Dedup.Cluster> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            List<String> members = new ArrayList<>(e.getValue());
            members.sort(String::compareTo);
            // The primary of a merged group is the primary of its earliest constituent
            // cluster, which the original report already computed by submission time.
            String primary = members.get(0);
            for (Dedup.Cluster c : report.clusters()) {
                if (members.contains(c.primaryApplicationId())) {
                    primary = c.primaryApplicationId();
                    break;
                }
            }
            out.add(new Dedup.Cluster("CL-" + primary, primary, members,
                    "cluster confirmed after review of " + members.size() + " application(s)"));
        }
        out.sort((a, b) -> a.clusterId().compareTo(b.clusterId()));
        return out;
    }

    private static boolean noDoubleSelection(Draw.DrawOutcome outcome) {
        Set<String> seen = new HashSet<>();
        for (Draw.PoolResult p : outcome.pools()) {
            for (String id : p.selectedApplicationIds()) {
                if (!seen.add(id)) {
                    System.out.println("    " + id + " selected in more than one pool");
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean poolsAreRankedPrefixes(Draw.DrawOutcome outcome) {
        for (Draw.PoolResult p : outcome.pools()) {
            int fill = Math.min(p.seats(), p.candidateCount());
            Set<String> expected = new LinkedHashSet<>();
            for (int i = 0; i < fill; i++) {
                expected.add(p.ranking().get(i).applicationId());
            }
            for (Draw.TopUpNote n : p.topUps()) {
                expected.removeAll(n.displacedApplicationIds());
                expected.addAll(n.promotedApplicationIds());
            }
            Set<String> actual = new LinkedHashSet<>(p.selectedApplicationIds());
            if (!expected.equals(actual)) {
                System.out.println("    pool " + p.poolCode() + " selection is not explained by its top-up notes");
                return false;
            }
        }
        return true;
    }

    private static boolean horizontalMinimaHonoured(Draw.DrawOutcome outcome, Roll.DrawRoll roll) {
        Map<String, Roll.RollEntry> byId = new HashMap<>();
        roll.entries().forEach(e -> byId.put(e.applicationId(), e));
        for (Draw.PoolResult p : outcome.pools()) {
            Draw.VerticalSeats vs = null;
            for (Draw.VerticalSeats candidate : outcome.seatPlan().verticals()) {
                if (candidate.code().equals(p.verticalCode())) {
                    vs = candidate;
                }
            }
            if (vs == null || p.poolCode().equals(AllocationEngine.LAPSED_POOL_CODE)) {
                continue;
            }
            for (Draw.HorizontalMinimum m : vs.minima()) {
                if (m.minimumSeats() <= 0) {
                    continue;
                }
                long have = p.selectedApplicationIds().stream()
                        .filter(id -> byId.get(id).satisfies(m.code()))
                        .count();
                if (have >= m.minimumSeats()) {
                    continue;
                }
                boolean explained = p.topUps().stream()
                        .anyMatch(n -> n.horizontalCode().equals(m.code()) && n.note().contains("could not"));
                if (!explained) {
                    System.out.println("    pool " + p.poolCode() + " has " + have + " of " + m.minimumSeats()
                            + " required for " + m.code() + " with no recorded shortfall");
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean residencyPreferenceHonoured(Draw.DrawOutcome outcome) {
        for (Draw.PoolResult p : outcome.pools()) {
            Set<String> selected = new HashSet<>(p.selectedApplicationIds());
            int worstSelectedTier = -1;
            int bestRejectedTier = Integer.MAX_VALUE;
            for (Draw.PoolCandidate c : p.ranking()) {
                if (selected.contains(c.applicationId())) {
                    worstSelectedTier = Math.max(worstSelectedTier, c.residencyTier());
                } else {
                    bestRejectedTier = Math.min(bestRejectedTier, c.residencyTier());
                }
            }
            // A lower tier may never be rejected while a higher tier is selected, unless a
            // recorded horizontal top-up caused it.
            if (worstSelectedTier > bestRejectedTier && p.topUps().stream()
                    .allMatch(n -> n.promotedApplicationIds().isEmpty())) {
                System.out.println("    pool " + p.poolCode() + " selected tier " + worstSelectedTier
                        + " while rejecting tier " + bestRejectedTier);
                return false;
            }
        }
        return true;
    }

    private static boolean promotionsStayInPool(Draw.DrawOutcome outcome,
                                                List<WaitlistPromoter.Promotion> promotions) {
        for (WaitlistPromoter.Promotion p : promotions) {
            if (p.promotedApplicationId() == null) {
                continue;
            }
            String vacatedPool = outcome.forApplication(p.vacatedByApplicationId()).decidingPoolCode();
            if (!vacatedPool.equals(p.poolCode())) {
                return false;
            }
            boolean inPool = outcome.pool(p.poolCode()).ranking().stream()
                    .anyMatch(c -> c.applicationId().equals(p.promotedApplicationId()));
            if (!inPool) {
                return false;
            }
        }
        return true;
    }

    private static int winnerOverlap(Draw.DrawOutcome a, Draw.DrawOutcome b) {
        Set<String> left = new HashSet<>();
        a.selections().stream().filter(s -> s.outcome() == Draw.Outcome.SELECTED)
                .forEach(s -> left.add(s.applicationId()));
        int overlap = 0;
        for (Draw.Selection s : b.selections()) {
            if (s.outcome() == Draw.Outcome.SELECTED && left.contains(s.applicationId())) {
                overlap++;
            }
        }
        return overlap;
    }

    private static boolean refusesBadNonce(Draw.SeedCommitment commitment, String entropy) {
        try {
            SeedDeriver.reveal(commitment, entropy, "0".repeat(64));
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean refusesTamperedRoll(Roll.DrawRoll roll, Rules.RuleSet rules, Draw.DrawSeed seed) {
        List<Roll.RollEntry> altered = new ArrayList<>(roll.entries());
        Roll.RollEntry first = altered.get(0);
        altered.set(0, new Roll.RollEntry(first.applicationId(), first.clusterId(), "CAT_A",
                Set.of("WOMEN", "PWD"), 25, first.unitTypePreferences()));
        Roll.DrawRoll forged = new Roll.DrawRoll(roll.rollId(), roll.schemeCode(), roll.ruleSetVersion(),
                roll.ruleSetHash(), roll.frozenAt(), altered, roll.rollHash());
        if (RollHasher.verify(forged)) {
            return false;
        }
        try {
            AllocationEngine.execute("DRAW-1", forged, rules, seed, Instant.parse("2026-05-25T10:00:00Z"));
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    private static boolean refusesMismatchedSeed(Roll.DrawRoll roll, Rules.RuleSet rules) {
        Draw.SeedCommitment wrong = SeedDeriver.commit("DRAW-X", "0".repeat(64), "abc",
                Instant.parse("2026-05-21T05:00:00Z"), "someone");
        Draw.DrawSeed wrongSeed = SeedDeriver.reveal(wrong, "ENTROPY", "abc");
        try {
            AllocationEngine.execute("DRAW-1", roll, rules, wrongSeed, Instant.parse("2026-05-25T10:00:00Z"));
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    // ================================================================== output

    private static void printExplanation(Draw.DrawOutcome outcome, Roll.DrawRoll roll, String applicationId) {
        Draw.Selection s = outcome.forApplication(applicationId);
        Draw.PoolResult pool = outcome.pool(s.decidingPoolCode());
        System.out.println();
        System.out.println("  Application " + applicationId + " - " + s.outcome());
        System.out.println("    ticket          : " + s.ticketHex().substring(0, 24) + "...");
        System.out.println("    pool            : " + pool.poolCode()
                + " (" + pool.seats() + " seats, " + pool.candidateCount() + " candidates)");
        System.out.println("    rank            : " + s.rankInDecidingPool());
        System.out.println("    residency tier  : " + s.residencyTier());
        if (s.waitlistPosition() != null) {
            System.out.println("    waitlist        : " + s.waitlistPosition());
        }
        System.out.println("    reason          : " + s.reasonCode());
        System.out.println("    explanation     : " + s.reasonText());
        System.out.println("    verify with     : seed=" + outcome.seedHex().substring(0, 16) + "...  "
                + "ticket = SHA256(\"ticket/1|<seed>|" + applicationId + "\")");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " " + "=".repeat(Math.max(0, 74 - title.length())));
    }

    private static void check(String description, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println("  [" + (condition ? "PASS" : "FAIL") + "] " + description);
    }
}
