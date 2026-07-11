package com.terminalone.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Spring Data repository over the accumulated ATM IV series ({@link IvHistory}). */
public interface IvHistoryRepository extends JpaRepository<IvHistory, Long> {

    /** The one reading (if any) for a symbol on a given day — the upsert lookup. */
    Optional<IvHistory> findBySymbolAndAsOfDate(String symbol, LocalDate asOfDate);

    /** The full accumulated series for a symbol, oldest first (for observability). */
    List<IvHistory> findBySymbolOrderByAsOfDateAsc(String symbol);
}
