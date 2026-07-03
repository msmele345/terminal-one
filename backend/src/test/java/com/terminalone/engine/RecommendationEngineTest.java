package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terminalone.engine.config.ActiveEngineConfig;
import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.engine.config.EngineConfigProvider;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import com.terminalone.portfolio.PositionSource;

@ExtendWith(MockitoExtension.class)
class RecommendationEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Mock
    private PositionSource positionSource;

    @Mock
    private EngineConfigProvider engineConfigProvider;

    @Mock
    private Clock clock;

    @Mock
    private MarketDataProvider marketDataProvider;

    @Mock
    private TechnicalSignalCalculator technicalSignalCalculator;

    @Mock
    private BullCallDebitSpreadSelector bullCallDebitSpreadSelector;

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RecommendationEngine recommendationEngine;

    private EngineConfig engineConfig;
    private ActiveEngineConfig activeEngineConfig;
    private EngineRunRequest engineRunRequest;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = EngineConfigDefaults.load();
        activeEngineConfig = new ActiveEngineConfig(1, engineConfig);
        engineRunRequest = new EngineRunRequest("AAPL");
        when(clock.instant()).thenReturn(AS_OF);
    }

    @Test
    void bullishNormalUnderlyingProducesAndPersistsABullCallDebitSpread() throws Exception {
        // Arrange — config
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);

        // Arrange — market data (bullish price history)
        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(2), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                new PriceBar(TODAY.minusDays(1), 100.0, 102.0, 99.0, 101.0, 1_000_000),
                new PriceBar(TODAY, 101.0, 103.0, 100.0, 102.0, 1_000_000)
        ));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);

        // Arrange — bullish signal above trade floor
        DirectionSignal signal = new DirectionSignal(
                Direction.BULLISH, 60, 0.75, 0.8, 0.7, 0.6,
                102.0, 100.0, 0.5, 55.0
        );
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(signal);

        // Arrange — option chain (contents don't matter; selector is mocked)
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        // Arrange — candidate returned by selector
        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        RecommendationLeg longLeg = new RecommendationLeg(
                "BUY", "AAPL-100C", CallPut.CALL, 100.0, EXPIRY, 5.0, 5.20, 5.10, 0.55);
        RecommendationLeg shortLeg = new RecommendationLeg(
                "SELL", "AAPL-110C", CallPut.CALL, 110.0, EXPIRY, 1.50, 1.70, 1.60, 0.30);

        RecommendationRationale rationale = new RecommendationRationale(
                new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", null),
                new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                new RecommendationRationale.Pricing(10.0, 3.50, 103.50, 0.72, 6.50, 3.50, 1.86, 1.34));

        RecommendationCandidate candidate = new RecommendationCandidate(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, EXPIRY,
                List.of(longLeg, shortLeg), 3.50, 0.72, 6.50, 3.50, 1.86, 85.0, rationale);

        when(bullCallDebitSpreadSelector.select(
                eq("AAPL"), eq(signal), eq(regime), eq(chain), eq(engineConfig)))
                .thenReturn(Optional.of(candidate));

        // Arrange — persisted entity
        Recommendation saved = new Recommendation(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60,
                RecommendationStatus.PAPER, 1, EXPIRY,
                "AAPL-100C", 100.0, "AAPL-110C", 110.0,
                3.50, 0.72, 6.50, 3.50, 1.86, 85.0, "{}", AS_OF);
        when(recommendationRepository.save(any())).thenReturn(saved);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Act
        EngineRunResponse response = recommendationEngine.run(engineRunRequest);

        // Assert — response shape
        assertThat(response.recommendations()).hasSize(1);
        RecommendationResponse result = response.recommendations().get(0);
        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.strategy()).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
        assertThat(result.direction()).isEqualTo(Direction.BULLISH);
        assertThat(result.regime()).isEqualTo(VolatilityRegime.NORMAL);
        assertThat(result.configVersion()).isEqualTo(1);
        assertThat(result.expiry()).isEqualTo(EXPIRY);
        assertThat(result.legs()).hasSize(2);
        assertThat(result.legs().get(0).action()).isEqualTo("BUY");
        assertThat(result.legs().get(1).action()).isEqualTo("SELL");
        assertThat(result.entryDebit()).isCloseTo(3.50, within(1e-4));
        assertThat(result.probabilityOfProfit()).isCloseTo(0.72, within(1e-4));
        assertThat(result.maxProfit()).isCloseTo(6.50, within(1e-4));
        assertThat(result.maxLoss()).isCloseTo(3.50, within(1e-4));
        assertThat(result.riskReward()).isCloseTo(1.86, within(1e-4));
        assertThat(result.score()).isCloseTo(85.0, within(1e-4));

        // Assert — persistence was invoked
        verify(recommendationRepository).save(any());
    }
}
