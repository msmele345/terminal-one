package com.terminalone.ledger;

import com.terminalone.engine.Direction;
import com.terminalone.engine.RecommendationStatus;
import com.terminalone.engine.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One ledger row (Phase 8 AC2): a recommendation's paper-trade entry with its
 * current status and outcomes. {@code recommendationStatus} is the PAPER/TAKEN
 * promotion state; {@code tradeStatus} is the settlement lifecycle (OPEN/SETTLED).
 */
public record LedgerEntryResponse(
        long id,
        long recommendationId,
        String symbol,
        StrategyType strategy,
        Direction direction,
        int conviction,
        RecommendationStatus recommendationStatus,
        PaperTrade.Status tradeStatus,
        int configVersion,
        LocalDate expiry,
        int contracts,
        BigDecimal entryDebit,
        BigDecimal markDebit,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        Instant openedAt,
        Instant lastMarkedAt,
        Instant closedAt) {

    static LedgerEntryResponse from(PaperTrade trade) {
        return new LedgerEntryResponse(
                trade.getId(),
                trade.getRecommendation().getId(),
                trade.getSymbol(),
                trade.getStrategy(),
                trade.getRecommendation().getDirection(),
                trade.getRecommendation().getConviction(),
                trade.getRecommendation().getStatus(),
                trade.getStatus(),
                trade.getConfigVersion(),
                trade.getExpiry(),
                trade.getContracts(),
                trade.getEntryDebit(),
                trade.getMarkDebit(),
                trade.getUnrealizedPnl(),
                trade.getRealizedPnl(),
                trade.getOpenedAt(),
                trade.getLastMarkedAt(),
                trade.getClosedAt());
    }
}
