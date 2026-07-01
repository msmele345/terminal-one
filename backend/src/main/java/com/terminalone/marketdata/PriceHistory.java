package com.terminalone.marketdata;

import java.time.Instant;
import java.util.List;

/**
 * Daily price history for one symbol (FR-8), served behind {@link MarketDataProvider}
 * and cached like quotes/chains (FR-7).
 *
 * @param symbol  the ticker
 * @param asOf    vendor data timestamp of the most recent bar
 * @param delayed true when served from a delayed/cached vendor response (HTTP 203)
 * @param bars    daily OHLCV bars, oldest first (may be empty when the vendor returns no data)
 */
public record PriceHistory(
        String symbol,
        Instant asOf,
        boolean delayed,
        List<PriceBar> bars) {
}
