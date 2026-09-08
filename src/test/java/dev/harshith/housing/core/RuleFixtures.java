package dev.harshith.housing.core;

import dev.harshith.housing.core.model.Rules;

import java.math.BigDecimal;
import java.util.List;

/** Shared rule sets for the core tests. */
public final class RuleFixtures {

    public static final String SCHEME = "SCH-TEST";

    private RuleFixtures() {
    }

    /** The scheme in the brief: 600 flats, three reserved categories, four horizontal minima. */
    public static Rules.RuleSet standard(int totalUnits) {
        return new Rules.RuleSet(
                "TEST-1", SCHEME, totalUnits, "OPEN",
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
                "https://example.gov/rules.pdf");
    }

    /** No reservations, no horizontal minima, no residency preference: a plain lottery. */
    public static Rules.RuleSet plain(int totalUnits) {
        return new Rules.RuleSet("PLAIN-1", SCHEME, totalUnits, "OPEN",
                List.of(), List.of(), Rules.ResidencyRule.none(),
                Rules.LapsePolicy.LAPSE_TO_OPEN, 10, "");
    }

    /**
     * Residency preference and nothing else. Isolates the tier rule from the horizontal
     * minima, which are the one thing allowed to override it — so the residency test can
     * assert the strict property instead of skipping every pool that had a top-up.
     */
    public static Rules.RuleSet residencyOnly(int totalUnits, int minYears) {
        return new Rules.RuleSet("RESIDENCY-1", SCHEME, totalUnits, "OPEN",
                List.of(), List.of(),
                new Rules.ResidencyRule(Rules.ResidencyMode.PRIORITY_TIER, minYears),
                Rules.LapsePolicy.LAPSE_TO_OPEN, 50, "");
    }
}
