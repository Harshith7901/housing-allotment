package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.model.Rules;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the draw. Pure function: {@code (roll, rules, seed) -> outcome}.
 *
 * <p>No clock, no database, no randomness, no configuration read from the environment.
 * Given the same three inputs it returns a bit-identical result on any machine, in any
 * time zone, in any JVM. That property is the whole point: it is what lets an applicant's
 * lawyer recompute the draw from published artefacts and get the same 600 names.
 *
 * <h2>Order of operations</h2>
 * <ol>
 *   <li><b>Apportion</b> the inventory into vertical categories ({@link QuotaCalculator}).</li>
 *   <li><b>Open pool first, over every applicant regardless of category.</b> This ordering
 *       is not cosmetic. A reserved-category applicant who ranks inside the open list takes
 *       an open seat and does <em>not</em> consume their category's quota, so the quota
 *       goes to the next person in that category. Running the reserved pools first would
 *       quietly convert the quota into a ceiling, which is the opposite of its purpose and
 *       is exactly the kind of thing that gets a scheme struck down.</li>
 *   <li><b>Each reserved pool</b>, in the order declared in the published rule set, over
 *       that category's applicants who did not already win an open seat.</li>
 *   <li><b>Horizontal minima</b> (women, persons with disability, ex-servicemen…) are
 *       enforced inside each pool as minimum guarantees after ranking, by promoting the
 *       highest-ranked qualifying candidate and displacing the lowest-ranked selected
 *       candidate who is not himself needed for a quota. Every promotion and displacement
 *       is recorded by name.</li>
 *   <li><b>Reserved seats nobody claimed</b> are re-offered in a clearly labelled
 *       supplementary open pool, or carried forward, according to the rule set.</li>
 *   <li><b>Everyone else is ranked into waitlists</b>, and every applicant on the roll
 *       gets an outcome record with a reason.</li>
 * </ol>
 */
public final class AllocationEngine {

    /** Pool code for reserved seats that lapsed to the open category. */
    public static final String LAPSED_POOL_CODE = "OPEN_LAPSED";

    private AllocationEngine() {
    }

    public static Draw.DrawOutcome execute(String drawId,
                                           Roll.DrawRoll roll,
                                           Rules.RuleSet rules,
                                           Draw.DrawSeed seed,
                                           Instant executedAt) {
        validate(roll, rules, seed);

        Draw.SeatPlan plan = QuotaCalculator.plan(rules);

        // --- 1. every applicant gets a ticket and a residency tier -------------------
        Map<String, TicketGenerator.Ranked> ranked = new LinkedHashMap<>();
        for (Roll.RollEntry e : roll.entries()) {
            String ticket = TicketGenerator.ticket(seed.seedHex(), e.applicationId());
            ranked.put(e.applicationId(), new TicketGenerator.Ranked(e, ticket, residencyTier(rules, e)));
        }

        Set<String> selectedGlobal = new LinkedHashSet<>();
        Map<String, String> selectedInPool = new HashMap<>();
        Map<String, Draw.ReasonCode> selectionReason = new HashMap<>();
        Set<String> displacedAnywhere = new LinkedHashSet<>();
        List<Draw.PoolResult> pools = new ArrayList<>();

        // --- 2. the open pool, over the whole roll ----------------------------------
        Draw.VerticalSeats openPlan = plan.vertical(rules.openCode());
        PoolRun open = runPool(rules.openCode(), rules.openCode(), openPlan.seats(),
                new ArrayList<>(ranked.values()), openPlan.minima());
        absorb(open, pools, selectedGlobal, selectedInPool, selectionReason, displacedAnywhere, null);

        // --- 3. reserved pools, in published order ----------------------------------
        int reservedUnfilled = 0;
        for (Rules.ReservedQuota q : rules.reservedQuotas()) {
            Draw.VerticalSeats vs = plan.vertical(q.code());
            List<TicketGenerator.Ranked> candidates = new ArrayList<>();
            for (TicketGenerator.Ranked r : ranked.values()) {
                if (r.entry().verticalCode().equals(q.code()) && !selectedGlobal.contains(r.applicationId())) {
                    candidates.add(r);
                }
            }
            PoolRun run = runPool(q.code(), q.code(), vs.seats(), candidates, vs.minima());
            absorb(run, pools, selectedGlobal, selectedInPool, selectionReason, displacedAnywhere, null);
            reservedUnfilled += run.result().unfilledSeats();
        }

        // --- 4. lapsed reserved seats ------------------------------------------------
        if (reservedUnfilled > 0 && rules.lapsePolicy() == Rules.LapsePolicy.LAPSE_TO_OPEN) {
            List<TicketGenerator.Ranked> candidates = new ArrayList<>();
            for (TicketGenerator.Ranked r : ranked.values()) {
                if (!selectedGlobal.contains(r.applicationId())) {
                    candidates.add(r);
                }
            }
            List<Draw.HorizontalMinimum> minima =
                    QuotaCalculator.horizontalMinima(rules, LAPSED_POOL_CODE, reservedUnfilled, null);
            PoolRun run = runPool(LAPSED_POOL_CODE, rules.openCode(), reservedUnfilled, candidates, minima);
            absorb(run, pools, selectedGlobal, selectedInPool, selectionReason, displacedAnywhere,
                    Draw.ReasonCode.SELECTED_IN_LAPSED_OPEN_POOL);
        }

        // --- 5. waitlist position: the best position across every pool an applicant
        //        appeared in, counted over candidates who were not selected anywhere.
        Map<String, Integer> bestPosition = new HashMap<>();
        Map<String, String> bestPositionPool = new HashMap<>();
        Map<String, Integer> bestPositionRank = new HashMap<>();
        for (Draw.PoolResult p : pools) {
            int position = 0;
            for (Draw.PoolCandidate c : p.ranking()) {
                if (selectedGlobal.contains(c.applicationId())) {
                    continue;
                }
                position++;
                Integer current = bestPosition.get(c.applicationId());
                if (current == null || position < current) {
                    bestPosition.put(c.applicationId(), position);
                    bestPositionPool.put(c.applicationId(), p.poolCode());
                    bestPositionRank.put(c.applicationId(), c.rankInPool());
                }
            }
        }

        // --- 6. an outcome record for every person on the roll -----------------------
        Map<String, Integer> poolSeats = new HashMap<>();
        Map<String, Integer> poolCandidates = new HashMap<>();
        for (Draw.PoolResult p : pools) {
            poolSeats.put(p.poolCode(), p.seats());
            poolCandidates.put(p.poolCode(), p.candidateCount());
        }

        List<Draw.Selection> selections = new ArrayList<>();
        for (TicketGenerator.Ranked r : ranked.values()) {
            String id = r.applicationId();
            if (selectedGlobal.contains(id)) {
                String poolCode = selectedInPool.get(id);
                int rank = rankWithin(pools, poolCode, id);
                Draw.ReasonCode code = selectionReason.get(id);
                selections.add(new Draw.Selection(
                        id, Draw.Outcome.SELECTED, poolCode, r.ticketHex(), r.residencyTier(), rank, null, code,
                        selectedText(code, poolCode, rank, poolSeats.get(poolCode), poolCandidates.get(poolCode))));
            } else {
                String poolCode = bestPositionPool.getOrDefault(id, rules.openCode());
                int position = bestPosition.getOrDefault(id, 0);
                int rank = bestPositionRank.getOrDefault(id, 0);
                int seats = poolSeats.getOrDefault(poolCode, 0);
                int candidateCount = poolCandidates.getOrDefault(poolCode, 0);
                boolean waitlisted = position > 0 && position <= rules.waitlistSize();
                Draw.ReasonCode code;
                if (displacedAnywhere.contains(id)) {
                    code = Draw.ReasonCode.DISPLACED_BY_HORIZONTAL_MINIMUM;
                } else if (seats == 0) {
                    code = Draw.ReasonCode.NOT_SELECTED_NO_SEATS_IN_POOL;
                } else if (waitlisted) {
                    code = Draw.ReasonCode.WAITLISTED_BEHIND_SELECTED;
                } else {
                    code = Draw.ReasonCode.NOT_SELECTED_RANK_BELOW_SEATS;
                }
                selections.add(new Draw.Selection(
                        id,
                        waitlisted ? Draw.Outcome.WAITLISTED : Draw.Outcome.NOT_SELECTED,
                        poolCode, r.ticketHex(), r.residencyTier(), rank,
                        waitlisted ? position : null, code,
                        notSelectedText(code, poolCode, rank, seats, candidateCount, position, waitlisted)));
            }
        }
        selections.sort(Comparator.comparing(Draw.Selection::applicationId));

        Draw.DrawOutcome unhashed = new Draw.DrawOutcome(
                drawId, roll.rollId(), roll.rollHash(), rules.version(), roll.ruleSetHash(),
                seed.seedHex(), executedAt.truncatedTo(ChronoUnit.SECONDS), plan, pools, selections, "");
        String resultHash = ResultHasher.hash(unhashed);
        return new Draw.DrawOutcome(
                drawId, roll.rollId(), roll.rollHash(), rules.version(), roll.ruleSetHash(),
                seed.seedHex(), executedAt.truncatedTo(ChronoUnit.SECONDS), plan, pools, selections, resultHash);
    }

    // ------------------------------------------------------------------ one pool

    private record PoolRun(Draw.PoolResult result,
                           Map<String, Draw.ReasonCode> reasons,
                           List<String> displaced) {
    }

    /**
     * Ranks the candidates, fills the seats, then enforces the horizontal minima.
     *
     * <p>The displacement rule deserves a note because it is the only place in the engine
     * where somebody who was provisionally selected loses their place. When a minimum is
     * short, we take the highest-ranked qualifying candidate from outside the cut and
     * displace the <em>lowest-ranked</em> selected candidate who (a) does not himself
     * satisfy the quota being filled — displacing him would not help — and (b) is not the
     * last person holding up an already-satisfied minimum. If no such candidate exists the
     * minimum is recorded as unmet rather than being forced, because forcing it would mean
     * breaking a different published guarantee, and a recorded, explained shortfall is far
     * more defensible than a silent one.
     */
    private static PoolRun runPool(String poolCode,
                                   String verticalCode,
                                   int seats,
                                   List<TicketGenerator.Ranked> candidates,
                                   List<Draw.HorizontalMinimum> minima) {
        List<TicketGenerator.Ranked> sorted = new ArrayList<>(candidates);
        sorted.sort(TicketGenerator.order());

        Map<String, TicketGenerator.Ranked> byId = new HashMap<>();
        Map<String, Integer> rankOf = new HashMap<>();
        List<Draw.PoolCandidate> ranking = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            TicketGenerator.Ranked r = sorted.get(i);
            int rank = i + 1;
            byId.put(r.applicationId(), r);
            rankOf.put(r.applicationId(), rank);
            ranking.add(new Draw.PoolCandidate(r.applicationId(), r.ticketHex(), r.residencyTier(), rank));
        }

        int fill = Math.min(Math.max(seats, 0), sorted.size());
        TreeMap<Integer, String> provisional = new TreeMap<>();
        Set<String> inProvisional = new HashSet<>();
        Map<String, Draw.ReasonCode> reasons = new LinkedHashMap<>();
        for (int i = 0; i < fill; i++) {
            String id = sorted.get(i).applicationId();
            provisional.put(i + 1, id);
            inProvisional.add(id);
            reasons.put(id, Draw.ReasonCode.SELECTED_ON_MERIT);
        }

        List<Draw.TopUpNote> notes = new ArrayList<>();
        List<String> displaced = new ArrayList<>();
        List<Draw.HorizontalMinimum> processed = new ArrayList<>();

        for (Draw.HorizontalMinimum h : minima) {
            processed.add(h);
            if (h.minimumSeats() <= 0) {
                continue;
            }
            int have = countSatisfying(provisional.values(), byId, h.code());
            if (have >= h.minimumSeats()) {
                continue;
            }
            int shortfall = h.minimumSeats() - have;

            List<String> outsiders = new ArrayList<>();
            for (TicketGenerator.Ranked r : sorted) {
                if (!inProvisional.contains(r.applicationId()) && r.entry().satisfies(h.code())) {
                    outsiders.add(r.applicationId());
                }
            }

            List<String> promoted = new ArrayList<>();
            List<String> pushedOut = new ArrayList<>();
            String note = null;

            for (String promoteId : outsiders) {
                if (promoted.size() >= shortfall) {
                    break;
                }
                String victim = null;
                for (Map.Entry<Integer, String> candidate : provisional.descendingMap().entrySet()) {
                    String cid = candidate.getValue();
                    if (byId.get(cid).entry().satisfies(h.code())) {
                        continue;
                    }
                    if (neededForMinimum(cid, processed, provisional.values(), byId)) {
                        continue;
                    }
                    victim = cid;
                    break;
                }
                if (victim == null) {
                    note = "minimum for " + h.code() + " could not be fully met: every remaining selected "
                            + "candidate is required by another published minimum";
                    break;
                }
                provisional.remove(rankOf.get(victim));
                inProvisional.remove(victim);
                reasons.remove(victim);
                displaced.add(victim);

                provisional.put(rankOf.get(promoteId), promoteId);
                inProvisional.add(promoteId);
                reasons.put(promoteId, Draw.ReasonCode.SELECTED_VIA_HORIZONTAL_MINIMUM);
                displaced.remove(promoteId);
                promoted.add(promoteId);
                pushedOut.add(victim);
            }

            if (note == null && promoted.size() < shortfall) {
                note = "minimum for " + h.code() + " could not be fully met: only " + promoted.size()
                        + " of " + shortfall + " additional qualifying applicant(s) were available in this pool";
            }
            notes.add(new Draw.TopUpNote(
                    h.code(), h.minimumSeats(), have, promoted, pushedOut,
                    note == null
                            ? "minimum met by promoting " + promoted.size() + " qualifying applicant(s)"
                            : note));
        }

        List<String> selected = new ArrayList<>(provisional.values());
        return new PoolRun(
                new Draw.PoolResult(poolCode, verticalCode, seats, ranking, selected, notes, seats - fill),
                reasons,
                displaced);
    }

    private static int countSatisfying(Collection<String> ids,
                                       Map<String, TicketGenerator.Ranked> byId,
                                       String horizontalCode) {
        int count = 0;
        for (String id : ids) {
            if (byId.get(id).entry().satisfies(horizontalCode)) {
                count++;
            }
        }
        return count;
    }

    /**
     * True if removing {@code id} would take an already-processed minimum below its
     * guarantee, in which case this candidate may not be displaced.
     */
    private static boolean neededForMinimum(String id,
                                            List<Draw.HorizontalMinimum> processed,
                                            Collection<String> provisional,
                                            Map<String, TicketGenerator.Ranked> byId) {
        Roll.RollEntry entry = byId.get(id).entry();
        for (Draw.HorizontalMinimum m : processed) {
            if (m.minimumSeats() <= 0 || !entry.satisfies(m.code())) {
                continue;
            }
            if (countSatisfying(provisional, byId, m.code()) <= m.minimumSeats()) {
                return true;
            }
        }
        return false;
    }

    private static void absorb(PoolRun run,
                               List<Draw.PoolResult> pools,
                               Set<String> selectedGlobal,
                               Map<String, String> selectedInPool,
                               Map<String, Draw.ReasonCode> selectionReason,
                               Set<String> displacedAnywhere,
                               Draw.ReasonCode reasonOverride) {
        pools.add(run.result());
        for (Map.Entry<String, Draw.ReasonCode> e : run.reasons().entrySet()) {
            selectedGlobal.add(e.getKey());
            selectedInPool.put(e.getKey(), run.result().poolCode());
            selectionReason.put(e.getKey(), reasonOverride != null ? reasonOverride : e.getValue());
            displacedAnywhere.remove(e.getKey());
        }
        displacedAnywhere.addAll(run.displaced());
    }

    private static int rankWithin(List<Draw.PoolResult> pools, String poolCode, String applicationId) {
        for (Draw.PoolResult p : pools) {
            if (!p.poolCode().equals(poolCode)) {
                continue;
            }
            for (Draw.PoolCandidate c : p.ranking()) {
                if (c.applicationId().equals(applicationId)) {
                    return c.rankInPool();
                }
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    private static int residencyTier(Rules.RuleSet rules, Roll.RollEntry entry) {
        if (rules.residency().mode() == Rules.ResidencyMode.PRIORITY_TIER) {
            return entry.residencyYears() >= rules.residency().minYears() ? 0 : 1;
        }
        return 0;
    }

    private static void validate(Roll.DrawRoll roll, Rules.RuleSet rules, Draw.DrawSeed seed) {
        if (!RollHasher.verify(roll)) {
            throw new IllegalStateException(
                    "roll " + roll.rollId() + " does not match its own hash; it has been altered since freezing");
        }
        if (!roll.ruleSetVersion().equals(rules.version())) {
            throw new IllegalStateException("roll was frozen under rule set " + roll.ruleSetVersion()
                    + " but the draw was asked to use " + rules.version());
        }
        if (!roll.rollHash().equals(seed.rollHash())) {
            throw new IllegalStateException("the seed was committed against roll hash " + seed.rollHash()
                    + " but this roll hashes to " + roll.rollHash());
        }
        for (Roll.RollEntry e : roll.entries()) {
            if (!rules.knowsVertical(e.verticalCode())) {
                throw new IllegalStateException("roll entry " + e.applicationId()
                        + " has category " + e.verticalCode() + " which rule set " + rules.version()
                        + " does not define");
            }
            for (String h : e.horizontalCodes()) {
                if (!rules.knowsHorizontal(h)) {
                    throw new IllegalStateException("roll entry " + e.applicationId()
                            + " claims horizontal quota " + h + " which rule set " + rules.version()
                            + " does not define");
                }
            }
        }
    }

    private static String selectedText(Draw.ReasonCode code, String pool, int rank, Integer seats, Integer candidates) {
        String base = "Selected in pool " + pool + " at rank " + rank + " of " + candidates
                + " candidates for " + seats + " seats.";
        return switch (code) {
            case SELECTED_VIA_HORIZONTAL_MINIMUM -> base
                    + " Selection arises from a published minimum guarantee for a horizontal quota.";
            case SELECTED_IN_LAPSED_OPEN_POOL -> base
                    + " This seat was a reserved seat that no eligible applicant in its category claimed,"
                    + " re-offered to the open category under the published lapse rule.";
            default -> base + " Selection is on the merit of the ticket order alone.";
        };
    }

    private static String notSelectedText(Draw.ReasonCode code, String pool, int rank, int seats,
                                          int candidates, int position, boolean waitlisted) {
        String base = "Ranked " + rank + " of " + candidates + " in pool " + pool
                + ", which had " + seats + " seats.";
        if (waitlisted) {
            base += " Waitlist position " + position + ".";
        }
        return switch (code) {
            case DISPLACED_BY_HORIZONTAL_MINIMUM -> base
                    + " This application was inside the initial cut but was displaced to honour a published"
                    + " minimum guarantee for a horizontal quota; the displacement is recorded in the draw"
                    + " result and places the application at the front of the waitlist.";
            case NOT_SELECTED_NO_SEATS_IN_POOL -> base
                    + " No seats were apportioned to this pool under the published rule set.";
            case WAITLISTED_BEHIND_SELECTED -> base
                    + " The application is on the waitlist and will be considered in order if a selected"
                    + " applicant forfeits or fails verification.";
            default -> base
                    + " The ticket ranked below the number of available seats, and below the published"
                    + " waitlist length.";
        };
    }
}
