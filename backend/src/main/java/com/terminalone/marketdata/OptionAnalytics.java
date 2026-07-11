package com.terminalone.marketdata;

/**
 * The option-pricing + greeks seam (PRD D22, FR-8a). The engine derives IV
 * (inverted from the bid/ask mid) and greeks in-house, independent of any
 * vendor-supplied greeks. V1 wires a Black-Scholes implementation.
 */
public interface OptionAnalytics {

    /**
     * Price an option and compute its greeks for the given inputs.
     *
     * @param input fully-specified option inputs (volatility required)
     * @return the price + IV-independent greeks at {@code input.volatility()}
     */
    OptionValuation value(OptionInput input);

    /**
     * Invert the Black-Scholes model to find the implied volatility that
     * reproduces {@code marketPrice} for {@code input} (which itself is
     * re-priced at the solved vol). Returns {@code Double.NaN} if the price
     * is outside the achievable BS range (arbitrage bounds) for these inputs.
     *
     * @param input  option inputs (the {@code volatility} field is ignored)
     * @param marketPrice the observed option price (typically a bid/ask mid)
     * @return implied volatility, annualised, or {@code NaN} if not solvable
     */
    double impliedVolatility(OptionInput input, double marketPrice);

    /**
     * Convenience for the canonical D22 input: invert IV from the bid/ask
     * <em>mid</em>. Returns {@code NaN} for a crossed/empty quote (ask &lt; bid,
     * or either side non-positive).
     *
     * @param input option inputs (the {@code volatility} field is ignored)
     * @param bid   the option bid
     * @param ask   the option ask
     * @return implied volatility from the mid, or {@code NaN} if not solvable
     */
    default double impliedVolatilityFromQuote(OptionInput input, double bid, double ask) {
        if (bid <= 0.0 || ask <= 0.0 || ask < bid) {
            return Double.NaN;
        }
        return impliedVolatility(input, 0.5 * (bid + ask));
    }
}