package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
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

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private RecommendationRepository recommendations;

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
        addStock("AAPL", 100, "90.00");
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
                .andExpect(jsonPath("$.recommendations[0].configVersion").isNumber())
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
                .andExpect(jsonPath("$.recommendations[0].rationale.signals.trendVote").isNumber())
                .andExpect(jsonPath("$.recommendations[0].rationale.regime.reason").value("PHASE4_SINGLE_CELL_NORMAL"))
                .andExpect(jsonPath("$.recommendations[0].rationale.selection.longDeltaTarget").value(0.55));

        assertThat(recommendations.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getConfigVersion()).isNotNull();
            assertThat(saved.getStrategy()).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
            assertThat(saved.getRationale()).contains("PHASE4_SINGLE_CELL_NORMAL");
        });
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

    private OptionChain normalIvChain(String symbol) {
        double spot = 100.0;
        double sigma = 0.30;
        return new OptionChain(symbol, spot, AS_OF, true, List.of(
                pricedCall(symbol, 90.0, spot, sigma),
                pricedCall(symbol, 95.0, spot, sigma),
                pricedCall(symbol, 100.0, spot, sigma),
                pricedCall(symbol, 105.0, spot, sigma),
                pricedCall(symbol, 110.0, spot, sigma),
                pricedCall(symbol, 115.0, spot, sigma)));
    }

    private OptionContract pricedCall(String symbol, double strike, double spot, double sigma) {
        double t = ChronoUnit.DAYS.between(TODAY, EXPIRY) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, 0.04, 0.0, sigma, CallPut.CALL)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract(symbol + "-" + strike, CallPut.CALL, strike, EXPIRY, bid, ask, 1_000);
    }
}
