package dev.harshith.housing.persistence.repo;

import dev.harshith.housing.persistence.DuplicateLinkEntity;
import dev.harshith.housing.persistence.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DuplicateLinkRepository extends JpaRepository<DuplicateLinkEntity, String> {

    List<DuplicateLinkEntity> findBySchemeCodeOrderByScoreDesc(String schemeCode);

    List<DuplicateLinkEntity> findBySchemeCodeAndReviewDecisionOrderByScoreDesc(String schemeCode,
                                                                                ReviewDecision reviewDecision);

    long countBySchemeCodeAndReviewDecision(String schemeCode, ReviewDecision reviewDecision);

    List<DuplicateLinkEntity> findByLeftApplicationIdOrRightApplicationId(String left, String right);
}
