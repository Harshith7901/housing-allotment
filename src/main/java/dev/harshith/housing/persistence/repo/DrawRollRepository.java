package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.DrawRollEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DrawRollRepository extends JpaRepository<DrawRollEntity, String> {

    List<DrawRollEntity> findBySchemeCodeOrderByFrozenAtDesc(String schemeCode);
}
