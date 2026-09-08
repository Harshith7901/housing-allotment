package dev.harshith.housing.core.draw;

import dev.harshith.housing.core.model.Roll;
import dev.harshith.housing.core.util.Hashing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces the canonical text form of a frozen roll and its hash.
 *
 * <p>The output of {@link #encode} is what gets published alongside the result, and it is
 * the exact input a third party feeds to {@code sha256sum} to check that the roll they
 * were given is the roll that was drawn from. Two properties are therefore non-negotiable:
 *
 * <ul>
 *   <li><b>Total ordering.</b> Entries are sorted by application id, never left in
 *       insertion or database order. A roll whose hash depends on which replica answered
 *       the query is worthless.</li>
 *   <li><b>Fixed precision.</b> The freeze timestamp is truncated to whole seconds, so a
 *       round trip through a column with different fractional-second precision cannot
 *       change the hash.</li>
 * </ul>
 */
public final class RollHasher {

    private RollHasher() {
    }

    /** Sorts and hashes the given entries into a complete, self-describing roll. */
    public static Roll.DrawRoll freeze(String rollId,
                                       String schemeCode,
                                       String ruleSetVersion,
                                       String ruleSetHash,
                                       Instant frozenAt,
                                       List<Roll.RollEntry> entries) {
        List<Roll.RollEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Roll.RollEntry::applicationId));
        assertNoDuplicateIds(sorted);

        Instant stamp = frozenAt.truncatedTo(ChronoUnit.SECONDS);
        Roll.DrawRoll unhashed = new Roll.DrawRoll(
                rollId, schemeCode, ruleSetVersion, ruleSetHash, stamp, sorted, "");
        String hash = Hashing.sha256Hex(encode(unhashed));
        return new Roll.DrawRoll(rollId, schemeCode, ruleSetVersion, ruleSetHash, stamp, sorted, hash);
    }

    public static String encode(Roll.DrawRoll roll) {
        StringBuilder sb = new StringBuilder();
        sb.append("roll/1\n");
        sb.append("schemeCode=").append(roll.schemeCode()).append('\n');
        sb.append("ruleSetVersion=").append(roll.ruleSetVersion()).append('\n');
        sb.append("ruleSetHash=").append(roll.ruleSetHash()).append('\n');
        sb.append("frozenAt=").append(roll.frozenAt().truncatedTo(ChronoUnit.SECONDS)).append('\n');
        sb.append("entryCount=").append(roll.entries().size()).append('\n');
        for (Roll.RollEntry e : roll.entries()) {
            sb.append("e=").append(e.applicationId()).append('|')
              .append(e.clusterId()).append('|')
              .append(e.verticalCode()).append('|')
              .append(String.join(",", e.horizontalCodes())).append('|')
              .append(e.residencyYears()).append('|')
              .append(String.join(",", e.unitTypePreferences())).append('\n');
        }
        return sb.toString();
    }

    /** Recomputes the hash of a roll and compares it to the stored value. */
    public static boolean verify(Roll.DrawRoll roll) {
        Roll.DrawRoll withoutHash = new Roll.DrawRoll(
                roll.rollId(), roll.schemeCode(), roll.ruleSetVersion(), roll.ruleSetHash(),
                roll.frozenAt(), roll.entries(), "");
        return Hashing.sha256Hex(encode(withoutHash)).equals(roll.rollHash());
    }

    private static void assertNoDuplicateIds(List<Roll.RollEntry> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).applicationId().equals(sorted.get(i - 1).applicationId())) {
                throw new IllegalStateException(
                        "duplicate application id in roll: " + sorted.get(i).applicationId());
            }
        }
    }
}
