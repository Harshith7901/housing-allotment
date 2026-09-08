package dev.harshith.housing.core.units;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.util.Hashing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns actual flats to the selected applicants.
 *
 * <p>Selecting 600 households is only half the job; they then have to be matched to 600
 * specific flats, and ground-floor and larger units are not equally desirable. The rule
 * here is <b>serial dictatorship in ticket order</b>: winners are processed in ascending
 * ticket value, and each takes the best still-available flat from their own stated
 * preference list.
 *
 * <p>Why this rule and not something cleverer:
 *
 * <ul>
 *   <li><b>It is strategy-proof.</b> Nobody can improve their outcome by misreporting
 *       their preferences, so applicants have no reason to game the form and the authority
 *       has no reason to be accused of rewarding those who did.</li>
 *   <li><b>It is explainable in one sentence to a person who lost the flat they wanted.</b>
 *       "Three households with lower ticket numbers than yours also asked for a
 *       ground-floor two-bedroom, and there were two left." A welfare-maximising
 *       assignment might place more people in a preferred flat overall, but it cannot be
 *       explained to the individual who was moved, and unexplainable is indefensible.</li>
 *   <li><b>Ticket order is already public.</b> No new ordering has to be justified.</li>
 * </ul>
 *
 * <p>Fallback: an applicant whose stated preferences are all exhausted receives the
 * lowest-numbered remaining unit, recorded as {@code preferenceRankHonoured = 0} so the
 * exception is visible in the published plan rather than hidden.
 */
public final class UnitAssigner {

    private UnitAssigner() {
    }

    public static Units.AllotmentPlan assign(Draw.DrawOutcome outcome,
                                             Roll.DrawRoll roll,
                                             List<Units.UnitInventoryItem> inventory) {
        Map<String, Roll.RollEntry> entries = new LinkedHashMap<>();
        for (Roll.RollEntry e : roll.entries()) {
            entries.put(e.applicationId(), e);
        }

        // Winners in ascending ticket order: one published order across all pools, so a
        // reserved-category winner is not systematically served before or after an open
        // winner.
        List<Draw.Selection> winners = new ArrayList<>();
        for (Draw.Selection s : outcome.selections()) {
            if (s.outcome() == Draw.Outcome.SELECTED) {
                winners.add(s);
            }
        }
        Comparator<Draw.Selection> byTicket =
                Comparator.comparing(Draw.Selection::ticketHex, Comparator.<String>naturalOrder());
        winners.sort(byTicket.thenComparing(Draw.Selection::applicationId, Comparator.<String>naturalOrder()));

        // Available units, grouped by type, each group in unit-id order.
        List<Units.UnitInventoryItem> available = new ArrayList<>(inventory);
        available.sort(Comparator.comparing(Units.UnitInventoryItem::unitId));
        Map<String, List<Units.UnitInventoryItem>> byType = new LinkedHashMap<>();
        for (Units.UnitInventoryItem u : available) {
            byType.computeIfAbsent(u.unitType(), k -> new ArrayList<>()).add(u);
        }

        List<Units.UnitAllotment> allotments = new ArrayList<>();
        List<String> withoutUnit = new ArrayList<>();
        List<String> workings = new ArrayList<>();
        workings.add("winners=" + winners.size() + "; inventory=" + inventory.size()
                + "; assignment=serial dictatorship in ascending ticket order");

        int pickOrder = 0;
        for (Draw.Selection winner : winners) {
            pickOrder++;
            Roll.RollEntry entry = entries.get(winner.applicationId());
            List<String> preferences = entry == null ? List.of() : entry.unitTypePreferences();

            Units.UnitInventoryItem taken = null;
            int honoured = 0;
            for (int i = 0; i < preferences.size(); i++) {
                List<Units.UnitInventoryItem> pool = byType.get(preferences.get(i));
                if (pool != null && !pool.isEmpty()) {
                    taken = pool.remove(0);
                    honoured = i + 1;
                    break;
                }
            }
            if (taken == null) {
                taken = takeLowestRemaining(byType);
                honoured = 0;
            }
            if (taken == null) {
                withoutUnit.add(winner.applicationId());
                workings.add("  pick " + pickOrder + ": " + winner.applicationId()
                        + " could not be housed - inventory exhausted");
                continue;
            }
            String basis = honoured > 0
                    ? "preference " + honoured + " of " + preferences.size() + " honoured"
                    : (preferences.isEmpty()
                            ? "no preference stated; lowest-numbered available unit assigned"
                            : "all stated preferences exhausted; lowest-numbered available unit assigned");
            allotments.add(new Units.UnitAllotment(winner.applicationId(), taken.unitId(),
                    taken.unitType(), taken.block(), pickOrder, honoured, basis));
        }

        List<String> leftOver = new ArrayList<>();
        byType.values().forEach(list -> list.forEach(u -> leftOver.add(u.unitId())));
        leftOver.sort(Comparator.naturalOrder());

        allotments.sort(Comparator.comparing(Units.UnitAllotment::applicationId));
        String hash = Hashing.sha256Hex(encode(outcome.drawId(), allotments));
        workings.add("assigned=" + allotments.size() + "; unhoused winners=" + withoutUnit.size()
                + "; unassigned units=" + leftOver.size());

        return new Units.AllotmentPlan(outcome.drawId(), allotments, withoutUnit, leftOver, workings, hash);
    }

    private static Units.UnitInventoryItem takeLowestRemaining(
            Map<String, List<Units.UnitInventoryItem>> byType) {
        Units.UnitInventoryItem best = null;
        List<Units.UnitInventoryItem> bestList = null;
        for (List<Units.UnitInventoryItem> list : byType.values()) {
            if (list.isEmpty()) {
                continue;
            }
            Units.UnitInventoryItem head = list.get(0);
            if (best == null || head.unitId().compareTo(best.unitId()) < 0) {
                best = head;
                bestList = list;
            }
        }
        if (bestList != null) {
            bestList.remove(0);
        }
        return best;
    }

    private static String encode(String drawId, List<Units.UnitAllotment> allotments) {
        StringBuilder sb = new StringBuilder("allotment/1\ndrawId=").append(drawId).append('\n');
        for (Units.UnitAllotment a : allotments) {
            sb.append("a=").append(a.applicationId()).append('|')
              .append(a.unitId()).append('|')
              .append(a.unitType()).append('|')
              .append(a.preferenceRankHonoured()).append('\n');
        }
        return sb.toString();
    }
}
