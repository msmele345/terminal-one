package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.IvHistory;
import com.terminalone.marketdata.IvHistoryRepository;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 AC3/AC4: the first recommendation-engine vertical slice. A lever-pull
 * over one bullish/normal-IV underlying returns a concrete Bull Call Debit Spread
 * and persists it with the active engine-config version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class EngineRunControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);
    private static final LocalDate INCOME_EXPIRY = TODAY.plusDays(38);

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private IvHistoryRepository ivHistory;

    @MockitoBean
    private MarketDataProvider marketData;

    @BeforeEach
    void seedConfig() {
        seeder.run();
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(AS_OF, ZoneOffset.UTC);
        }
    }

    @Test
    void engineRunRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/engine/run").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bullishNormalUnderlyingProducesAndPersistsABullCallDebitSpread() throws Exception {
        // Large enough book that the §7 3% cap admits the structure (else it abstains RISK_TOO_LARGE).
        addStock("AAPL", 1_000, "150.00");
        seedNormalIvRankHistory("AAPL");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.recommendations[0].strategy").value("BULL_CALL_DEBIT_SPREAD"))
                .andExpect(jsonPath("$.recommendations[0].direction").value("BULLISH"))
                .andExpect(jsonPath("$.recommendations[0].regime").value("NORMAL"))
                .andExpect(jsonPath("$.recommendations[0].configVersion").value(1))
                .andExpect(jsonPath("$.recommendations[0].expiry").value(EXPIRY.toString()))
                .andExpect(jsonPath("$.recommendations[0].legs[0].action").value("BUY"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].callPut").value("CALL"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].strike").value(100.0))
                .andExpect(jsonPath("$.recommendations[0].legs[1].action").value("SELL"))
                .andExpect(jsonPath("$.recommendations[0].legs[1].callPut").value("CALL"))
                .andExpect(jsonPath("$.recommendations[0].legs[1].strike").value(110.0))
                .andExpect(jsonPath("$.recommendations[0].entryDebit").isNumber())
                .andExpect(jsonPath("$.recommendations[0].probabilityOfProfit").isNumber())
                .andExpect(jsonPath("$.recommendations[0].maxProfit").isNumber())
                .andExpect(jsonPath("$.recommendations[0].maxLoss").isNumber())
                .andExpect(jsonPath("$.recommendations[0].contracts").isNumber())
                .andExpect(jsonPath("$.recommendations[0].rationale.sizing.contracts").isNumber())
                .andExpect(jsonPath("$.recommendations[0].rationale.sizing.portfolioValue").isNumber())
                .andExpect(jsonPath("$.recommendations[0].rationale.signals.trendVote").isNumber())
                .andExpect(jsonPath("$.recommendations[0].rationale.regime.reason").value(containsString("IV_RANK")))
                .andExpect(jsonPath("$.recommendations[0].rationale.selection.longDeltaTarget").value(0.55));

        assertThat(recommendations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getConfigVersion()).isEqualTo(1);
            assertThat(saved.getStrategy()).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
            assertThat(saved.getContracts()).isGreaterThan(0);
            assertThat(saved.getRationale()).contains("IV_RANK");
            assertThat(saved.getRationale()).contains("sizing");
        });
    }

    @Test
    void abstainsWhenASingleContractExceedsThePerTradeRiskCap() throws Exception {
        // $150 book × 3% = $4.50 risk budget — too small for any defined-risk contract.
        addStock("AAPL", 1, "150.00");
        seedNormalIvRankHistory("AAPL");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(0)));

        assertThat(recommendations.findAll()).isEmpty();
    }

    @Test
    void bearishNormalUnderlyingProducesAndPersistsABearPutDebitSpread() throws Exception {
        addStock("XYZ", 100, "150.00");
        seedNormalIvRankHistory("XYZ");
        when(marketData.getDailyBars("XYZ")).thenReturn(bearishHistory("XYZ"));
        when(marketData.getChain("XYZ")).thenReturn(putChain("XYZ"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].symbol").value("XYZ"))
                .andExpect(jsonPath("$.recommendations[0].strategy").value("BEAR_PUT_DEBIT_SPREAD"))
                .andExpect(jsonPath("$.recommendations[0].direction").value("BEARISH"))
                .andExpect(jsonPath("$.recommendations[0].regime").value("NORMAL"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].action").value("BUY"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].callPut").value("PUT"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].strike").value(100.0))
                .andExpect(jsonPath("$.recommendations[0].legs[1].action").value("SELL"))
                .andExpect(jsonPath("$.recommendations[0].legs[1].callPut").value("PUT"))
                .andExpect(jsonPath("$.recommendations[0].legs[1].strike").value(95.0));

        assertThat(recommendations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getConfigVersion()).isEqualTo(1);
            assertThat(saved.getStrategy()).isEqualTo(StrategyType.BEAR_PUT_DEBIT_SPREAD);
            assertThat(saved.getDirection()).isEqualTo(Direction.BEARISH);
        });
    }

    /**
     * Phase 5 AC6 (§8): with three underlyings in the portfolio, the engine
     * ranks all survivors globally and surfaces them in descending score order,
     * persisted as separate recommendations. Default topN=3 keeps all three.
     */
    @Test
    void ranksMultipleUnderlyingsGloballyAcrossThePortfolio() throws Exception {
        addStock("AAPL", 1_000, "150.00");
        addStock("MSFT", 1_000, "150.00");
        addStock("TSLA", 1_000, "150.00");
        seedNormalIvRankHistory("AAPL");
        seedNormalIvRankHistory("MSFT");
        seedNormalIvRankHistory("TSLA");
        // Same chain/sigma for all three so resulting economics are comparable; the
        // distinctive lever is the signal history, which drives raw EV via the
        // POP/modeling baked into the selector. All three are bullish-normal so
        // each yields a BULL_CALL_DEBIT_SPREAD surviving the guardrails.
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getDailyBars("MSFT")).thenReturn(bullishHistory("MSFT"));
        when(marketData.getDailyBars("TSLA")).thenReturn(bullishHistory("TSLA"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));
        when(marketData.getChain("MSFT")).thenReturn(normalIvChain("MSFT"));
        when(marketData.getChain("TSLA")).thenReturn(normalIvChain("TSLA"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(3)))
                .andExpect(jsonPath("$.recommendations[*].symbol",
                        containsInAnyOrder("AAPL", "MSFT", "TSLA")));

        assertThat(recommendations.findAll()).hasSize(3);
    }

    @Test
    void neutralHighIvUnderlyingWithTwoHundredSharesProducesCoveredCallSizedToHeldShares() throws Exception {
        addStock("MSFT", 200, "100.00");
        seedHighIvRankHistory("MSFT");
        when(marketData.getDailyBars("MSFT")).thenReturn(flatHistory("MSFT"));
        when(marketData.getChain("MSFT")).thenReturn(highIvIncomeCallChain("MSFT"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.recommendations[0].strategy").value("COVERED_CALL"))
                .andExpect(jsonPath("$.recommendations[0].direction").value("NEUTRAL"))
                .andExpect(jsonPath("$.recommendations[0].regime").value("HIGH"))
                .andExpect(jsonPath("$.recommendations[0].expiry").value(INCOME_EXPIRY.toString()))
                .andExpect(jsonPath("$.recommendations[0].contracts").value(2))
                .andExpect(jsonPath("$.recommendations[0].legs", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].legs[0].action").value("SELL"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].callPut").value("CALL"))
                .andExpect(jsonPath("$.recommendations[0].rationale.selection.shortDeltaTarget").value(0.30))
                .andExpect(jsonPath("$.recommendations[0].rationale.sizing.contracts").value(2))
                .andExpect(jsonPath("$.recommendations[0].rationale.incomeOverlay.label")
                        .value("CAPS_UPSIDE_ABOVE_STRIKE"));

        assertThat(recommendations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getStrategy()).isEqualTo(StrategyType.COVERED_CALL);
            assertThat(saved.getContracts()).isEqualTo(2);
            assertThat(saved.getRationale()).contains("CAPS_UPSIDE_ABOVE_STRIKE");
        });
    }

    @Test
    void neutralHighIvUnderlyingWithFewerThanOneHundredSharesProducesACashSecuredPutInstead() throws Exception {
        addStock("MSFT", 99, "100.00"); // < 100 shares → covered call ineligible, CSP is the §2 "else"
        seedHighIvRankHistory("MSFT");
        when(marketData.getDailyBars("MSFT")).thenReturn(flatHistory("MSFT"));
        when(marketData.getChain("MSFT")).thenReturn(highIvIncomePutChain("MSFT"));

        mockMvc.perform(post("/api/engine/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.recommendations[0].strategy").value("CASH_SECURED_PUT"))
                .andExpect(jsonPath("$.recommendations[0].direction").value("NEUTRAL"))
                .andExpect(jsonPath("$.recommendations[0].regime").value("HIGH"))
                .andExpect(jsonPath("$.recommendations[0].expiry").value(INCOME_EXPIRY.toString()))
                .andExpect(jsonPath("$.recommendations[0].contracts").value(1))
                .andExpect(jsonPath("$.recommendations[0].legs", hasSize(1)))
                .andExpect(jsonPath("$.recommendations[0].legs[0].action").value("SELL"))
                .andExpect(jsonPath("$.recommendations[0].legs[0].callPut").value("PUT"))
                .andExpect(jsonPath("$.recommendations[0].rationale.selection.shortDeltaTarget").value(0.30))
                .andExpect(jsonPath("$.recommendations[0].rationale.entrySuggestion.label")
                        .value("REQUIRES_CASH_COLLATERAL"))
                .andExpect(jsonPath("$.recommendations[0].rationale.entrySuggestion.requiredCapital").isNumber());

        assertThat(recommendations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getStrategy()).isEqualTo(StrategyType.CASH_SECURED_PUT);
            assertThat(saved.getContracts()).isEqualTo(1);
            assertThat(saved.getRationale()).contains("REQUIRES_CASH_COLLATERAL");
        });
    }

    private void seedNormalIvRankHistory(String symbol) {
        for (int i = 0; i < 60; i++) {
            double atmIv = 0.20 + (0.20 * i / 59.0);
            ivHistory.save(new IvHistory(symbol, TODAY.minusDays(60 - i), atmIv,
                    100.0, 100.0, EXPIRY, AS_OF));
        }
    }

    private void seedHighIvRankHistory(String symbol) {
        for (int i = 0; i < 60; i++) {
            double atmIv = 0.20 + (0.20 * i / 59.0);
            ivHistory.save(new IvHistory(symbol, TODAY.minusDays(60 - i), atmIv,
                    100.0, 100.0, INCOME_EXPIRY, AS_OF));
        }
    }

    private void addStock(String symbol, int qty, String cost) throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"%s","quantity":%d,
                                 "costBasis":%s,"openedDate":"2026-01-15"}""".formatted(symbol, qty, cost)))
                .andExpect(status().isCreated());
    }

    private PriceHistory bullishHistory(String symbol) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = TODAY.minusDays(119);
        for (int i = 0; i < 120; i++) {
            double close = 45.0 + i * 0.50 + Math.max(0, i - 80) * 0.40;
            bars.add(new PriceBar(start.plusDays(i), close - 0.35, close + 0.65,
                    close - 0.85, close, 1_000_000 + i));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }

    private PriceHistory bearishHistory(String symbol) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = TODAY.minusDays(119);
        for (int i = 0; i < 120; i++) {
            double close = 155.0 - i * 0.50 - Math.max(0, i - 80) * 0.40;
            bars.add(new PriceBar(start.plusDays(i), close + 0.35, close + 0.85,
                    close - 0.65, close, 1_000_000 + i));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }

    private PriceHistory flatHistory(String symbol) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = TODAY.minusDays(119);
        for (int i = 0; i < 120; i++) {
            bars.add(new PriceBar(start.plusDays(i), 100.0, 100.5,
                    99.5, 100.0, 1_000_000 + i));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }

    /** Puts at and below spot only, so the long leg pins to the ATM 100 strike in every band. */
    private OptionChain putChain(String symbol) {
        double spot = 100.0;
        double sigma = 0.30;
        return new OptionChain(symbol, spot, AS_OF, true, List.of(
                pricedContract(symbol, CallPut.PUT, 80.0, spot, sigma),
                pricedContract(symbol, CallPut.PUT, 85.0, spot, sigma),
                pricedContract(symbol, CallPut.PUT, 90.0, spot, sigma),
                pricedContract(symbol, CallPut.PUT, 95.0, spot, sigma),
                pricedContract(symbol, CallPut.PUT, 100.0, spot, sigma)));
    }

    private OptionChain normalIvChain(String symbol) {
        double spot = 100.0;
        double sigma = 0.30;
        return new OptionChain(symbol, spot, AS_OF, true, List.of(
                pricedContract(symbol, CallPut.CALL, 90.0, spot, sigma),
                pricedContract(symbol, CallPut.CALL, 95.0, spot, sigma),
                pricedContract(symbol, CallPut.CALL, 100.0, spot, sigma),
                pricedContract(symbol, CallPut.CALL, 105.0, spot, sigma),
                pricedContract(symbol, CallPut.CALL, 110.0, spot, sigma),
                pricedContract(symbol, CallPut.CALL, 115.0, spot, sigma)));
    }

    private OptionChain highIvIncomeCallChain(String symbol) {
        double spot = 100.0;
        double sigma = 0.45;
        return new OptionChain(symbol, spot, AS_OF, true, List.of(
                pricedContract(symbol, CallPut.CALL, 100.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.CALL, 105.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.CALL, 110.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.CALL, 115.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.CALL, 120.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.CALL, 125.0, spot, sigma, INCOME_EXPIRY)));
    }

    /** ATM call (for the ATM-IV read) plus a put ladder below spot for the CSP short strike.
     *  σ=0.35 still ranks HIGH vs the seeded 0.20–0.40 history, but keeps the 0.30Δ put's
     *  POP above the 0.65 credit floor. */
    private OptionChain highIvIncomePutChain(String symbol) {
        double spot = 100.0;
        double sigma = 0.35;
        return new OptionChain(symbol, spot, AS_OF, true, List.of(
                pricedContract(symbol, CallPut.CALL, 100.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 100.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 95.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 90.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 85.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 80.0, spot, sigma, INCOME_EXPIRY),
                pricedContract(symbol, CallPut.PUT, 75.0, spot, sigma, INCOME_EXPIRY)));
    }

    private OptionContract pricedContract(String symbol, CallPut type, double strike, double spot,
            double sigma) {
        return pricedContract(symbol, type, strike, spot, sigma, EXPIRY);
    }

    private OptionContract pricedContract(String symbol, CallPut type, double strike, double spot,
            double sigma, LocalDate expiry) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, 0.04, 0.0, sigma, type)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract(symbol + "-" + type + "-" + strike, type, strike, expiry, bid, ask, 1_000);
    }
}
