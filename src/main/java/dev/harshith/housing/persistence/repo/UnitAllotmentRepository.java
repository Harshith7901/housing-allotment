package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.AllotmentStatus;
import dev.harshith.housing.persistence.UnitAllotmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnitAllotmentRepository extends JpaRepository<UnitAllotmentEntity, String> {

    List<UnitAllotmentEntity> findByDrawIdOrderByPickOrderAsc(String drawId);

    List<UnitAllotmentEntity> findByDrawIdAndStatusInOrderByPickOrderAsc(String drawId,
                                                                         List<AllotmentStatus> statuses);

    List<UnitAllotmentEntity> findByApplicationIdOrderByOfferedAtAsc(String applicationId);

    List<UnitAllotmentEntity> findByDrawIdAndUnitId(String drawId, String unitId);

    long countByDrawId(String drawId);
}
