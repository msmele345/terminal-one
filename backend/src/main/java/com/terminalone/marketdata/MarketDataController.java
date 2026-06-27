package com.terminalone.marketdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Delayed market-data API (FR-6). Both routes are JWT-protected and served from
 * the read-through cache (FR-7) — the underlying {@link MarketDataProvider} only
 * hits the vendor on a cache miss/expiry.
 */
@RestController
@RequestMapping("/api/marketdata")
public class MarketDataController {

    private final MarketDataProvider provider;

    public MarketDataController(MarketDataProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/quote/{symbol}")
    public StockQuote quote(@PathVariable String symbol) {
        return provider.getQuote(normalize(symbol));
    }

    @GetMapping("/chain/{symbol}")
    public OptionChain chain(@PathVariable String symbol) {
        return provider.getChain(normalize(symbol));
    }

    private static String normalize(String symbol) {
        return symbol.toUpperCase(Locale.ROOT);
    }
}
