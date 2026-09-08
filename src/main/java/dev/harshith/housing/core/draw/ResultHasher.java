package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.core.util.Hashing;

import java.time.temporal.ChronoUnit;

/**
 * Canonical text form of a draw result, and its hash.
 *
 * <p>The result hash is what gets read out at the public announcement and printed in the
 * notification. Anyone who later downloads the result file can hash it and confirm they
 * are looking at the announced result and not a revision.
 *
 * <p>Only decision-bearing fields are included. Human-readable reason sentences are
 * <em>excluded</em> deliberately: they are presentation, and improving the wording of an
 * explanation must not invalidate the hash of a result that has already been announced.
 * The machine-readable reason code is included, because that is substance.
 */
public final class ResultHasher {

    private ResultHasher() {
    }

    public static String hash(Draw.DrawOutcome outcome) {
        return Hashing.sha256Hex(encode(outcome));
    }

    public static String encode(Draw.DrawOutcome o) {
        StringBuilder sb = new StringBuilder();
        sb.append("result/1\n");
        sb.append("drawId=").append(o.drawId()).append('\n');
        sb.append("rollId=").append(o.rollId()).append('\n');
        sb.append("rollHash=").append(o.rollHash()).append('\n');
        sb.append("ruleSetVersion=").append(o.ruleSetVersion()).append('\n');
        sb.append("ruleSetHash=").append(o.ruleSetHash()).append('\n');
        sb.append("seedHex=").append(o.seedHex()).append('\n');
        sb.append("executedAt=").append(o.executedAt().truncatedTo(ChronoUnit.SECONDS)).append('\n');
        sb.append("totalUnits=").append(o.seatPlan().totalUnits()).append('\n');

        for (Draw.VerticalSeats v : o.seatPlan().verticals()) {
            sb.append("plan=").append(v.code()).append('|').append(v.seats());
            for (Draw.HorizontalMinimum m : v.minima()) {
                sb.append('|').append(m.code()).append(':').append(m.minimumSeats());
            }
            sb.append('\n');
        }

        for (Draw.PoolResult p : o.pools()) {
            sb.append("pool=").append(p.poolCode()).append('|')
              .append(p.verticalCode()).append('|')
              .append(p.seats()).append('|')
              .append(p.candidateCount()).append('|')
              .append(p.unfilledSeats()).append('\n');
        }

        // Selections are already sorted by application id by the engine; sorting is part
        // of the canonical form, so it is asserted here rather than assumed.
        String previous = null;
        for (Draw.Selection s : o.selections()) {
            if (previous != null && previous.compareTo(s.applicationId()) >= 0) {
                throw new IllegalStateException(
                        "selections must be sorted by application id before hashing; saw "
                                + previous + " then " + s.applicationId());
            }
            previous = s.applicationId();
            sb.append("sel=").append(s.applicationId()).append('|')
              .append(s.outcome()).append('|')
              .append(s.decidingPoolCode()).append('|')
              .append(s.ticketHex()).append('|')
              .append(s.residencyTier()).append('|')
              .append(s.rankInDecidingPool()).append('|')
              .append(s.waitlistPosition() == null ? "-" : s.waitlistPosition()).append('|')
              .append(s.reasonCode()).append('\n');
        }
        return sb.toString();
    }

    public static boolean verify(Draw.DrawOutcome outcome) {
        Draw.DrawOutcome withoutHash = new Draw.DrawOutcome(
                outcome.drawId(), outcome.rollId(), outcome.rollHash(), outcome.ruleSetVersion(),
                outcome.ruleSetHash(), outcome.seedHex(), outcome.executedAt(), outcome.seatPlan(),
                outcome.pools(), outcome.selections(), "");
        return hash(withoutHash).equals(outcome.resultHash());
    }
}
