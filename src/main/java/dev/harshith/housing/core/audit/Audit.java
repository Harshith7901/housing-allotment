package dev.harshith.housing.core.audit;

import java.time.Instant;
import java.util.List;

/** Value objects for the append-only audit chain. */
public final class Audit {

    private Audit() {
    }

    /**
     * One recorded act. Never updated, never deleted.
     *
     * @param sequence     dense, gapless, starting at 1 — a gap is itself evidence
     * @param previousHash hash of event {@code sequence - 1}, or 64 zeros for the first
     * @param hash         {@code SHA256(previousHash + "|" + canonical form of this event)}
     */
    public record AuditEvent(
            long sequence,
            String eventId,
            Instant occurredAt,
            String actor,
            String actorRole,
            String action,
            String entityType,
            String entityId,
            String payload,
            String previousHash,
            String hash
    ) {
    }

    /**
     * @param firstBrokenSequence the sequence number at which the chain stops verifying,
     *                            or null when it is intact end to end
     */
    public record ChainVerification(
            boolean intact,
            long eventsChecked,
            Long firstBrokenSequence,
            String detail
    ) {
    }

    public record ChainSummary(long eventCount, String headHash, List<String> recentActions) {
        public ChainSummary {
            recentActions = List.copyOf(recentActions == null ? List.of() : recentActions);
        }
    }
}
