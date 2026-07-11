package com.terminalone.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the paper-trade ledger (Phase 8 AC2, FR-22): lists every
 * recommendation's ledger entry with status + outcomes.
 */
@Service
public class LedgerService {

    private final PaperTradeRepository paperTrades;

    public LedgerService(PaperTradeRepository paperTrades) {
        this.paperTrades = paperTrades;
    }

    /** @param configVersion restrict entries + stats to one producing config version; null = all. */
    @Transactional(readOnly = true)
    public LedgerResponse ledger(Integer configVersion) {
        var trades = configVersion == null
                ? paperTrades.findAllByOrderByOpenedAtDesc()
                : paperTrades.findByConfigVersionOrderByOpenedAtDesc(configVersion);
        var entries = trades.stream()
                .map(LedgerEntryResponse::from)
                .toList();
        return new LedgerResponse(entries, LedgerStats.over(entries),
                configVersion, paperTrades.findDistinctConfigVersions());
    }
}
