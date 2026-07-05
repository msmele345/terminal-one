package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import com.terminalone.portfolio.StockPosition;

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
    private VolatilityRegimeCalculator volatilityRegimeCalculator;

    @Mock
    private DirectionalStrategySelector strategySelector;

    @Mock
    private IncomeOverlaySelector incomeOverlaySelector;

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
    }

    @Test
    void bullishNormalUnderlyingProducesAndPersistsABullCallDebitSpread() throws Exception {
        // Arrange — config
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);

        when(clock.instant()).thenReturn(AS_OF);

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
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(regime);
        RecommendationLeg longLeg = new RecommendationLeg(
                "BUY", "AAPL-100C", CallPut.CALL, 100.0, EXPIRY, 5.0, 5.20, 5.10, 0.55);
        RecommendationLeg shortLeg = new RecommendationLeg(
                "SELL", "AAPL-110C", CallPut.CALL, 110.0, EXPIRY, 1.50, 1.70, 1.60, 0.30);

        RecommendationRationale rationale = new RecommendationRationale(
                new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", null),
                new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                new RecommendationRationale.Pricing(10.0, 3.50, 103.50, 0.72, 6.50, 350.0, 1.86, 1.34),
                null);

        RecommendationCandidate candidate = new RecommendationCandidate(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, EXPIRY,
                List.of(longLeg, shortLeg), 3.50, 0.72, 6.50, 350.0, 1.86, 85.0, 0, rationale);

        when(strategySelector.selectWithRejections(
                eq("AAPL"), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                eq(chain), eq(engineConfig)))
                .thenReturn(new CandidateSelection(Optional.of(candidate), List.of()));

        // Arrange — §10 Example A: $50k book × 3% = $1,500 budget; $350/contract max loss =
        // floor(1500/350) = 4 contracts.
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("100"), new BigDecimal("500.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        // Arrange — persisted entity (mock returns the contracts the engine sized to)
        Recommendation saved = new Recommendation(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60,
                RecommendationStatus.PAPER, 1, EXPIRY,
                "AAPL-100C", 100.0, "AAPL-110C", 110.0,
                3.50, 0.72, 6.50, 350.0, 1.86, 85.0, 4, "{}", AS_OF);
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
        assertThat(result.maxLoss()).isCloseTo(350.0, within(1e-4));
        assertThat(result.riskReward()).isCloseTo(1.86, within(1e-4));
        assertThat(result.score()).isCloseTo(85.0, within(1e-4));

        // AC5: the structure is sized to the per-trade-risk cap, not emitted unsized.
        assertThat(result.contracts()).isEqualTo(4);
        assertThat(result.rationale().sizing()).isNotNull();
        assertThat(result.rationale().sizing().contracts()).isEqualTo(4);
        assertThat(result.rationale().sizing().maxLossPerContract()).isCloseTo(350.0, within(1e-4));
        assertThat(result.rationale().sizing().portfolioValue()).isCloseTo(50_000.0, within(1e-4));

        // A qualifying underlying yields a trade, so nothing is abstained.
        assertThat(response.abstentions()).isEmpty();

        // Assert — persistence was invoked
        verify(recommendationRepository).save(any());
    }

    @Test
    void abstainsWhenASingleContractExceedsThePerTradeRiskCap() throws Exception {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(2), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                new PriceBar(TODAY.minusDays(1), 100.0, 102.0, 99.0, 101.0, 1_000_000),
                new PriceBar(TODAY, 101.0, 103.0, 100.0, 102.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);

        DirectionSignal signal = new DirectionSignal(
                Direction.BULLISH, 60, 0.75, 0.8, 0.7, 0.6,
                102.0, 100.0, 0.5, 55.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(signal);

        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(regime);

        RecommendationRationale rationale = new RecommendationRationale(
                new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", null),
                new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                new RecommendationRationale.Pricing(10.0, 3.50, 103.50, 0.72, 6.50, 3.50, 1.86, 1.34),
                null);
        RecommendationCandidate candidate = new RecommendationCandidate(
                "AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, EXPIRY,
                List.of(new RecommendationLeg("BUY", "AAPL-100C", CallPut.CALL, 100.0, EXPIRY,
                                5.0, 5.20, 5.10, 0.55)),
                3.50, 0.72, 6.50, 3.50, 1.86, 85.0, 0, rationale);
        when(strategySelector.selectWithRejections(
                eq("AAPL"), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                eq(chain), eq(engineConfig)))
                .thenReturn(new CandidateSelection(Optional.of(candidate), List.of()));

        // Tiny book — $100 × 3% = $3 budget < $350 per-contract max loss → RISK_TOO_LARGE.
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("1"), new BigDecimal("100.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        // Act
        EngineRunResponse response = recommendationEngine.run(engineRunRequest);

        // Assert — abstained, nothing persisted, and the §7 reason is returned explicitly.
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(PositionSizer.RISK_TOO_LARGE);
        });
        verifyNoInteractions(recommendationRepository);
        verifyNoInteractions(objectMapper);
    }

    /**
     * §10 Example B — a weak/neutral signal abstains explicitly. A NEUTRAL
     * direction at LOW/NORMAL IV maps to nothing on the matrix; the engine must
     * return a {@code WEAK_SIGNAL} abstention (never a silent drop) and touch
     * neither selector.
     */
    @Test
    void section10ExampleB_weakNeutralSignalAbstainsExplicitlyWithWeakSignal() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of()); // no shares held
        when(positionSource.listOptions()).thenReturn(List.of());

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 100.0, 100.5, 99.5, 100.0, 1_000_000),
                new PriceBar(TODAY, 100.0, 100.5, 99.5, 100.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);
        // Chain present (data is fine) — the abstain is about the weak signal, not missing data.
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        DirectionSignal neutral = new DirectionSignal(
                Direction.NEUTRAL, 22, -0.18, -0.2, -0.15, -0.1, 100.0, 100.0, -0.05, 47.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(neutral);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(new VolatilityRegimeResult(VolatilityRegime.NORMAL, "IV_RANK", 0.30));

        EngineRunResponse response = recommendationEngine.run(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.WEAK_SIGNAL.name());
        });
        verifyNoInteractions(strategySelector, incomeOverlaySelector, recommendationRepository);
    }

    /**
     * §2 no-data abstain — when the option chain can't be sourced, the engine
     * abstains {@code NO_MARKET_DATA} rather than guess, and never reaches the
     * signal, regime, or selection stages.
     */
    @Test
    void underlyingWithNoSourceableChainAbstainsWithNoMarketData() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of());
        when(positionSource.listOptions()).thenReturn(List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(null);

        EngineRunResponse response = recommendationEngine.run(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.NO_MARKET_DATA.name());
        });
        verifyNoInteractions(technicalSignalCalculator, volatilityRegimeCalculator,
                strategySelector, incomeOverlaySelector, recommendationRepository);
    }

    /**
     * A directional structure whose only candidate is rejected by a §6 guardrail
     * bubbles that guardrail reason up verbatim as the abstention (here
     * {@code NO_VALID_EXPIRY}), so the caller sees the precise cause.
     */
    @Test
    void directionalGuardrailRejectionBubblesUpAsAnExplicitAbstention() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of());
        when(positionSource.listOptions()).thenReturn(List.of());

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                new PriceBar(TODAY, 101.0, 103.0, 100.0, 102.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        DirectionSignal signal = new DirectionSignal(
                Direction.BULLISH, 60, 0.75, 0.8, 0.7, 0.6, 102.0, 100.0, 0.5, 55.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(signal);
        VolatilityRegimeResult regime = new VolatilityRegimeResult(VolatilityRegime.NORMAL, "IV_RANK", 0.30);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(regime);

        GuardrailRejection rejection = new GuardrailRejection("AAPL",
                StrategyType.BULL_CALL_DEBIT_SPREAD, GuardrailReason.NO_VALID_EXPIRY,
                "No expiration falls inside DTE window [35,70]");
        when(strategySelector.selectWithRejections(
                eq("AAPL"), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                eq(chain), eq(engineConfig)))
                .thenReturn(new CandidateSelection(Optional.empty(), List.of(rejection)));

        EngineRunResponse response = recommendationEngine.run(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(GuardrailReason.NO_VALID_EXPIRY.name());
        });
        verifyNoInteractions(recommendationRepository);
    }

    @Test
    void ranksMultipleUnderlyingsGloballyByScoreAndSurfacesOnlyTopN() throws Exception {
        // Three underlyings, three survivors with distinct §8 scores; default topN=3 keeps all,
        // asserted in descending score order regardless of iteration order.
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(clock.instant()).thenReturn(AS_OF);

        // Big enough book that none abstain on RISK_TOO_LARGE; three underlyings are
        // distinct portfolio symbols so symbolsFor iterates all three.
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("100"), new BigDecimal("500.00"), TODAY),
                new StockPosition("MSFT", new BigDecimal("100"), new BigDecimal("500.00"), TODAY),
                new StockPosition("TSLA", new BigDecimal("100"), new BigDecimal("500.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        // Symbols iterate in TreeSet order: AAPL, MSFT, TSLA. Assign scores 50 / 100 / 75
        // so the ranking reorder should be MSFT (100) → TSLA (75) → AAPL (50).
        String[] symbols = {"AAPL", "MSFT", "TSLA"};
        double[] scores = {50.0, 100.0, 75.0};
        for (int i = 0; i < symbols.length; i++) {
            String symbol = symbols[i];
            PriceHistory history = new PriceHistory(symbol, AS_OF, true, List.of(
                    new PriceBar(TODAY.minusDays(1), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                    new PriceBar(TODAY, 100.0, 102.0, 99.0, 101.0, 1_000_000)));
            when(marketDataProvider.getDailyBars(symbol)).thenReturn(history);

            DirectionSignal signal = new DirectionSignal(Direction.BULLISH, 60,
                    0.75, 0.8, 0.7, 0.6, 102.0, 100.0, 0.5, 55.0);
            when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(signal);

            OptionChain chain = new OptionChain(symbol, 100.0, AS_OF, true, List.of());
            when(marketDataProvider.getChain(symbol)).thenReturn(chain);

            VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
            when(volatilityRegimeCalculator.calculate(symbol, chain, history, engineConfig.regime()))
                    .thenReturn(regime);

            RecommendationLeg longLeg = new RecommendationLeg(
                    "BUY", symbol + "-100C", CallPut.CALL, 100.0, EXPIRY, 5.0, 5.20, 5.10, 0.55);
            RecommendationLeg shortLeg = new RecommendationLeg(
                    "SELL", symbol + "-110C", CallPut.CALL, 110.0, EXPIRY, 1.50, 1.70, 1.60, 0.30);
            // rawEV is positive for all three; score order is what differentiates them.
            RecommendationRationale rationale = new RecommendationRationale(
                    new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                    new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", null),
                    new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                    new RecommendationRationale.Pricing(10.0, 3.50, 103.50, 0.72, 6.50, 3.50, 1.86, 50.0),
                    null);
            RecommendationCandidate candidate = new RecommendationCandidate(
                    symbol, StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, EXPIRY,
                    List.of(longLeg, shortLeg), 3.50, 0.72, 6.50, 3.50, 1.86, scores[i], 0, rationale);
            when(strategySelector.selectWithRejections(
                    eq(symbol), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                    eq(chain), eq(engineConfig)))
                    .thenReturn(new CandidateSelection(Optional.of(candidate), List.of()));
        }

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(recommendationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act — null request means "run over all portfolio underlyings."
        EngineRunResponse response = recommendationEngine.run(new EngineRunRequest(null));

        // Assert — globally ranked by score, not iteration order.
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations()).extracting(RecommendationResponse::symbol)
                .containsExactly("MSFT", "TSLA", "AAPL");
        assertThat(response.recommendations()).extracting(RecommendationResponse::score)
                .containsExactly(100.0, 75.0, 50.0);
        verify(recommendationRepository, org.mockito.Mockito.times(3)).save(any());
        verifyNoMoreInteractions(recommendationRepository);
    }
}
