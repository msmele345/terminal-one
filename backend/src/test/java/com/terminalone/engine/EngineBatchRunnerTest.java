package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigRepository;
import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.ledger.PaperTrade;
import com.terminalone.ledger.PaperTradeRepository;
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
import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.OptionPositionRepository;
import com.terminalone.portfolio.OptionType;
import com.terminalone.portfolio.PositionSide;
import com.terminalone.portfolio.StockPosition;
import com.terminalone.portfolio.StockPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineBatchRunnerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    @Autowired
    private EngineBatchRunner runner;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private EngineConfigRepository engineConfigs;

    @Autowired
    private StockPositionRepository stocks;

    @Autowired
    private OptionPositionRepository options;

    @Autowired
    private IvHistoryRepository ivHistory;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private EngineBatchRunRepository batchRuns;

    @Autowired
    private SignalSnapshotRepository signalSnapshots;

    @Autowired
    private PaperTradeRepository paperTrades;

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
    void eodBatchPersistsAnObservableRunRecordRecommendationsAndSignalSnapshots() {
        stocks.save(new StockPosition("AAPL", new BigDecimal("1000"), new BigDecimal("150.00"), TODAY));
        stocks.save(new StockPosition("FLAT", new BigDecimal("100"), new BigDecimal("100.00"), TODAY));
        seedNormalIvRankHistory("AAPL");
        seedNormalIvRankHistory("FLAT");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getDailyBars("FLAT")).thenReturn(flatHistory("FLAT"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));
        when(marketData.getChain("FLAT")).thenReturn(normalIvChain("FLAT"));

        EngineBatchRunResponse result = runner.runEodBatch();

        assertThat(result.kind()).isEqualTo(EngineBatchKind.SCHEDULED_EOD);
        assertThat(result.status()).isEqualTo(EngineBatchStatus.COMPLETED);
        assertThat(result.startedAt()).isEqualTo(AS_OF);
        assertThat(result.completedAt()).isEqualTo(AS_OF);
        assertThat(result.recommendationCount()).isEqualTo(1);
        assertThat(result.abstentionCount()).isEqualTo(1);
        assertThat(result.signalSnapshotCount()).isEqualTo(2);

        assertThat(batchRuns.findAll()).singleElement().satisfies(savedRun -> {
            assertThat(savedRun.getId()).isEqualTo(result.id());
            assertThat(savedRun.getStatus()).isEqualTo(EngineBatchStatus.COMPLETED);
            assertThat(savedRun.getRecommendationCount()).isEqualTo(1);
            assertThat(savedRun.getAbstentionCount()).isEqualTo(1);
            assertThat(savedRun.getSignalSnapshotCount()).isEqualTo(2);
        });

        assertThat(recommendations.findAll()).singleElement().satisfies(savedRecommendation -> {
            assertThat(savedRecommendation.getSymbol()).isEqualTo("AAPL");
            assertThat(savedRecommendation.getBatchRunId()).isEqualTo(result.id());
        });
        assertThat(paperTrades.findAll()).singleElement().satisfies(paperTrade -> {
            Recommendation savedRecommendation = recommendations.findAll().getFirst();
            assertThat(paperTrade.getRecommendation().getId()).isEqualTo(savedRecommendation.getId());
            assertThat(paperTrade.getSymbol()).isEqualTo("AAPL");
            assertThat(paperTrade.getConfigVersion()).isEqualTo(savedRecommendation.getConfigVersion());
            assertThat(paperTrade.getContracts()).isEqualTo(savedRecommendation.getContracts());
            assertThat(paperTrade.getEntryDebit()).isEqualByComparingTo(
                    BigDecimal.valueOf(savedRecommendation.getEntryDebit()).setScale(4, RoundingMode.HALF_UP));
            assertThat(paperTrade.getStatus()).isEqualTo(PaperTrade.Status.OPEN);
        });

        assertThat(signalSnapshots.findByBatchRunIdOrderBySymbolAsc(result.id()))
                .hasSize(2)
                .extracting(SignalSnapshot::getSymbol)
                .containsExactly("AAPL", "FLAT");
        assertThat(signalSnapshots.findByBatchRunIdOrderBySymbolAsc(result.id()))
                .first()
                .satisfies(snapshot -> {
                    assertThat(snapshot.getConfigVersion()).isEqualTo(1);
                    assertThat(snapshot.getDirection()).isEqualTo(Direction.BULLISH);
                    assertThat(snapshot.getRegime()).isEqualTo(VolatilityRegime.NORMAL);
                    assertThat(snapshot.getChainAsOf()).isEqualTo(AS_OF);
                    assertThat(snapshot.getUnderlyingPrice()).isEqualTo(100.0);
                });
    }

    /**
     * Phase 6 AC6 — the on-demand lever-pull runs through the same batch
     * orchestration as the scheduled EOD run: it records an observable
     * {@code ON_DEMAND} run record, persists signal snapshots, and links its
     * recommendations, while still returning the full engine response the UI
     * renders.
     */
    @Test
    void onDemandLeverPullRecordsAnObservableRunWithSnapshotsViaTheSameEnginePath() {
        stocks.save(new StockPosition("AAPL", new BigDecimal("1000"), new BigDecimal("150.00"), TODAY));
        stocks.save(new StockPosition("FLAT", new BigDecimal("100"), new BigDecimal("100.00"), TODAY));
        seedNormalIvRankHistory("AAPL");
        seedNormalIvRankHistory("FLAT");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getDailyBars("FLAT")).thenReturn(flatHistory("FLAT"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));
        when(marketData.getChain("FLAT")).thenReturn(normalIvChain("FLAT"));

        EngineRunResponse response = runner.runOnDemand(new EngineRunRequest(null));

        assertThat(response.recommendations()).singleElement()
                .satisfies(rec -> assertThat(rec.symbol()).isEqualTo("AAPL"));
        assertThat(response.abstentions()).singleElement()
                .satisfies(abstention -> assertThat(abstention.symbol()).isEqualTo("FLAT"));

        assertThat(batchRuns.findAll()).singleElement().satisfies(savedRun -> {
            assertThat(savedRun.getKind()).isEqualTo(EngineBatchKind.ON_DEMAND);
            assertThat(savedRun.getStatus()).isEqualTo(EngineBatchStatus.COMPLETED);
            assertThat(savedRun.getRecommendationCount()).isEqualTo(1);
            assertThat(savedRun.getAbstentionCount()).isEqualTo(1);
            assertThat(savedRun.getSignalSnapshotCount()).isEqualTo(2);

            assertThat(recommendations.findAll()).singleElement()
                    .satisfies(rec -> assertThat(rec.getBatchRunId()).isEqualTo(savedRun.getId()));
            assertThat(signalSnapshots.findByBatchRunIdOrderBySymbolAsc(savedRun.getId()))
                    .extracting(SignalSnapshot::getSymbol)
                    .containsExactly("AAPL", "FLAT");
        });
    }

    /**
     * Phase 6 AC6 — no divergent logic: the scheduled EOD batch and the
     * on-demand lever-pull, given identical portfolio and market inputs,
     * persist recommendations that agree on every economic field.
     */
    @Test
    void onDemandAndScheduledBatchProduceIdenticalRecommendationsFromTheSameInputs() {
        stocks.save(new StockPosition("AAPL", new BigDecimal("1000"), new BigDecimal("150.00"), TODAY));
        seedNormalIvRankHistory("AAPL");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));

        EngineBatchRunResponse eod = runner.runEodBatch();
        runner.runOnDemand(new EngineRunRequest(null));

        EngineBatchRun onDemandRun = batchRuns.findAll().stream()
                .filter(run -> run.getKind() == EngineBatchKind.ON_DEMAND)
                .findFirst().orElseThrow();
        Recommendation fromEod = recommendations.findAll().stream()
                .filter(rec -> eod.id().equals(rec.getBatchRunId()))
                .findFirst().orElseThrow();
        Recommendation fromLever = recommendations.findAll().stream()
                .filter(rec -> onDemandRun.getId().equals(rec.getBatchRunId()))
                .findFirst().orElseThrow();

        assertThat(fromLever.getSymbol()).isEqualTo(fromEod.getSymbol());
        assertThat(fromLever.getStrategy()).isEqualTo(fromEod.getStrategy());
        assertThat(fromLever.getDirection()).isEqualTo(fromEod.getDirection());
        assertThat(fromLever.getRegime()).isEqualTo(fromEod.getRegime());
        assertThat(fromLever.getConviction()).isEqualTo(fromEod.getConviction());
        assertThat(fromLever.getConfigVersion()).isEqualTo(fromEod.getConfigVersion());
        assertThat(fromLever.getExpiry()).isEqualTo(fromEod.getExpiry());
        assertThat(fromLever.getLongOptionSymbol()).isEqualTo(fromEod.getLongOptionSymbol());
        assertThat(fromLever.getLongStrike()).isEqualTo(fromEod.getLongStrike());
        assertThat(fromLever.getShortOptionSymbol()).isEqualTo(fromEod.getShortOptionSymbol());
        assertThat(fromLever.getShortStrike()).isEqualTo(fromEod.getShortStrike());
        assertThat(fromLever.getEntryDebit()).isEqualTo(fromEod.getEntryDebit());
        assertThat(fromLever.getProbabilityOfProfit()).isEqualTo(fromEod.getProbabilityOfProfit());
        assertThat(fromLever.getMaxProfit()).isEqualTo(fromEod.getMaxProfit());
        assertThat(fromLever.getMaxLoss()).isEqualTo(fromEod.getMaxLoss());
        assertThat(fromLever.getRiskReward()).isEqualTo(fromEod.getRiskReward());
        assertThat(fromLever.getScore()).isEqualTo(fromEod.getScore());
        assertThat(fromLever.getContracts()).isEqualTo(fromEod.getContracts());
        assertThat(fromLever.getRationale()).isEqualTo(fromEod.getRationale());
    }

    /**
     * Shared failure handling (AC6): if the engine throws mid-run, the batch
     * record — on-demand here, identically for the scheduled kind — is marked
     * FAILED with the error message, and the exception still propagates.
     */
    @Test
    void aFailedRunIsMarkedFailedWithItsErrorAndRethrown() {
        engineConfigs.deleteAll(); // no active engine config → run start throws

        assertThatThrownBy(() -> runner.runOnDemand(new EngineRunRequest(null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(batchRuns.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getKind()).isEqualTo(EngineBatchKind.ON_DEMAND);
            assertThat(run.getStatus()).isEqualTo(EngineBatchStatus.FAILED);
            assertThat(run.getErrorMessage()).isNotBlank();
            assertThat(run.getCompletedAt()).isEqualTo(AS_OF);
        });
    }

    /**
     * Phase 6 AC7 — batch fan-out over a multi-symbol portfolio with mixed
     * outcomes. Four underlyings (one sourced from an option-only position):
     * AAPL trades, FLAT abstains {@code WEAK_SIGNAL}, MISS has no sourceable
     * chain and BOOM's vendor call throws — both abstain {@code NO_MARKET_DATA}
     * without aborting the run. The run record's counts and the per-symbol
     * snapshots stay consistent: snapshots exist only for the symbols that
     * reached signal/regime computation.
     */
    @Test
    void batchFansOutOverEveryPortfolioUnderlyingWithMixedOutcomes() {
        stocks.save(new StockPosition("AAPL", new BigDecimal("1000"), new BigDecimal("150.00"), TODAY));
        stocks.save(new StockPosition("FLAT", new BigDecimal("100"), new BigDecimal("100.00"), TODAY));
        stocks.save(new StockPosition("BOOM", new BigDecimal("50"), new BigDecimal("80.00"), TODAY));
        // MISS enters the universe through an option leg only — symbolsFor unions
        // stock symbols with option underlyings.
        options.save(new OptionPosition("MISS", OptionType.CALL, new BigDecimal("100"), EXPIRY,
                new BigDecimal("1"), new BigDecimal("5.00"), PositionSide.LONG, TODAY));
        seedNormalIvRankHistory("AAPL");
        seedNormalIvRankHistory("FLAT");
        when(marketData.getDailyBars("AAPL")).thenReturn(bullishHistory("AAPL"));
        when(marketData.getDailyBars("FLAT")).thenReturn(flatHistory("FLAT"));
        when(marketData.getChain("AAPL")).thenReturn(normalIvChain("AAPL"));
        when(marketData.getChain("FLAT")).thenReturn(normalIvChain("FLAT"));
        when(marketData.getChain("MISS")).thenReturn(null);
        when(marketData.getChain("BOOM")).thenThrow(new RuntimeException("vendor 500"));

        EngineRunResponse response = runner.runOnDemand(new EngineRunRequest(null));

        assertThat(response.recommendations()).singleElement()
                .satisfies(rec -> assertThat(rec.symbol()).isEqualTo("AAPL"));
        assertThat(response.abstentions())
                .extracting(Abstention::symbol, Abstention::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FLAT", "WEAK_SIGNAL"),
                        org.assertj.core.groups.Tuple.tuple("MISS", "NO_MARKET_DATA"),
                        org.assertj.core.groups.Tuple.tuple("BOOM", "NO_MARKET_DATA"));

        assertThat(batchRuns.findAll()).singleElement().satisfies(savedRun -> {
            assertThat(savedRun.getStatus()).isEqualTo(EngineBatchStatus.COMPLETED);
            assertThat(savedRun.getRecommendationCount()).isEqualTo(1);
            assertThat(savedRun.getAbstentionCount()).isEqualTo(3);
            assertThat(savedRun.getSignalSnapshotCount()).isEqualTo(2);
            // Only AAPL and FLAT reached signal/regime computation; the two
            // no-data symbols never produced a snapshot.
            assertThat(signalSnapshots.findByBatchRunIdOrderBySymbolAsc(savedRun.getId()))
                    .extracting(SignalSnapshot::getSymbol)
                    .containsExactly("AAPL", "FLAT");
        });
        assertThat(recommendations.findAll()).singleElement()
                .satisfies(rec -> assertThat(rec.getSymbol()).isEqualTo("AAPL"));
    }

    @Test
    void scheduledRunUsesThePostCloseWeekdayCron() throws NoSuchMethodException {
        Method method = EngineBatchRunner.class.getDeclaredMethod("scheduledRun");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.engine.eod-cron:0 30 16 * * MON-FRI}");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    private void seedNormalIvRankHistory(String symbol) {
        for (int i = 0; i < 60; i++) {
            double atmIv = 0.20 + (0.20 * i / 59.0);
            ivHistory.save(new IvHistory(symbol, TODAY.minusDays(60 - i), atmIv,
                    100.0, 100.0, EXPIRY, AS_OF));
        }
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

    private PriceHistory flatHistory(String symbol) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = TODAY.minusDays(119);
        for (int i = 0; i < 120; i++) {
            bars.add(new PriceBar(start.plusDays(i), 100.0, 100.5,
                    99.5, 100.0, 1_000_000 + i));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
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

    private OptionContract pricedContract(String symbol, CallPut type, double strike, double spot,
            double sigma) {
        double t = ChronoUnit.DAYS.between(TODAY, EXPIRY) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, 0.04, 0.0, sigma, type)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract("%s-%s%s".formatted(symbol, (int) strike, type == CallPut.CALL ? "C" : "P"),
                type, strike, EXPIRY, bid, ask, 1_000);
    }
}
