package com.terminalone.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findFirstByBatchRunIdOrderByConvictionDescScoreDesc(Long batchRunId);
}
