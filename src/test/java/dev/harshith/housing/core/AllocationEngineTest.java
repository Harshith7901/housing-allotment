package dev.harshith.housing.core;

import dev.harshith.housing.core.draw.AllocationEngine;
import dev.harshith.housing.core.draw.ResultHasher;
import dev.harshith.housing.core.draw.RollHasher;
import dev.harshith.housing.core.draw.SeedDeriver;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;
import dev.harshith.housing.core.sim.SyntheticData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the draw itself.
 *
 * <p>These are written as invariants rather than as expected outputs. Asserting that
 * application 41 wins would be a test of this seed and nothing else; asserting that a
 * reserved-category applicant who wins on open merit does not consume their category's
 * quota is a test of the rule that matters.
 */
class AllocationEngineTest {

    private static final Instant FROZEN_AT = Instant.parse("2026-05-20T09:30:00Z");
    private static final Instant DRAWN_AT = Instant.parse("2026-05-25T10:00:00Z");
    private static final String NONCE = "a".repeat(64);
    private static final String ENTROPY = "STATE-LOTTERY-2026-05-25:481902";

    // ------------------------------------------------------------------ reproducibility

    @Test
    @DisplayName("the same roll, rules and seed always produce a bit-identical result")
    void drawIsDeterministic() {
        Fixture f = realisticFixture(600);

        Draw.DrawOutcome first = f.run();
        for (int i = 0; i < 5; i++) {
            Draw.DrawOutcome again = f.run();
            assertEquals(first.resultHash(), again.resultHash());
            assertEquals(ResultHasher.encode(first), ResultHasher.encode(again),
                    "the canonical result bytes must be identical, not merely equivalent");
        }
    }

    @Test
    @DisplayName("changing only the public entropy value changes the winners")
    void differentEntropyProducesADifferentResult() {
        Fixture f = realisticFixture(600);
        Draw.DrawOutcome first = f.run();
        Draw.DrawOutcome second = f.runWithEntropy("STATE-LOTTERY-2026-05-25:481903");

        assertNotEquals(first.resultHash(), second.resultHash());

        Set<String> before = winners(first);
        Set<String> after = winners(second);
        Set<String> overlap = new HashSet<>(before);
        overlap.retainAll(after);
        // With 600 seats among ~3,400 applicants the expected overlap is roughly 600 x
        // 600/3400 ~= 106 plus the residency-tier structure; a near-total overlap would
        // mean the seed barely influences the outcome.
        assertTrue(overlap.size() < before.size() * 0.75,
                "the seed must materially determine the winners; overlap was " + overlap.size());
    }

    @Test
    @DisplayName("the result verifies against its own hash")
    void resultIsSelfVerifying() {
        assertTrue(ResultHasher.verify(realisticFixture(600).run()));
    }

    // ------------------------------------------------------------------ integrity gates

    @Test
    @DisplayName("a roll altered after freezing is refused")
    void tamperedRollIsRefused() {
        Fixture f = realisticFixture(600);

        List<Roll.RollEntry> altered = new ArrayList<>(f.roll.entries());
        Roll.RollEntry victim = altered.get(0);
        altered.set(0, new Roll.RollEntry(victim.applicationId(), victim.clusterId(),
                "CAT_A", Set.of("WOMEN", "PWD"), 30, victim.unitTypePreferences()));

        Roll.DrawRoll forged = new Roll.DrawRoll(f.roll.rollId(), f.roll.schemeCode(),
                f.roll.ruleSetVersion(), f.roll.ruleSetHash(), f.roll.frozenAt(), altered,
                f.roll.rollHash());

        assertTrue(!RollHasher.verify(forged), "the forged roll must not verify");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AllocationEngine.execute("DRAW-1", forged, f.rules, f.seed(ENTROPY), DRAWN_AT));
        assertTrue(failure.getMessage().contains("altered since freezing"), failure.getMessage());
    }

    @Test
    @DisplayName("a seed committed against a different roll is refused")
    void mismatchedSeedIsRefused() {
        Fixture f = realisticFixture(600);
        Draw.SeedCommitment elsewhere = SeedDeriver.commit("OTHER", "0".repeat(64), NONCE,
                FROZEN_AT, "someone");
        Draw.DrawSeed wrongSeed = SeedDeriver.reveal(elsewhere, ENTROPY, NONCE);

        assertThrows(IllegalStateException.class,
                () -> AllocationEngine.execute("DRAW-1", f.roll, f.rules, wrongSeed, DRAWN_AT));
    }

    @Test
    @DisplayName("a roll entry claiming an undefined category is refused")
    void unknownCategoryIsRefused() {
        Rules.RuleSet rules = RuleFixtures.standard(10);
        Roll.DrawRoll roll = RollHasher.freeze("R", RuleFixtures.SCHEME, rules.version(), "hash",
                FROZEN_AT, List.of(new Roll.RollEntry("APP-1", "CL-1", "CAT_INVENTED",
                        Set.of(), 5, List.of())));
        Draw.DrawSeed seed = seedFor(roll);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AllocationEngine.execute("DRAW-1", roll, rules, seed, DRAWN_AT));
        assertTrue(failure.getMessage().contains("CAT_INVENTED"), failure.getMessage());
    }

    // ------------------------------------------------------------------ the rules

    @Test
    @DisplayName("every seat is filled and nobody wins twice")
    void everySeatIsFilledExactlyOnce() {
        Draw.DrawOutcome outcome = realisticFixture(600).run();

        assertEquals(600, outcome.selectedCount());

        Set<String> seen = new LinkedHashSet<>();
        for (Draw.PoolResult pool : outcome.pools()) {
            for (String id : pool.selectedApplicationIds()) {
                assertTrue(seen.add(id), id + " was selected in more than one pool");
            }
        }
        assertEquals(600, seen.size());
    }

    @Test
    @DisplayName("every applicant on the roll gets an outcome and a reason, not just the winners")
    void everyApplicantIsAccountedFor() {
        Fixture f = realisticFixture(600);
        Draw.DrawOutcome outcome = f.run();

        assertEquals(f.roll.entryCount(), outcome.selections().size());
        for (Draw.Selection s : outcome.selections()) {
            assertTrue(s.reasonCode() != null, s.applicationId() + " has no reason code");
            assertTrue(s.reasonText() != null && !s.reasonText().isBlank(),
                    s.applicationId() + " has no human-readable explanation");
            assertTrue(s.ticketHex().length() == 64, "every applicant must have a full ticket");
        }
    }

    @Test
    @DisplayName("a reserved-category applicant who wins on open merit does not consume the quota")
    void meritoriousReservedDoesNotConsumeTheQuota() {
        Fixture f = realisticFixture(600);
        Draw.DrawOutcome outcome = f.run();

        Map<String, String> categoryOf = new HashMap<>();
        f.roll.entries().forEach(e -> categoryOf.put(e.applicationId(), e.verticalCode()));

        // Each reserved pool must fill its full seat count with that category's own
        // applicants, even though some of that category also won open seats.
        for (Rules.ReservedQuota quota : f.rules.reservedQuotas()) {
            Draw.PoolResult pool = outcome.pool(quota.code());
            assertEquals(pool.seats(), pool.selectedApplicationIds().size(),
                    "reserved pool " + quota.code() + " must be filled to its quota");
            for (String id : pool.selectedApplicationIds()) {
                assertEquals(quota.code(), categoryOf.get(id),
                        "a reserved seat may only go to an applicant of that category");
            }
        }

        // And the open pool must contain reserved-category winners, otherwise the open
        // pool is not genuinely open.
        long reservedWinnersInOpenPool = outcome.pool("OPEN").selectedApplicationIds().stream()
                .filter(id -> !"OPEN".equals(categoryOf.get(id)))
                .count();
        assertTrue(reservedWinnersInOpenPool > 0,
                "reserved-category applicants must be able to win open seats on merit");
    }

    @Test
    @DisplayName("horizontal minima are met, and every promotion and displacement is named")
    void horizontalMinimaAreHonouredAndRecorded() {
        Fixture f = realisticFixture(600);
        Draw.DrawOutcome outcome = f.run();

        Map<String, Roll.RollEntry> byId = new HashMap<>();
        f.roll.entries().forEach(e -> byId.put(e.applicationId(), e));

        for (Draw.PoolResult pool : outcome.pools()) {
            if (pool.poolCode().equals(AllocationEngine.LAPSED_POOL_CODE)) {
                continue;
            }
            Draw.VerticalSeats plan = outcome.seatPlan().vertical(pool.verticalCode());
            for (Draw.HorizontalMinimum minimum : plan.minima()) {
                if (minimum.minimumSeats() == 0) {
                    continue;
                }
                long have = pool.selectedApplicationIds().stream()
                        .filter(id -> byId.get(id).satisfies(minimum.code()))
                        .count();
                boolean shortfallRecorded = pool.topUps().stream()
                        .anyMatch(n -> n.horizontalCode().equals(minimum.code())
                                && n.note().contains("could not"));
                assertTrue(have >= minimum.minimumSeats() || shortfallRecorded,
                        "pool " + pool.poolCode() + " has " + have + " of " + minimum.minimumSeats()
                                + " for " + minimum.code() + " with no recorded shortfall");
            }
        }

        // A displaced applicant must be findable, by name, in the result.
        List<String> displaced = new ArrayList<>();
        outcome.pools().forEach(p -> p.topUps()
                .forEach(n -> displaced.addAll(n.displacedApplicationIds())));
        for (String id : displaced) {
            Draw.Selection selection = outcome.forApplication(id);
            assertEquals(Draw.ReasonCode.DISPLACED_BY_HORIZONTAL_MINIMUM, selection.reasonCode());
            assertTrue(selection.waitlistPosition() != null,
                    "a displaced applicant belongs on the waitlist, not merely rejected");
        }
    }

    @Test
    @DisplayName("each pool's winners are its ranked prefix, adjusted only by recorded top-ups")
    void selectionIsExplainedEntirelyByRankAndRecordedTopUps() {
        Draw.DrawOutcome outcome = realisticFixture(600).run();

        for (Draw.PoolResult pool : outcome.pools()) {
            int fill = Math.min(pool.seats(), pool.candidateCount());
            Set<String> expected = new LinkedHashSet<>();
            for (int i = 0; i < fill; i++) {
                expected.add(pool.ranking().get(i).applicationId());
            }
            pool.topUps().forEach(n -> {
                expected.removeAll(n.displacedApplicationIds());
                expected.addAll(n.promotedApplicationIds());
            });
            assertEquals(expected, new LinkedHashSet<>(pool.selectedApplicationIds()),
                    "pool " + pool.poolCode() + " selected somebody its own record cannot explain");
        }
    }

    @Test
    @DisplayName("a qualifying local is never rejected while a non-local is selected")
    void residencyPreferenceIsHonoured() {
        // Residency in isolation. Horizontal minima are the one published rule allowed to
        // override the tier, and they are tested separately, so this fixture removes them
        // in order to assert the strict property rather than skipping pools.
        Fixture f = fixture(RuleFixtures.residencyOnly(200, 3), 2_000);
        Draw.DrawOutcome outcome = f.run();

        Draw.PoolResult pool = outcome.pool("OPEN");
        assertTrue(pool.topUps().isEmpty());

        Set<String> selected = new HashSet<>(pool.selectedApplicationIds());
        int worstSelected = -1;
        int bestRejected = Integer.MAX_VALUE;
        for (Draw.PoolCandidate c : pool.ranking()) {
            if (selected.contains(c.applicationId())) {
                worstSelected = Math.max(worstSelected, c.residencyTier());
            } else {
                bestRejected = Math.min(bestRejected, c.residencyTier());
            }
        }
        assertTrue(worstSelected <= bestRejected,
                "selected tier " + worstSelected + " while rejecting tier " + bestRejected);

        // And the ranking really is tier-major: every tier 0 precedes every tier 1.
        int lastTier = 0;
        for (Draw.PoolCandidate c : pool.ranking()) {
            assertTrue(c.residencyTier() >= lastTier, "the ranking is not ordered by tier");
            lastTier = c.residencyTier();
        }

        Map<String, Integer> years = new HashMap<>();
        f.roll.entries().forEach(e -> years.put(e.applicationId(), e.residencyYears()));
        assertTrue(pool.ranking().stream().anyMatch(c -> c.residencyTier() == 1),
                "the fixture must contain non-locals for this test to mean anything");
        for (Draw.PoolCandidate c : pool.ranking()) {
            int expected = years.get(c.applicationId()) >= 3 ? 0 : 1;
            assertEquals(expected, c.residencyTier(), c.applicationId() + " is in the wrong tier");
        }
    }

    @Test
    @DisplayName("with no reservations at all, the draw is the ranked prefix of the whole roll")
    void plainLotteryIsJustTheTicketOrder() {
        Fixture f = fixture(RuleFixtures.plain(50), 400);
        Draw.DrawOutcome outcome = f.run();

        Draw.PoolResult pool = outcome.pool("OPEN");
        assertEquals(50, pool.selectedApplicationIds().size());
        assertTrue(pool.topUps().isEmpty(), "there are no horizontal minima to enforce");

        for (int i = 0; i < 50; i++) {
            assertEquals(pool.ranking().get(i).applicationId(), pool.selectedApplicationIds().get(i));
        }
        // And the ranking really is sorted by ticket value.
        for (int i = 1; i < pool.ranking().size(); i++) {
            assertTrue(pool.ranking().get(i - 1).ticketHex()
                            .compareTo(pool.ranking().get(i).ticketHex()) < 0,
                    "the ranking must be strictly ordered by ticket");
        }
    }

    @Test
    @DisplayName("unclaimed reserved seats are re-offered in a clearly labelled open pool")
    void unfilledReservedSeatsLapseVisibly() {
        // Ten flats, a 50% reserved category, and only one applicant in it: four of its
        // five seats cannot be filled from that category.
        Rules.RuleSet rules = new Rules.RuleSet("LAPSE-1", RuleFixtures.SCHEME, 10, "OPEN",
                List.of(new Rules.ReservedQuota("RARE", "Rare category", new BigDecimal("50"))),
                List.of(), Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 5, "");

        List<Roll.RollEntry> entries = new ArrayList<>();
        entries.add(new Roll.RollEntry("APP-0001", "CL-1", "RARE", Set.of(), 0, List.of()));
        for (int i = 2; i <= 40; i++) {
            entries.add(new Roll.RollEntry(String.format("APP-%04d", i), "CL-" + i, "OPEN",
                    Set.of(), 0, List.of()));
        }
        Roll.DrawRoll roll = RollHasher.freeze("R", RuleFixtures.SCHEME, rules.version(),
                "rulehash", FROZEN_AT, entries);

        Draw.DrawOutcome outcome =
                AllocationEngine.execute("DRAW-1", roll, rules, seedFor(roll), DRAWN_AT);

        int unfilled = outcome.pool("RARE").unfilledSeats();
        assertTrue(unfilled >= 4,
                "at most one of the five reserved seats can be filled from a category of one");
        Draw.PoolResult lapsed = outcome.pool(AllocationEngine.LAPSED_POOL_CODE);
        assertEquals(unfilled, lapsed.seats(), "every lapsed seat must be re-offered");
        assertEquals(10, outcome.selectedCount(), "all ten flats must still be allotted");
        for (String id : lapsed.selectedApplicationIds()) {
            assertEquals(Draw.ReasonCode.SELECTED_IN_LAPSED_OPEN_POOL,
                    outcome.forApplication(id).reasonCode(),
                    "a lapsed seat must be labelled as such, not passed off as an open win");
        }
    }

    @Test
    @DisplayName("carry-forward keeps unclaimed reserved seats out of this draw entirely")
    void carryForwardWithholdsUnclaimedSeats() {
        Rules.RuleSet rules = new Rules.RuleSet("CARRY-1", RuleFixtures.SCHEME, 10, "OPEN",
                List.of(new Rules.ReservedQuota("RARE", "Rare category", new BigDecimal("50"))),
                List.of(), Rules.ResidencyRule.none(), Rules.LapsePolicy.CARRY_FORWARD, 5, "");

        List<Roll.RollEntry> entries = new ArrayList<>();
        entries.add(new Roll.RollEntry("APP-0001", "CL-1", "RARE", Set.of(), 0, List.of()));
        for (int i = 2; i <= 40; i++) {
            entries.add(new Roll.RollEntry(String.format("APP-%04d", i), "CL-" + i, "OPEN",
                    Set.of(), 0, List.of()));
        }
        Roll.DrawRoll roll = RollHasher.freeze("R", RuleFixtures.SCHEME, rules.version(),
                "rulehash", FROZEN_AT, entries);

        Draw.DrawOutcome outcome =
                AllocationEngine.execute("DRAW-1", roll, rules, seedFor(roll), DRAWN_AT);

        int unfilled = outcome.pool("RARE").unfilledSeats();
        assertTrue(unfilled >= 4);
        assertEquals(10 - unfilled, outcome.selectedCount(),
                "unclaimed reserved seats are withheld from this draw, not re-offered");
        assertThrows(IllegalArgumentException.class,
                () -> outcome.pool(AllocationEngine.LAPSED_POOL_CODE),
                "carry-forward must not create a supplementary pool");
    }

    @Test
    @DisplayName("more seats than applicants leaves seats unfilled rather than inventing winners")
    void moreSeatsThanApplicants() {
        Fixture f = fixture(RuleFixtures.plain(100), 20);
        Draw.DrawOutcome outcome = f.run();

        assertEquals(20, outcome.selectedCount());
        assertEquals(80, outcome.pool("OPEN").unfilledSeats());
    }

    // ------------------------------------------------------------------ fixtures

    private record Fixture(Rules.RuleSet rules, Roll.DrawRoll roll) {

        Draw.SeedCommitment commitment() {
            return SeedDeriver.commit("DRAW-1", roll.rollHash(), NONCE, FROZEN_AT, "registrar");
        }

        Draw.DrawSeed seed(String entropy) {
            return SeedDeriver.reveal(commitment(), entropy, NONCE);
        }

        Draw.DrawOutcome run() {
            return runWithEntropy(ENTROPY);
        }

        Draw.DrawOutcome runWithEntropy(String entropy) {
            return AllocationEngine.execute("DRAW-1", roll, rules, seed(entropy), DRAWN_AT);
        }
    }

    private static Draw.DrawSeed seedFor(Roll.DrawRoll roll) {
        return SeedDeriver.reveal(
                SeedDeriver.commit("DRAW-1", roll.rollHash(), NONCE, FROZEN_AT, "registrar"),
                ENTROPY, NONCE);
    }

    /** The brief's population: about 4,000 applications, deduplicated, for {@code units} flats. */
    private static Fixture realisticFixture(int units) {
        return fixture(RuleFixtures.standard(units), 3_400);
    }

    private static Fixture fixture(Rules.RuleSet rules, int households) {
        SyntheticData.Dataset data = SyntheticData.generate(20260401L, households, 0, rules.totalUnits());
        List<Roll.RollEntry> entries = new ArrayList<>();
        for (SyntheticData.SyntheticApplication app : data.applications()) {
            String vertical = rules.knowsVertical(app.verticalCode()) ? app.verticalCode() : rules.openCode();
            Set<String> horizontal = new LinkedHashSet<>();
            app.horizontalCodes().stream().filter(rules::knowsHorizontal).forEach(horizontal::add);
            entries.add(new Roll.RollEntry(app.identity().applicationId(),
                    "CL-" + app.identity().applicationId(), vertical, horizontal,
                    app.residencyYears(), app.unitTypePreferences()));
        }
        Roll.DrawRoll roll = RollHasher.freeze("ROLL-1", RuleFixtures.SCHEME, rules.version(),
                "rule-set-hash", FROZEN_AT, entries);
        return new Fixture(rules, roll);
    }

    private static Set<String> winners(Draw.DrawOutcome outcome) {
        Set<String> out = new LinkedHashSet<>();
        outcome.selections().stream()
                .filter(s -> s.outcome() == Draw.Outcome.SELECTED)
                .forEach(s -> out.add(s.applicationId()));
        return out;
    }
}
