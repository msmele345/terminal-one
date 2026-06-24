package com.terminalone.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockPositionRepository extends JpaRepository<StockPosition, Long> {

    List<StockPosition> findAllByOrderBySymbolAsc();
}
