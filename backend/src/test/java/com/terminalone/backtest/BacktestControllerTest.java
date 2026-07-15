package com.terminalone.backtest;

import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 AC4 (FR-19): {@code POST /api/backtest/signal} runs the directional
 * signal over a symbol's historical bars and returns a hit-rate/return summary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class BacktestControllerTest {

    private static final Instant AS_OF = Instant.parse("2026-07-01T20:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 2, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @MockitoBean
    private MarketDataProvider marketData;

    @BeforeEach
    void seedConfig() {
        seeder.run();
    }

    @Test
    void runsTheSignalOverHistoricalBarsAndReturnsAHitRateSummary() throws Exception {
        when(marketData.getDailyBars("AAPL")).thenReturn(uptrend("AAPL"));

        mockMvc.perform(post("/api/backtest/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"aapl\",\"horizonDays\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.horizonDays").value(5))
                .andExpect(jsonPath("$.barsAnalyzed").value(160))
                .andExpect(jsonPath("$.signalsEvaluated").isNumber())
                .andExpect(jsonPath("$.hitRate").value(1.0))
                .andExpect(jsonPath("$.bullishSignals").isNumber())
                .andExpect(jsonPath("$.cumulativeReturn").isNumber());
    }

    @Test
    void defaultsTheHorizonWhenOmitted() throws Exception {
        when(marketData.getDailyBars("MSFT")).thenReturn(uptrend("MSFT"));

        mockMvc.perform(post("/api/backtest/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"MSFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizonDays").value(5));
    }

    @Test
    void rejectsAMissingSymbolWith400() throws Exception {
        mockMvc.perform(post("/api/backtest/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANonPositiveHorizonWith400() throws Exception {
        mockMvc.perform(post("/api/backtest/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAPL\",\"horizonDays\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnonymousCallers() throws Exception {
        mockMvc.perform(post("/api/backtest/signal")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAPL\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static PriceHistory uptrend(String symbol) {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double close = 80.0 + i * 1.5;
            bars.add(new PriceBar(START.plusDays(i), close - 0.50, close + 0.75, close - 1.00, close, 1_000_000));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }
}
