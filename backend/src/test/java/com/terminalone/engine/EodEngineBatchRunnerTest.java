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
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EodEngineBatchRunnerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    @Autowired
    private EodEngineBatchRunner runner;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private StockPositionRepository stocks;

    @Autowired
    private IvHistoryRepository ivHistory;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private EngineBatchRunRepository batchRuns;

    @Autowired
    private SignalSnapshotRepository signalSnapshots;

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

    @Test
    void scheduledRunUsesThePostCloseWeekdayCron() throws NoSuchMethodException {
        Method method = EodEngineBatchRunner.class.getDeclaredMethod("scheduledRun");
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
