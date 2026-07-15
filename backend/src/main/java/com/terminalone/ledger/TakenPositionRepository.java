package com.terminalone.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TakenPositionRepository extends JpaRepository<TakenPosition, Long> {

    Optional<TakenPosition> findByRecommendation_Id(Long recommendationId);

    List<TakenPosition> findAllByOrderByTakenAtDesc();
}
