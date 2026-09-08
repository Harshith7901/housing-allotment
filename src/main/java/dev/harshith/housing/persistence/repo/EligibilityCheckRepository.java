package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.EligibilityCheckEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EligibilityCheckRepository extends JpaRepository<EligibilityCheckEntity, String> {

    List<EligibilityCheckEntity> findByApplicationIdOrderByDecidedAtAsc(String applicationId);
}
