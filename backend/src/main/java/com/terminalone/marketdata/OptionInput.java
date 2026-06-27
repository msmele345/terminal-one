package com.terminalone.marketdata;

/**
 * Inputs to the Black-Scholes model. Time is in years, rates are annualised
 * and continuous. {@code volatility} is only required for {@link #value};
 * {@link OptionAnalytics#impliedVolatility} ignores it.
 *
 * @param underlying   spot price S
 * @param strike       strike K
 * @param timeToExpiry T, in years (e.g. DTE/365)
 * @param riskFreeRate r, annualised continuous
 * @param dividendYield q, annualised continuous (0 for a non-dividend stock)
 * @param volatility   sigma, annualised (only used by {@link #value})
 * @param callPut      call or put
 */
public record OptionInput(
        double underlying,
        double strike,
        double timeToExpiry,
        double riskFreeRate,
        double dividendYield,
        double volatility,
        CallPut callPut) {

    /** True if the inputs are degenerate (zero time or non-positive spot/strike). */
    boolean isDegenerate() {
        return timeToExpiry <= 0.0 || underlying <= 0.0 || strike <= 0.0;
    }
}