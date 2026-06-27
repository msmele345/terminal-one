package com.terminalone.portfolio;

import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.StockQuote;
import com.terminalone.portfolio.PositionPnl.Valuation;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.OptionSummary;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.PortfolioSummary;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.StockSummary;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.Totals;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices the manual portfolio with delayed market data and computes per-position
 * + portfolio-level P&amp;L (FR-5). Reads positions through {@link PositionSource}
 * (D2) and marks them via {@link MarketDataProvider} (D3): stocks against the
 * last/mid quote, option legs against the matching chain contract's bid/ask mid.
 *
 * <p>Resilience: a vendor call that fails or returns no usable mark degrades that
 * one position to an <em>unpriced</em> row (null money fields) — it never zeroes
 * it out or fails the whole summary. Quotes/chains are fetched at most once per
 * symbol per call.
 */
@Service
public class PortfolioSummaryService {

    private final PositionSource positions;
    private final MarketDataProvider marketData;

    public PortfolioSummaryService(PositionSource positions, MarketDataProvider marketData) {
        this.positions = positions;
        this.marketData = marketData;
    }

    public PortfolioSummary summarize() {
        Marks marks = new Marks();
        Accumulator totals = new Accumulator();

        List<StockSummary> stocks = positions.listStocks().stream()
                .map(s -> priceStock(s, marks, totals))
                .toList();
        List<OptionSummary> options = positions.listOptions().stream()
                .map(o -> priceOption(o, marks, totals))
                .toList();

        return new PortfolioSummary(stocks, options, totals.toTotals(), marks.delayed, marks.asOf, totals.unpriced);
    }

    private StockSummary priceStock(StockPosition s, Marks marks, Accumulator totals) {
        StockQuote quote = marks.quote(s.getSymbol(), marketData);
        Double mark = quote == null ? null : usableMark(quote);
        if (mark == null) {
            totals.unpriced++;
            return new StockSummary(s.getId(), "STOCK", s.getSymbol(), s.getQuantity(), s.getCostBasis(),
                    s.getOpenedDate(), null, null, null, null, false);
        }
        Valuation v = PositionPnl.forStock(s.getQuantity(), s.getCostBasis(), mark);
        totals.add(v);
        marks.observe(quote.delayed(), quote.asOf());
        return new StockSummary(s.getId(), "STOCK", s.getSymbol(), s.getQuantity(), s.getCostBasis(),
                s.getOpenedDate(), mark, v.marketValue(), v.unrealizedPnl(), v.pnlPct(), true);
    }

    private OptionSummary priceOption(OptionPosition o, Marks marks, Accumulator totals) {
        OptionChain chain = marks.chain(o.getUnderlying(), marketData);
        OptionContract match = matchContract(chain, o);
        Double mark = match == null || match.mid() <= 0.0 ? null : match.mid();
        if (mark == null) {
            totals.unpriced++;
            return new OptionSummary(o.getId(), "OPTION", o.getUnderlying(), o.getOptionType().name(),
                    o.getStrike(), o.getExpiry(), o.getQuantity(), o.getCostBasis(), o.getSide().name(),
                    o.getOpenedDate(), null, null, null, null, false);
        }
        Valuation v = PositionPnl.forOption(o.getQuantity(), o.getCostBasis(), o.getSide(), mark);
        totals.add(v);
        marks.observe(chain.delayed(), chain.asOf());
        return new OptionSummary(o.getId(), "OPTION", o.getUnderlying(), o.getOptionType().name(),
                o.getStrike(), o.getExpiry(), o.getQuantity(), o.getCostBasis(), o.getSide().name(),
                o.getOpenedDate(), mark, v.marketValue(), v.unrealizedPnl(), v.pnlPct(), true);
    }

    /** Prefer the last trade; fall back to the bid/ask mid. Returns null if neither is positive. */
    private static Double usableMark(StockQuote q) {
        if (q.last() > 0.0) {
            return q.last();
        }
        double mid = 0.5 * (q.bid() + q.ask());
        return mid > 0.0 ? mid : null;
    }

    /** Match a held leg to its chain row by type + strike + expiry (strikes compared with a small epsilon). */
    private static OptionContract matchContract(OptionChain chain, OptionPosition o) {
        if (chain == null) {
            return null;
        }
        double strike = o.getStrike().doubleValue();
        String type = o.getOptionType().name();
        return chain.contracts().stream()
                .filter(c -> c.callPut().name().equals(type))
                .filter(c -> Math.abs(c.strike() - strike) < 1e-6)
                .filter(c -> c.expiration().equals(o.getExpiry()))
                .findFirst()
                .orElse(null);
    }

    /** Per-call memo of vendor fetches + running staleness flags. */
    private static final class Marks {
        private final Map<String, StockQuote> quotes = new HashMap<>();
        private final Map<String, OptionChain> chains = new HashMap<>();
        private boolean delayed;
        private Instant asOf;

        StockQuote quote(String symbol, MarketDataProvider provider) {
            return quotes.computeIfAbsent(symbol, s -> safe(() -> provider.getQuote(s)));
        }

        OptionChain chain(String underlying, MarketDataProvider provider) {
            return chains.computeIfAbsent(underlying, u -> safe(() -> provider.getChain(u)));
        }

        void observe(boolean wasDelayed, Instant sourceAsOf) {
            delayed |= wasDelayed;
            if (sourceAsOf != null && (asOf == null || sourceAsOf.isBefore(asOf))) {
                asOf = sourceAsOf;
            }
        }

        private static <T> T safe(java.util.function.Supplier<T> call) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Sums signed market/cost values + absolute basis to derive portfolio totals. */
    private static final class Accumulator {
        private double marketValue;
        private double costValue;
        private double pnl;
        private double basis;
        private int unpriced;

        void add(Valuation v) {
            marketValue += v.marketValue();
            costValue += v.costValue();
            pnl += v.unrealizedPnl();
            basis += v.basis();
        }

        Totals toTotals() {
            Double pct = basis > 0.0 ? pnl / basis * 100.0 : null;
            return new Totals(costValue, marketValue, pnl, pct);
        }
    }
}
