package com.terminalone.marketdata;

import java.time.LocalDate;

/**
 * One point of the accumulated ATM IV series for the observability endpoint
 * (Phase 3 AC5) — a flat view over {@link IvHistory} for the thin client.
 */
public record IvHistoryPoint(
        String symbol,
        LocalDate asOfDate,
        double atmIv,
        double underlyingPrice,
        double atmStrike,
        LocalDate expiration) {

    static IvHistoryPoint from(IvHistory h) {
        return new IvHistoryPoint(h.getSymbol(), h.getAsOfDate(), h.getAtmIv(),
                h.getUnderlyingPrice(), h.getAtmStrike(), h.getExpiration());
    }
}
