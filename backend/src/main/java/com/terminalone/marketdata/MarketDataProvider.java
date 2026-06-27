package com.terminalone.marketdata;

/**
 * The market-data seam (PRD D3, FR-6). V1 is a MarketData.app adapter returning
 * delayed quotes + option chains (chains+quotes only; greeks/IV are computed
 * in-house by {@link OptionAnalytics}, D22). Real-time/broker providers are V2.
 */
public interface MarketDataProvider {

    /** Delayed option chain (strikes/expiries/bid-ask/OI) for {@code symbol}. */
    OptionChain getChain(String symbol);

    /** Delayed stock quote for {@code symbol}. */
    StockQuote getQuote(String symbol);
}
