package com.terminalone.marketdata;

import java.time.LocalDate;

/**
 * One option contract row from a vendor chain (quotes only; greeks/IV are
 * derived in-house by {@link OptionAnalytics}, D22).
 *
 * @param optionSymbol OCC symbol, e.g. {@code AAPL260116C00190000}
 * @param callPut      call or put
 * @param strike       strike price
 * @param expiration   expiration date
 * @param bid          best bid
 * @param ask          best ask
 * @param openInterest open interest
 */
public record OptionContract(
        String optionSymbol,
        CallPut callPut,
        double strike,
        LocalDate expiration,
        double bid,
        double ask,
        int openInterest) {

    /** The bid/ask mid — the canonical input to {@link OptionAnalytics#impliedVolatilityFromQuote}. */
    public double mid() {
        return 0.5 * (bid + ask);
    }
}
