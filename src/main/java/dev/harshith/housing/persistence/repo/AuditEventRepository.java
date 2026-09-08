package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findAllByOrderBySequenceAsc();

    Optional<AuditEventEntity> findFirstByOrderBySequenceDesc();

    List<AuditEventEntity> findByEntityTypeAndEntityIdOrderBySequenceAsc(String entityType, String entityId);

    List<AuditEventEntity> findBySequenceGreaterThanEqualAndSequenceLessThanEqualOrderBySequenceAsc(
            long from, long to);
}
