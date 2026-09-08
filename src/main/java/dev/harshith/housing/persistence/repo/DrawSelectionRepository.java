package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.core.model.Draw;
import dev.harshith.housing.persistence.DrawSelectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DrawSelectionRepository extends JpaRepository<DrawSelectionEntity, String> {

    List<DrawSelectionEntity> findByDrawIdOrderByApplicationIdAsc(String drawId);

    List<DrawSelectionEntity> findByDrawIdAndOutcomeOrderByTicketHexAsc(String drawId, Draw.Outcome outcome);

    List<DrawSelectionEntity> findByDrawIdAndPoolCodeAndOutcomeOrderByWaitlistPositionAsc(
            String drawId, String poolCode, Draw.Outcome outcome);

    Optional<DrawSelectionEntity> findByDrawIdAndApplicationId(String drawId, String applicationId);

    List<DrawSelectionEntity> findByApplicationId(String applicationId);

    long countByDrawIdAndOutcome(String drawId, Draw.Outcome outcome);
}
