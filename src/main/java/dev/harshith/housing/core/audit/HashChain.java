package dev.harshith.housing.core.audit;

import dev.harshith.housing.core.util.Hashing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tamper-evident audit chain.
 *
 * <p>An audit table that anyone with database access can edit is not evidence, it is
 * decoration. Chaining each event's hash into the next makes the log tamper-<em>evident</em>:
 * altering, deleting or reordering any historical event changes its hash, which breaks
 * every hash after it, and rebuilding the chain to hide that requires rewriting every
 * subsequent row.
 *
 * <p>Two honest limits, both worth stating out loud rather than overselling this:
 *
 * <ul>
 *   <li>Chaining is <b>evident</b>, not <b>proof</b>. Somebody with write access to the
 *       whole table can recompute the entire chain. What defeats that is periodically
 *       publishing the head hash somewhere outside the system's control — read out at the
 *       draw, printed in the notification, timestamped by a notary — so that a rewritten
 *       chain no longer matches a value already in the world. The head hash is therefore
 *       exposed on the public endpoint precisely so it can be quoted and archived.</li>
 *   <li>It proves the log was not changed. It does not prove the log is complete: an
 *       action that was never recorded leaves no trace. Completeness comes from recording
 *       every state change through one service and from the dense sequence check below,
 *       not from the hashes.</li>
 * </ul>
 */
public final class HashChain {

    private HashChain() {
    }

    /**
     * The exact bytes covered by an event's hash. Timestamps are truncated to whole
     * seconds so that a round trip through a column with coarser fractional-second
     * precision cannot invalidate a chain that was intact when it was written.
     */
    public static String canonical(long sequence,
                                   Instant occurredAt,
                                   String actor,
                                   String actorRole,
                                   String action,
                                   String entityType,
                                   String entityId,
                                   String payload) {
        return "event/1|" + sequence
                + "|" + occurredAt.truncatedTo(ChronoUnit.SECONDS)
                + "|" + nullSafe(actor)
                + "|" + nullSafe(actorRole)
                + "|" + nullSafe(action)
                + "|" + nullSafe(entityType)
                + "|" + nullSafe(entityId)
                + "|" + nullSafe(payload);
    }

    public static Audit.AuditEvent link(long sequence,
                                        String eventId,
                                        Instant occurredAt,
                                        String actor,
                                        String actorRole,
                                        String action,
                                        String entityType,
                                        String entityId,
                                        String payload,
                                        String previousHash) {
        String previous = previousHash == null || previousHash.isBlank() ? Hashing.GENESIS : previousHash;
        Instant stamp = occurredAt.truncatedTo(ChronoUnit.SECONDS);
        String canonical = canonical(sequence, stamp, actor, actorRole, action, entityType, entityId, payload);
        String hash = Hashing.chain(previous, canonical);
        return new Audit.AuditEvent(sequence, eventId, stamp, actor, actorRole, action,
                entityType, entityId, payload, previous, hash);
    }

    /**
     * Replays the chain from the genesis hash. Checks three things, in this order: that
     * the sequence is dense and gapless, that each event's recorded predecessor is the
     * previous event's hash, and that each event's own hash matches its content.
     *
     * @param events ordered by sequence ascending
     */
    public static Audit.ChainVerification verify(List<Audit.AuditEvent> events) {
        String expectedPrevious = Hashing.GENESIS;
        long expectedSequence = 1;
        long checked = 0;

        for (Audit.AuditEvent e : events) {
            if (e.sequence() != expectedSequence) {
                return new Audit.ChainVerification(false, checked, e.sequence(),
                        "audit sequence jumped: expected " + expectedSequence + " but found " + e.sequence()
                                + ". An event has been deleted or inserted out of order.");
            }
            if (!expectedPrevious.equals(e.previousHash())) {
                return new Audit.ChainVerification(false, checked, e.sequence(),
                        "event " + e.sequence() + " records predecessor " + e.previousHash()
                                + " but the previous event hashes to " + expectedPrevious + ".");
            }
            String recomputed = Hashing.chain(e.previousHash(),
                    canonical(e.sequence(), e.occurredAt(), e.actor(), e.actorRole(),
                            e.action(), e.entityType(), e.entityId(), e.payload()));
            if (!recomputed.equals(e.hash())) {
                return new Audit.ChainVerification(false, checked, e.sequence(),
                        "event " + e.sequence() + " has been altered: its content hashes to " + recomputed
                                + " but the stored hash is " + e.hash() + ".");
            }
            expectedPrevious = e.hash();
            expectedSequence++;
            checked++;
        }
        return new Audit.ChainVerification(true, checked, null,
                checked == 0
                        ? "the audit log is empty"
                        : "all " + checked + " events verify against the genesis hash; head is " + expectedPrevious);
    }

    public static String head(List<Audit.AuditEvent> events) {
        return events.isEmpty() ? Hashing.GENESIS : events.get(events.size() - 1).hash();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
