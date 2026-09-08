package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.RuleSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, String> {

    List<RuleSetEntity> findBySchemeCodeOrderByPublishedAtDesc(String schemeCode);
}
