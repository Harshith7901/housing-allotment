package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.DrawEntity;
import dev.harshith.housing.persistence.DrawStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DrawRepository extends JpaRepository<DrawEntity, String> {

    List<DrawEntity> findBySchemeCodeOrderByCommittedAtDesc(String schemeCode);

    List<DrawEntity> findBySchemeCodeAndStatus(String schemeCode, DrawStatus status);

    List<DrawEntity> findByRollId(String rollId);
}
