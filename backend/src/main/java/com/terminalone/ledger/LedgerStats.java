package com.terminalone.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Aggregate track record over a set of ledger entries (Phase 8 AC2). Wins and
 * losses count settled trades only; {@code hitRate} is null until any trade
 * settles — never a misleading 0.
 */
public record LedgerStats(
        int totalTrades,
        int openTrades,
        int settledTrades,
        int wins,
        int losses,
        Double hitRate,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalPnl) {

    static LedgerStats over(List<LedgerEntryResponse> entries) {
        int settled = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;

        for (LedgerEntryResponse entry : entries) {
            realized = realized.add(entry.realizedPnl());
            unrealized = unrealized.add(entry.unrealizedPnl());
            if (entry.tradeStatus() == PaperTrade.Status.SETTLED) {
                settled++;
                int sign = entry.realizedPnl().signum();
                if (sign > 0) {
                    wins++;
                } else if (sign < 0) {
                    losses++;
                }
            }
        }

        Double hitRate = settled == 0 ? null
                : BigDecimal.valueOf(wins)
                        .divide(BigDecimal.valueOf(settled), 4, RoundingMode.HALF_UP)
                        .doubleValue();
        return new LedgerStats(entries.size(), entries.size() - settled, settled,
                wins, losses, hitRate, realized, unrealized, realized.add(unrealized));
    }
}
