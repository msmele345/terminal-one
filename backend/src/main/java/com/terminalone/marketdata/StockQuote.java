package com.terminalone.marketdata;

import java.time.Instant;

/**
 * A delayed stock quote from a {@link MarketDataProvider}.
 *
 * @param symbol  the ticker
 * @param bid     best bid
 * @param ask     best ask
 * @param last    last traded price
 * @param asOf    vendor data timestamp (when the quote was sampled)
 * @param delayed true when served from a delayed/cached vendor response (HTTP 203)
 */
public record StockQuote(
        String symbol,
        double bid,
        double ask,
        double last,
        Instant asOf,
        boolean delayed) {
}
