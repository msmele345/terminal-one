package com.terminalone.marketdata;

import java.time.Instant;
import java.util.List;

/**
 * A delayed option chain for one underlying.
 *
 * @param underlying      the underlying ticker
 * @param underlyingPrice the underlying's price at sample time
 * @param asOf            vendor data timestamp
 * @param delayed         true when served from a delayed/cached vendor response (HTTP 203)
 * @param contracts       the chain rows (may be empty when the vendor returns no data)
 */
public record OptionChain(
        String underlying,
        double underlyingPrice,
        Instant asOf,
        boolean delayed,
        List<OptionContract> contracts) {
}
