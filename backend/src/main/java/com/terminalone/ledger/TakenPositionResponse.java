package com.terminalone.ledger;

import com.terminalone.engine.Direction;
import com.terminalone.engine.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A taken position as returned by the API (Phase 8 AC3): the real position the
 * recommendation was promoted to, carrying the manually entered fill price.
 */
public record TakenPositionResponse(
        long id,
        long recommendationId,
        String symbol,
        StrategyType strategy,
        Direction direction,
        int configVersion,
        LocalDate expiry,
        int contracts,
        BigDecimal fillPrice,
        Instant takenAt) {

    static TakenPositionResponse from(TakenPosition taken) {
        return new TakenPositionResponse(
                taken.getId(),
                taken.getRecommendation().getId(),
                taken.getSymbol(),
                taken.getStrategy(),
                taken.getDirection(),
                taken.getConfigVersion(),
                taken.getExpiry(),
                taken.getContracts(),
                taken.getFillPrice(),
                taken.getTakenAt());
    }
}
