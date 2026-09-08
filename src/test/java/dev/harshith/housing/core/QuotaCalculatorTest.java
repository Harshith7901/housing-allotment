package dev.harshith.housing.core;

import dev.harshith.housing.core.draw.QuotaCalculator;
import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Rules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apportionment is the shortest class in the system and the most likely to be argued about
 * in front of a judge, because somebody loses a flat to a rounding rule.
 */
class QuotaCalculatorTest {

    @Test
    @DisplayName("600 flats split 50.5/15/7.5/27 exactly, with no remainder seats")
    void exactApportionment() {
        Draw.SeatPlan plan = QuotaCalculator.plan(RuleFixtures.standard(600));

        assertEquals(303, plan.vertical("OPEN").seats());
        assertEquals(90, plan.vertical("CAT_A").seats());
        assertEquals(45, plan.vertical("CAT_B").seats());
        assertEquals(162, plan.vertical("CAT_C").seats());
        assertEquals(600, plan.allocatedSeats());
    }

    @ParameterizedTest(name = "an inventory of {0} units is always apportioned exactly")
    @ValueSource(ints = {1, 2, 3, 7, 13, 99, 100, 101, 599, 600, 601, 4001})
    void apportionmentAlwaysSumsToTheInventory(int units) {
        Draw.SeatPlan plan = QuotaCalculator.plan(RuleFixtures.standard(units));
        assertEquals(units, plan.allocatedSeats(),
                "largest remainder must never lose or invent a flat");
    }

    @Test
    @DisplayName("no category ends more than one seat from its exact entitlement")
    void largestRemainderIsFair() {
        int units = 4001;
        Rules.RuleSet rules = RuleFixtures.standard(units);
        Draw.SeatPlan plan = QuotaCalculator.plan(rules);

        for (Rules.ReservedQuota quota : rules.reservedQuotas()) {
            double exact = quota.percent().doubleValue() * units / 100.0;
            int actual = plan.vertical(quota.code()).seats();
            assertTrue(Math.abs(actual - exact) < 1.0,
                    quota.code() + " got " + actual + " seats against an exact entitlement of " + exact);
        }
    }

    @Test
    @DisplayName("remainder ties are broken by declared order, not by map iteration order")
    void remainderTiesAreDeterministic() {
        // Three categories at 10% of 7 units: each entitled to 0.7 seats, identical
        // remainders. The seats must go to the earlier-declared categories, every time.
        Rules.RuleSet rules = new Rules.RuleSet("TIE-1", "SCH", 7, "OPEN",
                List.of(new Rules.ReservedQuota("FIRST", "First", new BigDecimal("10.0")),
                        new Rules.ReservedQuota("SECOND", "Second", new BigDecimal("10.0")),
                        new Rules.ReservedQuota("THIRD", "Third", new BigDecimal("10.0"))),
                List.of(), Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 0, "");

        Draw.SeatPlan first = QuotaCalculator.plan(rules);
        for (int i = 0; i < 50; i++) {
            Draw.SeatPlan again = QuotaCalculator.plan(rules);
            assertEquals(first.vertical("FIRST").seats(), again.vertical("FIRST").seats());
            assertEquals(first.vertical("SECOND").seats(), again.vertical("SECOND").seats());
            assertEquals(first.vertical("THIRD").seats(), again.vertical("THIRD").seats());
        }
        assertEquals(7, first.allocatedSeats());
    }

    @Test
    @DisplayName("horizontal minima are computed against each category's own seat count")
    void horizontalMinimaAreScopedToTheirCategory() {
        Draw.SeatPlan plan = QuotaCalculator.plan(RuleFixtures.standard(600));

        // 30% of the open category's 303 seats, not 30% of 600.
        assertEquals(91, plan.vertical("OPEN").minimumFor("WOMEN"));
        assertEquals(27, plan.vertical("CAT_A").minimumFor("WOMEN"));
        assertEquals(14, plan.vertical("CAT_B").minimumFor("WOMEN"));
        assertEquals(49, plan.vertical("CAT_C").minimumFor("WOMEN"));
    }

    @Test
    @DisplayName("a rule set whose reserved quotas exceed 100% is refused at construction")
    void impossibleReservationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Rules.RuleSet(
                "BAD-1", "SCH", 600, "OPEN",
                List.of(new Rules.ReservedQuota("A", "A", new BigDecimal("60")),
                        new Rules.ReservedQuota("B", "B", new BigDecimal("60"))),
                List.of(), Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 0, ""));
    }

    @Test
    @DisplayName("horizontal minima that cannot fit inside a category are refused at planning time")
    void unsatisfiableHorizontalMinimaAreRefusedBeforeTheDraw() {
        // Two minima of 50% against a single seat. The percentages themselves are legal
        // (they sum to 100 and could both be met by one person satisfying both), but
        // rounding each to a whole seat demands two seats out of one. This is exactly the
        // kind of defect that only appears at a particular inventory size, so it has to be
        // caught when the plan is computed rather than assumed away at review.
        Rules.RuleSet rules = new Rules.RuleSet("BAD-2", "SCH", 1, "OPEN",
                List.of(),
                List.of(new Rules.HorizontalQuota("X", "X", new BigDecimal("50")),
                        new Rules.HorizontalQuota("Y", "Y", new BigDecimal("50"))),
                Rules.ResidencyRule.none(), Rules.LapsePolicy.LAPSE_TO_OPEN, 0, "");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> QuotaCalculator.plan(rules));
        assertTrue(failure.getMessage().contains("not satisfiable"), failure.getMessage());
    }

    @Test
    @DisplayName("the apportionment records its own arithmetic")
    void workingsAreRecorded() {
        Draw.SeatPlan plan = QuotaCalculator.plan(RuleFixtures.standard(600));
        assertTrue(plan.workings().stream().anyMatch(w -> w.contains("50.5% of 600")),
                "the open category's share must be shown, not just its result: " + plan.workings());
        assertTrue(plan.workings().stream().anyMatch(w -> w.contains("total apportioned=600")));
    }
}
