package com.terminalone.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MarketData.app columnar-JSON adapter (Phase 3 AC #1). Verifies parsing of the
 * vendor's parallel-array response (status {@code s}, epoch expirations, OCC
 * symbols) and HTTP 200/203/204 handling — exercised through the public
 * {@link MarketDataProvider} interface with a fake transport (no network).
 */
class MarketDataAppProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A fake transport that returns a canned response regardless of path. */
    private MarketDataProvider providerReturning(int status, String body) {
        return new MarketDataAppProvider(path -> new MarketDataClient.Response(status, body), mapper);
    }

    private static final String CHAIN_203 = """
            {
              "s": "ok",
              "optionSymbol": ["AAPL260116C00190000", "AAPL260116P00190000"],
              "underlying": ["AAPL", "AAPL"],
              "underlyingPrice": [192.5, 192.5],
              "side": ["call", "put"],
              "strike": [190.0, 190.0],
              "expiration": [1768597200, 1768597200],
              "bid": [8.1, 5.2],
              "ask": [8.4, 5.5],
              "openInterest": [12000, 9000],
              "updated": [1768510800, 1768510800]
            }
            """;

    @Test
    void parsesADelayedColumnarChain() {
        MarketDataProvider provider = providerReturning(203, CHAIN_203);

        OptionChain chain = provider.getChain("AAPL");

        assertThat(chain.underlying()).isEqualTo("AAPL");
        assertThat(chain.underlyingPrice()).isEqualTo(192.5);
        assertThat(chain.delayed()).isTrue(); // HTTP 203 = delayed
        assertThat(chain.contracts()).hasSize(2);

        OptionContract call = chain.contracts().get(0);
        assertThat(call.optionSymbol()).isEqualTo("AAPL260116C00190000");
        assertThat(call.callPut()).isEqualTo(CallPut.CALL);
        assertThat(call.strike()).isEqualTo(190.0);
        assertThat(call.expiration()).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(call.bid()).isEqualTo(8.1);
        assertThat(call.ask()).isEqualTo(8.4);
        assertThat(call.openInterest()).isEqualTo(12000);
    }

    @Test
    void parsesAStockQuote() {
        String body = """
                {"s":"ok","symbol":["AAPL"],"bid":[192.4],"ask":[192.6],
                 "last":[192.5],"updated":[1768510800]}
                """;
        MarketDataProvider provider = providerReturning(203, body);

        StockQuote quote = provider.getQuote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.bid()).isEqualTo(192.4);
        assertThat(quote.ask()).isEqualTo(192.6);
        assertThat(quote.last()).isEqualTo(192.5);
        assertThat(quote.asOf()).isEqualTo(Instant.ofEpochSecond(1768510800));
        assertThat(quote.delayed()).isTrue();
    }

    @Test
    void marksLiveResponsesAsNotDelayed() {
        MarketDataProvider provider = providerReturning(200, CHAIN_203); // HTTP 200 = live

        assertThat(provider.getChain("AAPL").delayed()).isFalse();
    }

    @Test
    void treatsNoDataAsAnEmptyChainWithoutCrashing() {
        MarketDataProvider provider = providerReturning(204, "{\"s\":\"no_data\"}"); // HTTP 204

        OptionChain chain = provider.getChain("ZZZZ");

        assertThat(chain.contracts()).isEmpty();
        assertThat(chain.delayed()).isFalse();
    }

    @Test
    void treatsAnEmptyBodyAsAnEmptyChainWithoutCrashing() {
        // 204/503 from the transport carry an empty body — must not blow up the parse.
        MarketDataProvider provider = providerReturning(503, "");

        OptionChain chain = provider.getChain("ZZZZ");

        assertThat(chain.contracts()).isEmpty();
    }

    // Daily candle response: columnar parallel arrays t/o/h/l/c/v (Phase 3 AC #4).
    private static final String CANDLES_203 = """
            {
              "s": "ok",
              "t": [1768338000, 1768424400, 1768510800],
              "o": [190.0, 191.5, 193.2],
              "h": [192.0, 193.0, 194.1],
              "l": [189.0, 190.5, 192.0],
              "c": [191.5, 192.8, 193.9],
              "v": [50000000, 48000000, 51000000]
            }
            """;

    @Test
    void parsesDelayedDailyCandlesOldestFirst() {
        MarketDataProvider provider = providerReturning(203, CANDLES_203);

        PriceHistory history = provider.getDailyBars("AAPL");

        assertThat(history.symbol()).isEqualTo("AAPL");
        assertThat(history.delayed()).isTrue(); // HTTP 203 = delayed
        assertThat(history.asOf()).isEqualTo(Instant.ofEpochSecond(1768510800)); // newest bar
        assertThat(history.bars()).hasSize(3);

        PriceBar first = history.bars().get(0);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 1, 13)); // 1768338000 UTC
        assertThat(first.open()).isEqualTo(190.0);
        assertThat(first.high()).isEqualTo(192.0);
        assertThat(first.low()).isEqualTo(189.0);
        assertThat(first.close()).isEqualTo(191.5);
        assertThat(first.volume()).isEqualTo(50000000L);

        assertThat(history.bars().get(2).close()).isEqualTo(193.9); // newest last
    }

    @Test
    void marksLiveCandlesAsNotDelayed() {
        MarketDataProvider provider = providerReturning(200, CANDLES_203); // HTTP 200 = live

        assertThat(provider.getDailyBars("AAPL").delayed()).isFalse();
    }

    @Test
    void treatsNoDataCandlesAsEmptyHistoryWithoutCrashing() {
        MarketDataProvider provider = providerReturning(204, "{\"s\":\"no_data\"}"); // HTTP 204

        PriceHistory history = provider.getDailyBars("ZZZZ");

        assertThat(history.bars()).isEmpty();
        assertThat(history.delayed()).isFalse();
    }

    @Test
    void treatsAnEmptyBodyAsEmptyHistoryWithoutCrashing() {
        MarketDataProvider provider = providerReturning(503, ""); // network failure → 503

        assertThat(provider.getDailyBars("ZZZZ").bars()).isEmpty();
    }

    /**
     * Parses a real recorded daily-candle response (Phase 3 AC #4 / AC #7). Skips
     * cleanly until the golden fixture is captured via
     * {@code spikes/capture-marketdata-fixture.mjs}.
     */
    @Test
    void parsesRecordedDailyCandleFixture() throws Exception {
        InputStream resource = getClass().getResourceAsStream("/fixtures/marketdata/candles-AAPL.json");
        assumeTrue(resource != null,
                "golden fixture not captured — run: node spikes/capture-marketdata-fixture.mjs AAPL");

        String body;
        try (InputStream in = resource) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assumeTrue(body.contains("\"t\""), "fixture has no candle payload");

        // 203 = the delayed/cached response the cache layer serves in production.
        PriceHistory history = providerReturning(203, body).getDailyBars("AAPL");

        assertThat(history.bars()).isNotEmpty();
        assertThat(history.delayed()).isTrue();
        for (PriceBar bar : history.bars()) {
            assertThat(bar.high()).isGreaterThanOrEqualTo(bar.low());
            assertThat(bar.high()).isGreaterThanOrEqualTo(bar.open());
            assertThat(bar.high()).isGreaterThanOrEqualTo(bar.close());
            assertThat(bar.low()).isLessThanOrEqualTo(bar.open());
            assertThat(bar.low()).isLessThanOrEqualTo(bar.close());
        }
        // Vendor returns bars oldest-first → dates strictly ascending.
        for (int i = 1; i < history.bars().size(); i++) {
            assertThat(history.bars().get(i).date()).isAfter(history.bars().get(i - 1).date());
        }
    }
}
