package com.terminalone.portfolio.dto;

import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.StockPosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response shapes for the portfolio API. Each position carries a {@code kind}
 * discriminator so the thin client can render a unified list and round-trip the
 * right id on update/delete.
 */
public final class PositionResponses {

    private PositionResponses() {
    }

    public record StockPositionResponse(
            Long id,
            String kind,
            String symbol,
            BigDecimal quantity,
            BigDecimal costBasis,
            LocalDate openedDate) {

        public static StockPositionResponse from(StockPosition p) {
            return new StockPositionResponse(p.getId(), "STOCK", p.getSymbol(),
                    p.getQuantity(), p.getCostBasis(), p.getOpenedDate());
        }
    }

    public record OptionPositionResponse(
            Long id,
            String kind,
            String underlying,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            BigDecimal quantity,
            BigDecimal costBasis,
            String side,
            LocalDate openedDate) {

        public static OptionPositionResponse from(OptionPosition p) {
            return new OptionPositionResponse(p.getId(), "OPTION", p.getUnderlying(),
                    p.getOptionType().name(), p.getStrike(), p.getExpiry(), p.getQuantity(),
                    p.getCostBasis(), p.getSide().name(), p.getOpenedDate());
        }
    }

    /** The full console payload: both position kinds, plus an emptiness flag. */
    public record PositionsResponse(
            List<StockPositionResponse> stocks,
            List<OptionPositionResponse> options) {
    }

    /** Outcome of a CSV import: counts of persisted rows + per-row errors. */
    public record ImportResponse(
            int importedStocks,
            int importedOptions,
            List<ImportError> errors) {

        public record ImportError(int line, String message) {
        }
    }
}
