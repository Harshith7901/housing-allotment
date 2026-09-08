package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Draw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills seats vacated after the draw.
 *
 * <p>Between publication and possession, seats come free: a winner declines, misses the
 * payment deadline, or fails document verification. Historically this is where allotment
 * schemes are actually corrupted — not in the lottery, which is public and watched, but in
 * the quiet reallocation of a dozen flats months later.
 *
 * <p>So promotion is a <b>pure function of the published draw</b> and carries no
 * discretion whatsoever. The next person in the same pool's waitlist takes the seat. The
 * only human input is the decision that a seat is genuinely vacant, which is itself a
 * recorded, evidenced, separately authorised act with its own audit entry.
 *
 * <p>The seat is filled from the <em>same pool</em> that awarded it, not from a single
 * global list. A vacated reserved seat is still a reserved seat; sending it to the top of
 * an open waitlist would erode the quota one forfeiture at a time.
 */
public final class WaitlistPromoter {

    private WaitlistPromoter() {
    }

    public record Promotion(
            String vacatedByApplicationId,
            String promotedApplicationId,
            String poolCode,
            int waitlistPosition,
            String basis
    ) {
    }

    /**
     * @param forfeited        applications whose seats have been declared vacant
     * @param alreadySelected  everyone currently holding a seat, including earlier
     *                         promotions, so that a seat is never offered twice
     * @param declined         applications that already declined a promotion and must be
     *                         skipped rather than re-offered
     */
    public static List<Promotion> promote(Draw.DrawOutcome outcome,
                                          List<String> forfeited,
                                          Set<String> alreadySelected,
                                          Set<String> declined) {
        Set<String> holders = new LinkedHashSet<>(alreadySelected);
        Set<String> skip = new LinkedHashSet<>(declined);
        skip.addAll(forfeited);

        // Vacancies are processed in ticket order so the sequence of promotions does not
        // depend on the order in which forfeitures happened to be keyed in.
        List<Draw.Selection> vacating = new ArrayList<>();
        for (String id : forfeited) {
            vacating.add(outcome.forApplication(id));
        }
        Comparator<Draw.Selection> byTicket =
                Comparator.comparing(Draw.Selection::ticketHex, Comparator.<String>naturalOrder());
        vacating.sort(byTicket.thenComparing(Draw.Selection::applicationId, Comparator.<String>naturalOrder()));

        List<Promotion> promotions = new ArrayList<>();
        for (Draw.Selection vacancy : vacating) {
            holders.remove(vacancy.applicationId());
            Draw.PoolResult pool = outcome.pool(vacancy.decidingPoolCode());

            int position = 0;
            Promotion made = null;
            for (Draw.PoolCandidate candidate : pool.ranking()) {
                String id = candidate.applicationId();
                if (holders.contains(id)) {
                    continue;
                }
                position++;
                if (skip.contains(id)) {
                    continue;
                }
                made = new Promotion(vacancy.applicationId(), id, pool.poolCode(), position,
                        "next eligible applicant on the waitlist of pool " + pool.poolCode()
                                + " at rank " + candidate.rankInPool()
                                + "; seat vacated by " + vacancy.applicationId());
                break;
            }
            if (made == null) {
                promotions.add(new Promotion(vacancy.applicationId(), null, pool.poolCode(), 0,
                        "pool " + pool.poolCode() + " has no remaining waitlisted applicant; "
                                + "the seat must be carried to the next draw"));
                continue;
            }
            holders.add(made.promotedApplicationId());
            skip.add(made.promotedApplicationId());
            promotions.add(made);
        }
        return promotions;
    }
}
