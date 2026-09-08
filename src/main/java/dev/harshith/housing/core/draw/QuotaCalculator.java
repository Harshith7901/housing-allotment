package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns percentages into whole flats.
 *
 * <p>This class is short and it is the most likely thing in the system to be litigated,
 * because 600 flats do not divide neatly by 7.5% and somebody loses a flat to a rounding
 * rule. Three decisions, all deliberate:
 *
 * <h2>1. Largest remainder, over a partition that sums to exactly 100%</h2>
 * The open category is treated as a participant with {@code 100 - sum(reserved)} percent
 * rather than as "whatever is left over". Every vertical then takes {@code floor} of its
 * exact entitlement, and the leftover seats go one each to the largest fractional
 * remainders. This guarantees the seats sum to the inventory exactly and that no category
 * is more than one seat away from its exact entitlement — which naive per-category
 * rounding does not: rounding 15%, 7.5% and 27% of 600 half-up independently and
 * subtracting can leave the open category short or over by several flats.
 *
 * <h2>2. Ties in the remainder are broken by declared order, not by value</h2>
 * If two categories have identical fractional remainders, the seat goes to whichever is
 * declared first in the published rule set. Arbitrary, but fixed in advance, printed in
 * the notification, and identical on every run. A tie broken by hash map iteration order
 * is the kind of defect that only shows up in court.
 *
 * <h2>3. Horizontal minima are computed independently and must fit</h2>
 * Horizontal quotas overlap — one person can satisfy several — so they are not a
 * partition and largest remainder does not apply. Each is rounded half-up against the
 * seats of its vertical. If the minima cannot jointly fit inside the vertical, the plan
 * is rejected here rather than producing a draw that quietly fails to honour a quota.
 */
public final class QuotaCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private QuotaCalculator() {
    }

    public static Draw.SeatPlan plan(Rules.RuleSet rules) {
        int total = rules.totalUnits();
        List<String> codes = new ArrayList<>();
        List<BigDecimal> percents = new ArrayList<>();
        List<String> workings = new ArrayList<>();

        BigDecimal reservedTotal = rules.reservedQuotas().stream()
                .map(Rules.ReservedQuota::percent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openPercent = HUNDRED.subtract(reservedTotal);

        codes.add(rules.openCode());
        percents.add(openPercent);
        for (Rules.ReservedQuota q : rules.reservedQuotas()) {
            codes.add(q.code());
            percents.add(q.percent());
        }

        workings.add("inventory=" + total + " units; rule set " + rules.version());
        workings.add("reserved percentages sum to " + reservedTotal
                + "%, so the open category is apportioned " + openPercent + "%");

        int[] seats = largestRemainder(total, percents, workings, codes);

        List<Draw.VerticalSeats> verticals = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            List<Draw.HorizontalMinimum> minima =
                    horizontalMinima(rules, codes.get(i), seats[i], workings);
            verticals.add(new Draw.VerticalSeats(codes.get(i), seats[i], minima));
        }

        int allocated = 0;
        for (int s : seats) {
            allocated += s;
        }
        if (allocated != total) {
            throw new IllegalStateException(
                    "apportionment bug: allocated " + allocated + " seats from an inventory of " + total);
        }
        workings.add("total apportioned=" + allocated + " (matches inventory)");

        return new Draw.SeatPlan(total, verticals, workings);
    }

    /**
     * Horizontal minima for one vertical, as whole seats.
     *
     * <p>Exposed separately because the supplementary pool created by lapsed reserved
     * seats needs its own minima computed against its own seat count.
     */
    public static List<Draw.HorizontalMinimum> horizontalMinima(Rules.RuleSet rules,
                                                                String verticalCode,
                                                                int seats,
                                                                List<String> workings) {
        List<Draw.HorizontalMinimum> minima = new ArrayList<>();
        int sum = 0;
        for (Rules.HorizontalQuota h : rules.horizontalQuotas()) {
            int min = h.percent()
                    .multiply(BigDecimal.valueOf(seats))
                    .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                    .intValue();
            minima.add(new Draw.HorizontalMinimum(h.code(), min));
            sum += min;
            if (workings != null && min > 0) {
                workings.add("  " + verticalCode + ": horizontal minimum " + h.code()
                        + " = round(" + h.percent() + "% of " + seats + ") = " + min);
            }
        }
        if (sum > seats) {
            throw new IllegalStateException(
                    "horizontal minima for vertical " + verticalCode + " require " + sum
                            + " seats but only " + seats + " are apportioned to it - "
                            + "the rule set is not satisfiable and must be amended before the draw");
        }
        return minima;
    }

    /**
     * Hare / largest-remainder apportionment.
     *
     * @param percents percentages parallel to {@code codes}; expected to sum to 100
     */
    private static int[] largestRemainder(int total,
                                          List<BigDecimal> percents,
                                          List<String> workings,
                                          List<String> codes) {
        int n = percents.size();
        int[] seats = new int[n];
        BigDecimal[] remainders = new BigDecimal[n];
        int assigned = 0;

        for (int i = 0; i < n; i++) {
            BigDecimal exact = percents.get(i)
                    .multiply(BigDecimal.valueOf(total))
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(0, RoundingMode.FLOOR);
            seats[i] = floor.intValue();
            remainders[i] = exact.subtract(floor);
            assigned += seats[i];
            workings.add("  " + codes.get(i) + ": " + percents.get(i) + "% of " + total
                    + " = " + exact.stripTrailingZeros().toPlainString()
                    + " -> floor " + seats[i] + ", remainder " + remainders[i].stripTrailingZeros().toPlainString());
        }

        int leftover = total - assigned;
        if (leftover > 0) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            final BigDecimal[] rem = remainders;
            Comparator<Integer> byRemainderDesc =
                    Comparator.comparing((Integer i) -> rem[i], Comparator.<BigDecimal>reverseOrder());
            order.sort(byRemainderDesc.thenComparing(Comparator.<Integer>naturalOrder()));
            for (int k = 0; k < leftover; k++) {
                int idx = order.get(k);
                seats[idx]++;
                workings.add("  remainder seat " + (k + 1) + " of " + leftover
                        + " -> " + codes.get(idx) + " (largest remaining fraction "
                        + rem[idx].stripTrailingZeros().toPlainString()
                        + "; ties broken by declared order)");
            }
        }
        return seats;
    }
}
