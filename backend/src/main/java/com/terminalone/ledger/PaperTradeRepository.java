package com.terminalone.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PaperTradeRepository extends JpaRepository<PaperTrade, Long> {

    Optional<PaperTrade> findByRecommendation_Id(Long recommendationId);

    List<PaperTrade> findByStatusOrderByOpenedAtAsc(PaperTrade.Status status);

    List<PaperTrade> findAllByOrderByOpenedAtDesc();

    List<PaperTrade> findByConfigVersionOrderByOpenedAtDesc(int configVersion);

    @Query("select distinct t.configVersion from PaperTrade t order by t.configVersion")
    List<Integer> findDistinctConfigVersions();
}
