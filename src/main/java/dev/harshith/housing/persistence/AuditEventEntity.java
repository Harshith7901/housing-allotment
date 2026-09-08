package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One link in the audit chain.
 *
 * <p>The sequence number is the primary key rather than a surrogate id, and it is assigned
 * by the application under a lock rather than by an auto-increment column. Two reasons:
 * an auto-increment can leave gaps when a transaction rolls back, and a gap in a chain is
 * indistinguishable from a deletion; and the hash of each event depends on its sequence
 * number, so the number has to be known before the row is written, not after.
 *
 * <p>In production this table should also be append-only at the privilege level — a
 * database role with INSERT and SELECT and no UPDATE or DELETE — and the head hash should
 * be exported somewhere outside this system's control. Neither is done here, and both are
 * listed in the README as omissions, because claiming tamper-proofing that is only
 * tamper-evidence would be the wrong kind of confidence in exactly the wrong place.
 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "ix_audit_entity", columnList = "entityType,entityId"),
        @Index(name = "ix_audit_actor", columnList = "actor"),
        @Index(name = "ix_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditEventEntity {

    /**
     * Named {@code event_seq} rather than {@code sequence} because SEQUENCE is a reserved
     * word in several engines, H2 among them, and a column name that needs quoting in DDL
     * but not in generated queries is a portability trap.
     */
    @Id
    @Column(name = "event_seq")
    private long sequence;

    @Column(nullable = false, length = 64, unique = true)
    private String eventId;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 32)
    private String actorRole;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 32)
    private String entityType;

    @Column(nullable = false, length = 100)
    private String entityId;

    @Column(length = 200_000)
    private String payload;

    /**
     * Unique: each hash can be the predecessor of at most one event. This turns a forked
     * chain — the failure mode of two concurrent appends — into a constraint violation
     * that rolls the second one back, rather than a silently broken log.
     */
    @Column(nullable = false, length = 64, unique = true)
    private String previousHash;

    @Column(nullable = false, length = 64)
    private String hash;
}
