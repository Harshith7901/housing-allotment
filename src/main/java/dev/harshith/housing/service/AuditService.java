package dev.harshith.housing.service;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.core.audit.Audit;
import dev.harshith.housing.core.audit.HashChain;
import dev.harshith.housing.core.util.Hashing;
import dev.harshith.housing.persistence.AuditEventEntity;
import dev.harshith.housing.persistence.repo.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The only way anything gets written to the audit log.
 *
 * <h2>Why appends are serialised</h2>
 * Each event's hash covers its own sequence number and its predecessor's hash, so two
 * concurrent appends that both read the same head would produce two events claiming the
 * same sequence and the same predecessor — a broken chain, written by entirely correct
 * code. Appends are therefore serialised.
 *
 * <p>The serialisation here is an in-process lock, which is correct for a single instance
 * and <b>not</b> correct behind a load balancer. The production form is a row lock —
 * {@code SELECT ... FOR UPDATE} on a chain-head row, or an advisory lock — taken inside
 * the same transaction as the insert. That is a real limitation and it is listed in the
 * README rather than buried here; the mitigations meanwhile are that the schema has a
 * unique index on {@code previous_hash}, so a forked chain is a constraint violation
 * rather than silent damage, and that the verifier detects the damage in any case.
 *
 * <p>A {@link ReentrantLock} rather than {@code synchronized}: this application runs on
 * virtual threads, and a virtual thread that blocks on JDBC inside a {@code synchronized}
 * block pins its carrier thread, so concurrent appends would consume the (CPU-count-sized)
 * carrier pool instead of parking cheaply. Virtual threads park on a {@code ReentrantLock}
 * without pinning.
 *
 * <p>Appends deliberately join the caller's transaction. If the act being recorded rolls
 * back, its audit entry must roll back with it: a log claiming a roll was frozen when the
 * freeze failed is worse than no log.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;
    private final ReentrantLock appendLock = new ReentrantLock();

    public AuditService(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Audit.AuditEvent append(Actor actor,
                                   String action,
                                   String entityType,
                                   String entityId,
                                   String payload) {
        appendLock.lock();
        try {
            AuditEventEntity previous = repository.findFirstByOrderBySequenceDesc().orElse(null);
            long sequence = previous == null ? 1 : previous.getSequence() + 1;
            String previousHash = previous == null ? Hashing.GENESIS : previous.getHash();

            Audit.AuditEvent event = HashChain.link(
                    sequence,
                    "EVT-" + UUID.randomUUID(),
                    clock.instant(),
                    actor.id(),
                    actor.role().name(),
                    action,
                    entityType,
                    entityId,
                    payload,
                    previousHash);

            AuditEventEntity row = new AuditEventEntity();
            row.setSequence(event.sequence());
            row.setEventId(event.eventId());
            row.setOccurredAt(event.occurredAt());
            row.setActor(event.actor());
            row.setActorRole(event.actorRole());
            row.setAction(event.action());
            row.setEntityType(event.entityType());
            row.setEntityId(event.entityId());
            row.setPayload(event.payload());
            row.setPreviousHash(event.previousHash());
            row.setHash(event.hash());
            repository.save(row);
            return event;
        } finally {
            appendLock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public Audit.ChainVerification verifyChain() {
        return HashChain.verify(toCore(repository.findAllByOrderBySequenceAsc()));
    }

    @Transactional(readOnly = true)
    public String headHash() {
        return repository.findFirstByOrderBySequenceDesc()
                .map(AuditEventEntity::getHash)
                .orElse(Hashing.GENESIS);
    }

    @Transactional(readOnly = true)
    public long eventCount() {
        return repository.count();
    }

    @Transactional(readOnly = true)
    public List<Audit.AuditEvent> forEntity(String entityType, String entityId) {
        return toCore(repository.findByEntityTypeAndEntityIdOrderBySequenceAsc(entityType, entityId));
    }

    @Transactional(readOnly = true)
    public List<Audit.AuditEvent> range(long from, long to) {
        return toCore(repository
                .findBySequenceGreaterThanEqualAndSequenceLessThanEqualOrderBySequenceAsc(from, to));
    }

    private List<Audit.AuditEvent> toCore(List<AuditEventEntity> rows) {
        List<Audit.AuditEvent> out = new ArrayList<>(rows.size());
        for (AuditEventEntity r : rows) {
            out.add(new Audit.AuditEvent(r.getSequence(), r.getEventId(), r.getOccurredAt(),
                    r.getActor(), r.getActorRole(), r.getAction(), r.getEntityType(), r.getEntityId(),
                    r.getPayload(), r.getPreviousHash(), r.getHash()));
        }
        return out;
    }
}
