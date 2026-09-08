package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.ApplicationEntity;
import dev.harshith.housing.persistence.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, String> {

    Optional<ApplicationEntity> findByIdempotencyKey(String idempotencyKey);

    List<ApplicationEntity> findBySchemeCodeOrderByApplicationIdAsc(String schemeCode);

    List<ApplicationEntity> findBySchemeCodeAndStatusOrderByApplicationIdAsc(String schemeCode,
                                                                             ApplicationStatus status);

    List<ApplicationEntity> findByClusterIdOrderByApplicationIdAsc(String clusterId);

    long countBySchemeCode(String schemeCode);

    long countBySchemeCodeAndStatus(String schemeCode, ApplicationStatus status);
}
