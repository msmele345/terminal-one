package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigSeeder;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaperTradeSettlementServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(21, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private PaperTradeRepository paperTrades;

    @Autowired
    private PaperTradeService paperTradeService;

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
    void settlementMarksOpenPaperTradesWithUnrealizedPnlFromCurrentOptionMarks() {
        Recommendation recommendation = recommendations.save(recommendation(EXPIRY, 3.50, 2));
        paperTradeService.createForRecommendation(recommendation);
        when(marketData.getChain("AAPL")).thenReturn(new OptionChain("AAPL", 106.0, AS_OF, true, List.of(
                contract("AAPL-100C", CallPut.CALL, 100.0, 5.40, 5.60),
                contract("AAPL-110C", CallPut.CALL, 110.0, 0.90, 1.10))));

        paperTradeService.settleOpenTrades();

        assertThat(paperTrades.findAll()).singleElement().satisfies(trade -> {
            assertThat(trade.getStatus()).isEqualTo(PaperTrade.Status.OPEN);
            assertThat(trade.getLastMarkedAt()).isEqualTo(AS_OF);
            assertThat(trade.getMarkDebit()).isEqualByComparingTo("4.50");
            assertThat(trade.getUnrealizedPnl()).isEqualByComparingTo("200.00");
            assertThat(trade.getRealizedPnl()).isEqualByComparingTo("0.00");
            assertThat(trade.getClosedAt()).isNull();
        });
    }

    @Test
    void settlementClosesExpiredPaperTradesAndRecordsRealizedPnlFromIntrinsicValue() {
        Recommendation recommendation = recommendations.save(recommendation(TODAY, 3.50, 2));
        paperTradeService.createForRecommendation(recommendation);
        when(marketData.getChain("AAPL")).thenReturn(new OptionChain("AAPL", 112.0, AS_OF, true, List.of()));

        paperTradeService.settleOpenTrades();

        assertThat(paperTrades.findAll()).singleElement().satisfies(trade -> {
            assertThat(trade.getStatus()).isEqualTo(PaperTrade.Status.SETTLED);
            assertThat(trade.getLastMarkedAt()).isEqualTo(AS_OF);
            assertThat(trade.getClosedAt()).isEqualTo(AS_OF);
            assertThat(trade.getMarkDebit()).isEqualByComparingTo("10.00");
            assertThat(trade.getUnrealizedPnl()).isEqualByComparingTo("0.00");
            assertThat(trade.getRealizedPnl()).isEqualByComparingTo("1300.00");
        });
    }

    @Test
    void settlementJobUsesThePostCloseWeekdayCron() throws NoSuchMethodException {
        var method = com.terminalone.ledger.PaperTradeSettlementJob.class.getDeclaredMethod("scheduledSettlement");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.ledger.settlement-cron:0 0 17 * * MON-FRI}");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    private Recommendation recommendation(LocalDate expiry, double entryDebit, int contracts) {
        return new Recommendation(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60,
                RecommendationStatus.PAPER, 1, expiry,
                "AAPL-100C", 100.0, "AAPL-110C", 110.0,
                entryDebit, 0.72, 650.0, 350.0, 1.86, 85.0,
                contracts, "{}", AS_OF.minusSeconds(3600));
    }

    private OptionContract contract(String optionSymbol, CallPut type, double strike, double bid, double ask) {
        return new OptionContract(optionSymbol, type, strike, EXPIRY, bid, ask, 1_000);
    }
}
