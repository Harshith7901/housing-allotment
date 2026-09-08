package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.util.Hashing;

import java.util.Comparator;

/**
 * Turns the seed plus an application id into that application's lottery ticket.
 *
 * <pre>
 *   ticket(app) = SHA256("ticket/1|" + seedHex + "|" + applicationId)
 * </pre>
 *
 * <p>Ordering the draw by ticket value has properties a shuffled list does not:
 *
 * <ul>
 *   <li><b>It is independent of language, library and JVM.</b> {@code java.util.Random}
 *       with a fixed seed is reproducible only in Java, and only while the JDK keeps that
 *       algorithm. SHA-256 is reproducible in a five-line Python script or a shell
 *       one-liner, which is what makes independent verification realistic.</li>
 *   <li><b>It is per-applicant, not per-list.</b> A shuffle depends on the order and the
 *       exact membership of the input list, so adding one late applicant reshuffles
 *       everybody. Here each ticket depends only on the seed and that person's own id, so
 *       a person's ticket is stable and individually checkable.</li>
 *   <li><b>It is uniform and unpredictable before the reveal.</b> No applicant can choose
 *       an id that yields a low ticket, because the seed is not known when ids are issued.</li>
 * </ul>
 *
 * <p>The one thing to be careful about: because the ticket depends on the application id,
 * ids must be issued without regard to the seed and must never be reissued. That is
 * enforced upstream by making application ids immutable once acknowledged.
 */
public final class TicketGenerator {

    private static final String DOMAIN = "ticket/1";

    private TicketGenerator() {
    }

    public static String ticket(String seedHex, String applicationId) {
        return Hashing.sha256Hex(DOMAIN + "|" + seedHex + "|" + applicationId);
    }

    /**
     * The canonical ranking order inside a pool.
     *
     * <p>Residency tier first, because a published preference for existing residents is a
     * rule, not a tie-break. Then the ticket. Then the application id, which can only ever
     * matter on a 256-bit hash collision but is included so that the comparator is a total
     * order rather than merely a partial one — a comparator that returns 0 for distinct
     * elements makes the sort result depend on the sort algorithm.
     */
    public static Comparator<Ranked> order() {
        Comparator<Ranked> byTier = Comparator.comparingInt(Ranked::residencyTier);
        return byTier
                .thenComparing(Ranked::ticketHex, Comparator.<String>naturalOrder())
                .thenComparing(Ranked::applicationId, Comparator.<String>naturalOrder());
    }

    /** A roll entry with its ticket and residency tier resolved, ready to be ranked. */
    public record Ranked(Roll.RollEntry entry, String ticketHex, int residencyTier) {
        public String applicationId() {
            return entry.applicationId();
        }
    }
}
