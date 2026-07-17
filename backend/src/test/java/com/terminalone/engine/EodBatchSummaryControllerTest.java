package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.marketdata.MarketDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9 AC1 (FR-24, D20): the always-on backend summarizes its latest
 * scheduled EOD batch so the Electron main process can fire the single
 * post-batch desktop notification ("N new recommendations, top conviction: X").
 * On-demand lever pulls are deliberately excluded — they happen in-app and must
 * produce no alert noise. Lives in the engine package because
 * {@link Recommendation} construction is deliberately package-private.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class EodBatchSummaryControllerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-14T20:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private EngineBatchRunRepository batchRuns;

    @Autowired
    private RecommendationRepository recommendations;

    @MockitoBean
    private MarketDataProvider marketData;

    @BeforeEach
    void seedConfig() {
        seeder.run();
    }

    @Test
    void latestEodSummaryRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/engine/eod/latest").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNoContentWhenNoScheduledEodBatchHasEverRun() throws Exception {
        // An on-demand lever pull exists but must not surface here — it happens
        // in-app and would otherwise become notification noise.
        completedRun(EngineBatchKind.ON_DEMAND, STARTED_AT, 2);

        mockMvc.perform(get("/api/engine/eod/latest"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsTheLatestEodBatchWithItsTopConvictionRecommendation() throws Exception {
        completedRun(EngineBatchKind.SCHEDULED_EOD, STARTED_AT.minusSeconds(86_400), 1);
        EngineBatchRun latest = completedRun(EngineBatchKind.SCHEDULED_EOD, STARTED_AT, 2);
        recommendations.save(recommendation("AAPL", 55, latest.getId()));
        recommendations.save(recommendation("TSLA", 82, latest.getId()));

        mockMvc.perform(get("/api/engine/eod/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRunId").value(latest.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startedAt").value(STARTED_AT.toString()))
                .andExpect(jsonPath("$.completedAt").value(STARTED_AT.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.recommendationCount").value(2))
                .andExpect(jsonPath("$.topSymbol").value("TSLA"))
                .andExpect(jsonPath("$.topConviction").value(82));
    }

    @Test
    void omitsTopRecommendationWhenTheBatchProducedNoTrades() throws Exception {
        completedRun(EngineBatchKind.SCHEDULED_EOD, STARTED_AT, 0);

        mockMvc.perform(get("/api/engine/eod/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationCount").value(0))
                .andExpect(jsonPath("$.topSymbol").isEmpty())
                .andExpect(jsonPath("$.topConviction").isEmpty());
    }

    private EngineBatchRun completedRun(EngineBatchKind kind, Instant startedAt, int recommendationCount) {
        EngineBatchRun run = batchRuns.saveAndFlush(EngineBatchRun.start(kind, startedAt));
        List<RecommendationResponse> recs = java.util.stream.IntStream.range(0, recommendationCount)
                .mapToObj(i -> dummyResponse())
                .toList();
        run.complete(new EngineRunResponse(recs), recommendationCount, startedAt.plusSeconds(60));
        return batchRuns.saveAndFlush(run);
    }

    private RecommendationResponse dummyResponse() {
        return new RecommendationResponse(null, "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60, 1, EXPIRY, List.of(),
                1, 0, 0, 0, 0, 0, 0, null);
    }

    private Recommendation recommendation(String symbol, int conviction, Long batchRunId) {
        return new Recommendation(
                symbol, StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, conviction,
                RecommendationStatus.PAPER, 1, EXPIRY,
                symbol + "-100C", 100.0, symbol + "-110C", 110.0,
                3.50, 0.72, 650.0, 350.0, 1.86, 85.0,
                1, "{}", STARTED_AT, batchRunId);
    }
}
