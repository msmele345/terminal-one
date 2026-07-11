package com.terminalone.portfolio;

import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.PriceHistory;
import com.terminalone.marketdata.StockQuote;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.OptionSummary;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.PortfolioSummary;
import com.terminalone.portfolio.dto.PortfolioSummaryResponses.StockSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pricing + roll-up behaviour of {@link PortfolioSummaryService}, exercised with
 * in-memory fakes for the two seams (positions + market data). Covers happy-path
 * P&amp;L, the option chain match, and the no-data/exception paths that must
 * degrade to an unpriced row rather than crash the whole summary.
 */
class PortfolioSummaryServiceTest {

    private static final Instant T = Instant.parse("2026-06-26T20:00:00Z");

    @Test
    void pricesStockAndRollsUpTotals() {
        var positions = positions(
                List.of(stock("AAPL", "100", "150")),
                List.of());
        var market = market()
                .quote("AAPL", 165.0, true, T);

        PortfolioSummary summary = service(positions, market).summarize();

        assertThat(summary.stocks()).hasSize(1);
        StockSummary aapl = summary.stocks().get(0);
        assertThat(aapl.priced()).isTrue();
        assertThat(aapl.markPrice()).isEqualTo(165.0);
        assertThat(aapl.marketValue()).isEqualTo(16_500.0);
        assertThat(aapl.unrealizedPnl()).isEqualTo(1_500.0);
        assertThat(aapl.unrealizedPnlPct()).isEqualTo(10.0);

        assertThat(summary.totals().marketValue()).isEqualTo(16_500.0);
        assertThat(summary.totals().unrealizedPnl()).isEqualTo(1_500.0);
        assertThat(summary.totals().unrealizedPnlPct()).isEqualTo(10.0);
        assertThat(summary.delayed()).isTrue();
        assertThat(summary.asOf()).isEqualTo(T);
        assertThat(summary.unpriced()).isZero();
    }

    @Test
    void pricesOptionByMatchingChainContract() {
        var pos = optionLeg("TSLA", OptionType.CALL, "200", LocalDate.of(2026, 9, 18), "2", "5.00", PositionSide.LONG);
        var positions = positions(List.of(), List.of(pos));
        // Chain has a decoy strike + the matching one (mid = (6.0+7.0)/2 = 6.5).
        var market = market().chain("TSLA", 205.0, false, T, List.of(
                new OptionContract("TSLA260918C00190000", CallPut.CALL, 190.0, LocalDate.of(2026, 9, 18), 12.0, 13.0, 100),
                new OptionContract("TSLA260918C00200000", CallPut.CALL, 200.0, LocalDate.of(2026, 9, 18), 6.0, 7.0, 500)));

        PortfolioSummary summary = service(positions, market).summarize();

        assertThat(summary.options()).hasSize(1);
        OptionSummary o = summary.options().get(0);
        assertThat(o.priced()).isTrue();
        assertThat(o.markPrice()).isCloseTo(6.5, offset(1e-9));
        assertThat(o.marketValue()).isCloseTo(1_300.0, offset(1e-9)); // 6.5 * 2 * 100
        assertThat(o.unrealizedPnl()).isCloseTo(300.0, offset(1e-9));  // (6.5-5.0)*2*100
        assertThat(o.unrealizedPnlPct()).isCloseTo(30.0, offset(1e-9));
        assertThat(summary.delayed()).isFalse();
    }

    @Test
    void unpricedWhenNoQuote() {
        var positions = positions(List.of(stock("ZZZZ", "10", "20")), List.of());
        var market = market(); // returns null for everything

        PortfolioSummary summary = service(positions, market).summarize();

        StockSummary row = summary.stocks().get(0);
        assertThat(row.priced()).isFalse();
        assertThat(row.markPrice()).isNull();
        assertThat(row.marketValue()).isNull();
        assertThat(row.unrealizedPnl()).isNull();
        assertThat(row.unrealizedPnlPct()).isNull();
        assertThat(summary.unpriced()).isEqualTo(1);
        assertThat(summary.totals().marketValue()).isZero();
    }

    @Test
    void unpricedWhenChainHasNoMatchingContract() {
        var pos = optionLeg("TSLA", OptionType.CALL, "999", LocalDate.of(2026, 9, 18), "1", "5.00", PositionSide.LONG);
        var positions = positions(List.of(), List.of(pos));
        var market = market().chain("TSLA", 205.0, false, T, List.of(
                new OptionContract("TSLA260918C00200000", CallPut.CALL, 200.0, LocalDate.of(2026, 9, 18), 6.0, 7.0, 500)));

        PortfolioSummary summary = service(positions, market).summarize();

        assertThat(summary.options().get(0).priced()).isFalse();
        assertThat(summary.unpriced()).isEqualTo(1);
    }

    @Test
    void providerExceptionDegradesToUnpricedRow() {
        var positions = positions(List.of(stock("BOOM", "5", "10")), List.of());
        MarketDataProvider exploding = new MarketDataProvider() {
            @Override
            public OptionChain getChain(String symbol) {
                throw new RuntimeException("vendor down");
            }

            @Override
            public StockQuote getQuote(String symbol) {
                throw new RuntimeException("vendor down");
            }

            @Override
            public PriceHistory getDailyBars(String symbol) {
                throw new RuntimeException("vendor down");
            }
        };

        PortfolioSummary summary = new PortfolioSummaryService(positions, exploding).summarize();

        assertThat(summary.stocks().get(0).priced()).isFalse();
        assertThat(summary.unpriced()).isEqualTo(1);
    }

    // ---- fakes & builders ----

    private static PortfolioSummaryService service(PositionSource positions, FakeMarket market) {
        return new PortfolioSummaryService(positions, market);
    }

    private static StockPosition stock(String symbol, String qty, String cost) {
        return new StockPosition(symbol, new BigDecimal(qty), new BigDecimal(cost), LocalDate.of(2026, 1, 1));
    }

    private static OptionPosition optionLeg(String underlying, OptionType type, String strike, LocalDate expiry,
                                            String qty, String premium, PositionSide side) {
        return new OptionPosition(underlying, type, new BigDecimal(strike), expiry,
                new BigDecimal(qty), new BigDecimal(premium), side, null);
    }

    private static PositionSource positions(List<StockPosition> stocks, List<OptionPosition> options) {
        return new PositionSource() {
            @Override
            public List<StockPosition> listStocks() {
                return stocks;
            }

            @Override
            public List<OptionPosition> listOptions() {
                return options;
            }

            @Override
            public StockPosition addStock(StockPosition position) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OptionPosition addOption(OptionPosition position) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StockPosition updateStock(long id, StockPosition patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OptionPosition updateOption(long id, OptionPosition patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteStock(long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteOption(long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImportResult importCsv(String csv) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static FakeMarket market() {
        return new FakeMarket();
    }

    /** A tiny in-memory MarketDataProvider returning preconfigured quotes/chains, null otherwise. */
    private static final class FakeMarket implements MarketDataProvider {
        private final java.util.Map<String, StockQuote> quotes = new java.util.HashMap<>();
        private final java.util.Map<String, OptionChain> chains = new java.util.HashMap<>();

        FakeMarket quote(String symbol, double last, boolean delayed, Instant asOf) {
            quotes.put(symbol, new StockQuote(symbol, last - 0.05, last + 0.05, last, asOf, delayed));
            return this;
        }

        FakeMarket chain(String symbol, double underlyingPrice, boolean delayed, Instant asOf, List<OptionContract> contracts) {
            chains.put(symbol, new OptionChain(symbol, underlyingPrice, asOf, delayed, contracts));
            return this;
        }

        @Override
        public OptionChain getChain(String symbol) {
            return chains.get(symbol);
        }

        @Override
        public StockQuote getQuote(String symbol) {
            return quotes.get(symbol);
        }

        @Override
        public PriceHistory getDailyBars(String symbol) {
            return new PriceHistory(symbol, Instant.EPOCH, false, List.of());
        }
    }
}
