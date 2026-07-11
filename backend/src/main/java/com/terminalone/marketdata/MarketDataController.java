package com.terminalone.marketdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Delayed market-data API (FR-6). All routes are JWT-protected and served from
 * the read-through cache (FR-7) — the underlying {@link MarketDataProvider} only
 * hits the vendor on a cache miss/expiry. Also exposes the accumulated ATM IV
 * series (Phase 3 AC5) so backfill/accumulation is observable.
 */
@RestController
@RequestMapping("/api/marketdata")
public class MarketDataController {

    private final MarketDataProvider provider;
    private final IvHistoryRepository ivHistory;
    private final AtmIvRecorder ivRecorder;

    public MarketDataController(MarketDataProvider provider, IvHistoryRepository ivHistory,
                                AtmIvRecorder ivRecorder) {
        this.provider = provider;
        this.ivHistory = ivHistory;
        this.ivRecorder = ivRecorder;
    }

    @GetMapping("/quote/{symbol}")
    public StockQuote quote(@PathVariable String symbol) {
        return provider.getQuote(normalize(symbol));
    }

    @GetMapping("/chain/{symbol}")
    public OptionChain chain(@PathVariable String symbol) {
        return provider.getChain(normalize(symbol));
    }

    @GetMapping("/history/{symbol}")
    public PriceHistory history(@PathVariable String symbol) {
        return provider.getDailyBars(normalize(symbol));
    }

    /** The accumulated daily ATM IV series for a symbol, oldest first (AC5 observability). */
    @GetMapping("/iv-history/{symbol}")
    public List<IvHistoryPoint> ivHistory(@PathVariable String symbol) {
        return ivHistory.findBySymbolOrderByAsOfDateAsc(normalize(symbol)).stream()
                .map(IvHistoryPoint::from)
                .toList();
    }

    /** On-demand trigger of the daily ATM IV accumulation (also runs post-close on a schedule). */
    @PostMapping("/iv-history/run")
    public AtmIvRecorder.RecordResult runIvHistory() {
        return ivRecorder.recordDailyAtmIv();
    }

    private static String normalize(String symbol) {
        return symbol.toUpperCase(Locale.ROOT);
    }
}
