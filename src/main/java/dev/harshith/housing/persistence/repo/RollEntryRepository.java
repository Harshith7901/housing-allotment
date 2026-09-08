package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.RollEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RollEntryRepository extends JpaRepository<RollEntryEntity, String> {

    /**
     * Ordered by application id, because that ordering is part of the canonical roll and
     * therefore of the roll hash. Reading the roll without an ORDER BY would make the
     * recomputed hash depend on the query plan.
     */
    List<RollEntryEntity> findByRollIdOrderByApplicationIdAsc(String rollId);

    long countByRollId(String rollId);
}
