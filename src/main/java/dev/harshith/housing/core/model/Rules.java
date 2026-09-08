package dev.harshith.housing.core.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The published allotment rules, as data.
 *
 * <p>The single most important design decision in this system is that the rules are a
 * <em>versioned, immutable data record</em> rather than Java code. Three reasons:
 *
 * <ol>
 *   <li>Every allotment stores the rule-set version it was decided under, so "you changed
 *       the rules half way through the draw" is answerable with a hash rather than a
 *       git blame.</li>
 *   <li>A rule set can be published, hashed, and printed in a gazette notification before
 *       a single application is accepted.</li>
 *   <li>Amending a quota is a data migration reviewed by the scheme authority, not a
 *       code deploy.</li>
 * </ol>
 *
 * <p>Types are grouped in this holder class deliberately: they are one cohesive value
 * cluster and splitting nine two-line records across nine files hides the structure.
 */
public final class Rules {

    /** Maximum decimal places allowed on a published percentage. */
    public static final int PERCENT_SCALE = 4;

    private Rules() {
    }

    /**
     * Rejects text that the canonical encoding cannot represent.
     *
     * <p>{@link RulesCodec} is line- and pipe-delimited, so a {@code |} in a category label
     * or a newline in a URI would encode fine and then fail to decode. Because rule sets
     * are immutable and the scheme points at the active version, that failure would arrive
     * on the <em>next read</em> and brick the scheme with no repair path. The delimiters are
     * therefore refused here, at construction, where the error is still a bad request.
     */
    static String requireEncodable(String field, String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('|') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a pipe or a line break, "
                    + "because the published rule set is a line- and pipe-delimited document: " + value);
        }
        return value;
    }

    /**
     * Rejects a percentage with more precision than the canonical encoding carries.
     *
     * <p>The encoder writes percentages at a fixed scale and refuses to round, so that two
     * semantically identical rule sets cannot hash differently. A value needing more than
     * four decimals has to be refused rather than silently rounded — quietly changing a
     * published quota is worse than rejecting it.
     */
    static BigDecimal requireEncodablePercent(String field, BigDecimal value) {
        if (value != null && value.stripTrailingZeros().scale() > PERCENT_SCALE) {
            throw new IllegalArgumentException(field + " may carry at most " + PERCENT_SCALE
                    + " decimal places, because that is the precision the published rule set records: "
                    + value.toPlainString());
        }
        return value;
    }

    /**
     * A vertical reservation: mutually exclusive categories, each applicant belongs to
     * exactly one. Percentages are of the total unit count.
     */
    public record ReservedQuota(String code, String label, BigDecimal percent) {
        public ReservedQuota {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("reserved quota code is required");
            }
            if (percent == null || percent.signum() < 0) {
                throw new IllegalArgumentException("reserved quota percent must be >= 0: " + code);
            }
            requireEncodable("reserved quota code", code);
            requireEncodable("reserved quota label", label);
            requireEncodablePercent("reserved quota percent for " + code, percent);
        }
    }

    /**
     * A horizontal quota: a <em>minimum guarantee</em> applied inside every vertical
     * category, not a separate pool. An applicant may satisfy several at once (a woman
     * with a disability counts towards both), which is precisely why these are minima
     * topped up after ranking rather than percentages carved out of the inventory.
     */
    public record HorizontalQuota(String code, String label, BigDecimal percent) {
        public HorizontalQuota {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("horizontal quota code is required");
            }
            if (percent == null || percent.signum() < 0) {
                throw new IllegalArgumentException("horizontal quota percent must be >= 0: " + code);
            }
            requireEncodable("horizontal quota code", code);
            requireEncodable("horizontal quota label", label);
            requireEncodablePercent("horizontal quota percent for " + code, percent);
        }
    }

    public enum ResidencyMode {
        /** Residency is recorded but does not affect ranking. */
        NONE,
        /**
         * Applicants resident in the scheme's area for at least {@code minYears} are
         * ranked as tier 0, everyone else as tier 1. Tier dominates the random ticket,
         * so every qualifying local outranks every non-local inside a pool.
         */
        PRIORITY_TIER
    }

    public record ResidencyRule(ResidencyMode mode, int minYears) {
        public ResidencyRule {
            if (mode == null) {
                throw new IllegalArgumentException("residency mode is required");
            }
            if (minYears < 0) {
                throw new IllegalArgumentException("residency minYears must be >= 0");
            }
        }

        public static ResidencyRule none() {
            return new ResidencyRule(ResidencyMode.NONE, 0);
        }
    }

    /** What happens to a reserved seat that no eligible applicant claimed. */
    public enum LapsePolicy {
        /** The seat is offered in a clearly labelled supplementary open pool in the same draw. */
        LAPSE_TO_OPEN,
        /** The seat is withheld and carried to the next draw of the same scheme. */
        CARRY_FORWARD
    }

    public record RuleSet(
            String version,
            String schemeCode,
            int totalUnits,
            String openCode,
            List<ReservedQuota> reservedQuotas,
            List<HorizontalQuota> horizontalQuotas,
            ResidencyRule residency,
            LapsePolicy lapsePolicy,
            int waitlistSize,
            String publishedRulesUri
    ) {
        public RuleSet {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("rule set version is required");
            }
            if (schemeCode == null || schemeCode.isBlank()) {
                throw new IllegalArgumentException("scheme code is required");
            }
            if (totalUnits <= 0) {
                throw new IllegalArgumentException("totalUnits must be positive");
            }
            if (openCode == null || openCode.isBlank()) {
                throw new IllegalArgumentException("openCode is required");
            }
            reservedQuotas = List.copyOf(reservedQuotas == null ? List.of() : reservedQuotas);
            horizontalQuotas = List.copyOf(horizontalQuotas == null ? List.of() : horizontalQuotas);
            if (residency == null) {
                residency = ResidencyRule.none();
            }
            if (lapsePolicy == null) {
                lapsePolicy = LapsePolicy.LAPSE_TO_OPEN;
            }
            if (waitlistSize < 0) {
                throw new IllegalArgumentException("waitlistSize must be >= 0");
            }
            if (publishedRulesUri == null) {
                publishedRulesUri = "";
            }
            requireEncodable("rule set version", version);
            requireEncodable("scheme code", schemeCode);
            requireEncodable("openCode", openCode);
            requireEncodable("publishedRulesUri", publishedRulesUri);
            validate(version, totalUnits, openCode, reservedQuotas, horizontalQuotas);
        }

        public List<String> verticalCodes() {
            List<String> codes = new java.util.ArrayList<>();
            codes.add(openCode);
            reservedQuotas.forEach(q -> codes.add(q.code()));
            return List.copyOf(codes);
        }

        public Optional<ReservedQuota> reserved(String code) {
            return reservedQuotas.stream().filter(q -> q.code().equals(code)).findFirst();
        }

        public boolean knowsVertical(String code) {
            return openCode.equals(code) || reserved(code).isPresent();
        }

        public boolean knowsHorizontal(String code) {
            return horizontalQuotas.stream().anyMatch(q -> q.code().equals(code));
        }

        private static void validate(String version,
                                     int totalUnits,
                                     String openCode,
                                     List<ReservedQuota> reserved,
                                     List<HorizontalQuota> horizontal) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            seen.add(openCode);
            BigDecimal reservedTotal = BigDecimal.ZERO;
            for (ReservedQuota q : reserved) {
                if (!seen.add(q.code())) {
                    throw new IllegalArgumentException("duplicate vertical code in rule set " + version + ": " + q.code());
                }
                reservedTotal = reservedTotal.add(q.percent());
            }
            if (reservedTotal.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(
                        "reserved percentages sum to " + reservedTotal + " which exceeds 100 in rule set " + version);
            }
            java.util.Set<String> hSeen = new java.util.HashSet<>();
            BigDecimal horizontalTotal = BigDecimal.ZERO;
            for (HorizontalQuota q : horizontal) {
                if (!hSeen.add(q.code())) {
                    throw new IllegalArgumentException("duplicate horizontal code in rule set " + version + ": " + q.code());
                }
                horizontalTotal = horizontalTotal.add(q.percent());
            }
            // Horizontal minima are overlapping guarantees, so their sum may legitimately
            // exceed 100% only if applicants can satisfy several at once. Above 100% the
            // rule set is arithmetically unsatisfiable for any single-flag population, so
            // we refuse it at publish time rather than discovering it during the draw.
            if (horizontalTotal.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(
                        "horizontal minima sum to " + horizontalTotal + " which cannot be guaranteed in rule set " + version);
            }
            if (totalUnits > 1_000_000) {
                throw new IllegalArgumentException("totalUnits looks implausible: " + totalUnits);
            }
        }
    }
}
