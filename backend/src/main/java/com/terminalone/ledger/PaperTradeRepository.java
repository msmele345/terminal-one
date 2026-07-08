package com.terminalone.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperTradeRepository extends JpaRepository<PaperTrade, Long> {

    Optional<PaperTrade> findByRecommendation_Id(Long recommendationId);

    List<PaperTrade> findByStatusOrderByOpenedAtAsc(PaperTrade.Status status);
}
