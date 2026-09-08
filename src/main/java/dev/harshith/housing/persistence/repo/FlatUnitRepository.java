package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.FlatUnitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlatUnitRepository extends JpaRepository<FlatUnitEntity, String> {

    List<FlatUnitEntity> findBySchemeCodeAndWithdrawnFalseOrderByUnitIdAsc(String schemeCode);

    long countBySchemeCodeAndWithdrawnFalse(String schemeCode);
}
