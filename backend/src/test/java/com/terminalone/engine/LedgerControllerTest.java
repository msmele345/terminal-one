package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigRepository;
import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.engine.config.EngineConfigVersion;
import com.terminalone.ledger.PaperTrade;
import com.terminalone.ledger.PaperTradeRepository;
import com.terminalone.ledger.PaperTradeService;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 AC2: the ledger API lists every recommendation's paper-trade entry with
 * its status and outcomes, plus aggregate track-record stats, filterable by the
 * producing {@code config_version} so tuning can be compared (FR-17/FR-22).
 * Lives in the engine package because {@link Recommendation} construction is
 * deliberately package-private (same precedent as PaperTradeSettlementServiceTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class LedgerControllerTest {

    private static final Instant OPENED_AT = Instant.parse("2026-06-15T20:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(21, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private PaperTradeRepository paperTrades;

    @Autowired
    private PaperTradeService paperTradeService;

    @Autowired
    private EngineConfigRepository engineConfigs;

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
    void listsPaperTradeEntriesWithStatusAndOutcomesNewestFirst() throws Exception {
        Recommendation older = recommendations.save(recommendation("AAPL", 1, 3.50, 2));
        paperTrades.save(PaperTrade.fromRecommendation(older, OPENED_AT));
        Recommendation newer = recommendations.save(recommendation("MSFT", 1, -1.20, 1));
        paperTrades.save(PaperTrade.fromRecommendation(newer, OPENED_AT.plusSeconds(3600)));

        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.entries[1].symbol").value("AAPL"))
                .andExpect(jsonPath("$.entries[1].strategy").value("BULL_CALL_DEBIT_SPREAD"))
                .andExpect(jsonPath("$.entries[1].direction").value("BULLISH"))
                .andExpect(jsonPath("$.entries[1].conviction").value(60))
                .andExpect(jsonPath("$.entries[1].recommendationStatus").value("PAPER"))
                .andExpect(jsonPath("$.entries[1].tradeStatus").value("OPEN"))
                .andExpect(jsonPath("$.entries[1].configVersion").value(1))
                .andExpect(jsonPath("$.entries[1].expiry").value(EXPIRY.toString()))
                .andExpect(jsonPath("$.entries[1].contracts").value(2))
                .andExpect(jsonPath("$.entries[1].entryDebit").value(3.50))
                .andExpect(jsonPath("$.entries[1].markDebit").isEmpty())
                .andExpect(jsonPath("$.entries[1].unrealizedPnl").value(0.0))
                .andExpect(jsonPath("$.entries[1].realizedPnl").value(0.0))
                .andExpect(jsonPath("$.entries[1].openedAt").value(OPENED_AT.toString()))
                .andExpect(jsonPath("$.entries[1].recommendationId").value(older.getId()));
    }

    @Test
    void aggregatesTrackRecordStatsAcrossOpenAndSettledTrades() throws Exception {
        // Settled win: expired today, spot 112 → mark 10.00, realized (10 − 3.50) × 100 × 2 = +1300.
        paperTradeService.createForRecommendation(
                recommendations.save(recommendation("AAPL", 1, 3.50, 2, TODAY)));
        when(marketData.getChain("AAPL")).thenReturn(new OptionChain("AAPL", 112.0, AS_OF, true, List.of()));
        // Settled loss: expired today, spot 90 → both legs worthless, realized −3.50 × 100 = −350.
        paperTradeService.createForRecommendation(
                recommendations.save(recommendation("TSLA", 1, 3.50, 1, TODAY)));
        when(marketData.getChain("TSLA")).thenReturn(new OptionChain("TSLA", 90.0, AS_OF, true, List.of()));
        // Still open: marked at 4.50 → unrealized (4.50 − 3.50) × 100 = +100.
        paperTradeService.createForRecommendation(
                recommendations.save(recommendation("MSFT", 1, 3.50, 1, EXPIRY)));
        when(marketData.getChain("MSFT")).thenReturn(new OptionChain("MSFT", 106.0, AS_OF, true, List.of(
                contract("MSFT-100C", 100.0, 5.40, 5.60),
                contract("MSFT-110C", 110.0, 0.90, 1.10))));

        paperTradeService.settleOpenTrades();

        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalTrades").value(3))
                .andExpect(jsonPath("$.stats.openTrades").value(1))
                .andExpect(jsonPath("$.stats.settledTrades").value(2))
                .andExpect(jsonPath("$.stats.wins").value(1))
                .andExpect(jsonPath("$.stats.losses").value(1))
                .andExpect(jsonPath("$.stats.hitRate").value(0.5))
                .andExpect(jsonPath("$.stats.realizedPnl").value(950.0))
                .andExpect(jsonPath("$.stats.unrealizedPnl").value(100.0))
                .andExpect(jsonPath("$.stats.totalPnl").value(1050.0));
    }

    @Test
    void hitRateIsNullUntilAnyTradeSettles() throws Exception {
        paperTrades.save(PaperTrade.fromRecommendation(
                recommendations.save(recommendation("AAPL", 1, 3.50, 2)), OPENED_AT));

        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalTrades").value(1))
                .andExpect(jsonPath("$.stats.settledTrades").value(0))
                .andExpect(jsonPath("$.stats.hitRate").isEmpty());
    }

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/ledger").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void filtersEntriesAndStatsByConfigVersionAndListsAvailableVersions() throws Exception {
        EngineConfigVersion v1 = engineConfigs.findAll().get(0);
        int v2 = engineConfigs.save(new EngineConfigVersion(
                v1.getConfig(), false, "retune for ledger filter test", OPENED_AT)).getVersion();

        paperTrades.save(PaperTrade.fromRecommendation(
                recommendations.save(recommendation("AAPL", 1, 3.50, 2)), OPENED_AT));
        paperTrades.save(PaperTrade.fromRecommendation(
                recommendations.save(recommendation("MSFT", v2, 1.25, 1)), OPENED_AT.plusSeconds(60)));

        mockMvc.perform(get("/api/ledger").param("configVersion", String.valueOf(v2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.entries[0].configVersion").value(v2))
                .andExpect(jsonPath("$.stats.totalTrades").value(1))
                .andExpect(jsonPath("$.configVersion").value(v2))
                .andExpect(jsonPath("$.configVersions[0]").value(1))
                .andExpect(jsonPath("$.configVersions[1]").value(v2));

        // Unfiltered: all entries, both versions advertised for the filter UI.
        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.stats.totalTrades").value(2))
                .andExpect(jsonPath("$.configVersion").isEmpty())
                .andExpect(jsonPath("$.configVersions.length()").value(2));
    }

    private OptionContract contract(String optionSymbol, double strike, double bid, double ask) {
        return new OptionContract(optionSymbol, CallPut.CALL, strike, EXPIRY, bid, ask, 1_000);
    }

    private Recommendation recommendation(String symbol, int configVersion, double entryDebit, int contracts) {
        return recommendation(symbol, configVersion, entryDebit, contracts, EXPIRY);
    }

    private Recommendation recommendation(String symbol, int configVersion, double entryDebit, int contracts,
            LocalDate expiry) {
        return new Recommendation(
                symbol, StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60,
                RecommendationStatus.PAPER, configVersion, expiry,
                symbol + "-100C", 100.0, symbol + "-110C", 110.0,
                entryDebit, 0.72, 650.0, 350.0, 1.86, 85.0,
                contracts, "{}", OPENED_AT.minusSeconds(3600));
    }
}
