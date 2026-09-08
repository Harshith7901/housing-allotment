package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.SchemeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemeRepository extends JpaRepository<SchemeEntity, String> {
}
