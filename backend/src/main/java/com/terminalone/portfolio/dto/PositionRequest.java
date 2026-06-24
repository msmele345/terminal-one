package com.terminalone.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Unified create/update payload for the {@code /api/portfolio/positions} route.
 * {@code kind} discriminates STOCK vs OPTION; option-only fields are ignored for
 * stock and required for option (validated when mapped to an entity).
 */
public record PositionRequest(
        String kind,
        String symbol,
        BigDecimal quantity,
        BigDecimal costBasis,
        LocalDate openedDate,
        String optionType,
        BigDecimal strike,
        LocalDate expiry,
        String side) {
}
