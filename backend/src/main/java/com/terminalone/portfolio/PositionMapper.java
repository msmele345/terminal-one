package com.terminalone.portfolio;

import com.terminalone.portfolio.dto.PositionRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maps the unified {@link PositionRequest} onto domain entities, validating the
 * fields required per kind. Missing/invalid fields raise {@link IllegalArgumentException},
 * surfaced as HTTP 400 by {@link PortfolioExceptionHandler}.
 */
final class PositionMapper {

    private PositionMapper() {
    }

    static StockPosition toStock(PositionRequest r) {
        String symbol = required(r.symbol(), "symbol");
        BigDecimal quantity = positive(r.quantity(), "quantity");
        BigDecimal costBasis = required(r.costBasis(), "costBasis");
        LocalDate openedDate = required(r.openedDate(), "openedDate");
        return new StockPosition(symbol.strip().toUpperCase(), quantity, costBasis, openedDate);
    }

    static OptionPosition toOption(PositionRequest r) {
        String underlying = required(r.symbol(), "symbol");
        BigDecimal quantity = positive(r.quantity(), "quantity");
        BigDecimal costBasis = required(r.costBasis(), "costBasis");
        OptionType optionType = parseEnum(OptionType.class, r.optionType(), "optionType");
        BigDecimal strike = positive(r.strike(), "strike");
        LocalDate expiry = required(r.expiry(), "expiry");
        PositionSide side = parseEnum(PositionSide.class, r.side(), "side");
        return new OptionPosition(underlying.strip().toUpperCase(), optionType, strike, expiry,
                quantity, costBasis, side, r.openedDate());
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        required(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        required(raw, field);
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is invalid: '" + raw + "'");
        }
    }
}
