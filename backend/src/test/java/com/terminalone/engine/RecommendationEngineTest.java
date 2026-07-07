package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    /** Every run now happens inside a recorded batch (AC6); tests emit into this list. */
    private static final long BATCH_RUN_ID = 7L;
    private List<SignalSnapshot> emittedSnapshots;

    @BeforeEach
    void setUp() throws Exception {
        engineConfig = EngineConfigDefaults.load();
        activeEngineConfig = new ActiveEngineConfig(1, engineConfig);
        engineRunRequest = new EngineRunRequest("AAPL");
        emittedSnapshots = new java.util.ArrayList<>();
        // Snapshot capture stamps LocalDate.now(clock) + clock.instant(); lenient
        // because the NO_MARKET_DATA path never reaches capture.
        lenient().when(clock.instant()).thenReturn(AS_OF);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private EngineRunResponse runEngine(EngineRunRequest request) {
        return recommendationEngine.run(request, BATCH_RUN_ID, emittedSnapshots::add);
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
        EngineRunResponse response = runEngine(engineRunRequest);

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

        // AC6: every run — lever-pull included — emits a signal snapshot per
        // underlying that reaches signal/regime computation.
        assertThat(emittedSnapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.getSymbol()).isEqualTo("AAPL");
            assertThat(snapshot.getBatchRunId()).isEqualTo(BATCH_RUN_ID);
        });

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
        EngineRunResponse response = runEngine(engineRunRequest);

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

        EngineRunResponse response = runEngine(engineRunRequest);

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

        EngineRunResponse response = runEngine(engineRunRequest);

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

        EngineRunResponse response = runEngine(engineRunRequest);

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
        EngineRunResponse response = runEngine(new EngineRunRequest(null));

        // Assert — globally ranked by score, not iteration order.
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations()).extracting(RecommendationResponse::symbol)
                .containsExactly("MSFT", "TSLA", "AAPL");
        assertThat(response.recommendations()).extracting(RecommendationResponse::score)
                .containsExactly(100.0, 75.0, 50.0);
        verify(recommendationRepository, org.mockito.Mockito.times(3)).save(any());
        verifyNoMoreInteractions(recommendationRepository);
    }

    /**
     * AC7 — the other {@code WEAK_SIGNAL} branch: a *directional* signal whose
     * conviction sits below the §3 trade floor abstains explicitly (the matrix
     * maps no cell), with the floor spelled out in the detail. The signal was
     * computed, so the snapshot is still emitted.
     */
    @Test
    void directionalConvictionBelowTheTradeFloorAbstainsWithWeakSignal() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of());
        when(positionSource.listOptions()).thenReturn(List.of());

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                new PriceBar(TODAY, 100.0, 102.0, 99.0, 101.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        // Bullish, but conviction 30 < the default trade floor of 40.
        DirectionSignal belowFloor = new DirectionSignal(
                Direction.BULLISH, 30, 0.30, 0.4, 0.3, 0.2, 101.0, 100.0, 0.2, 52.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(belowFloor);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(new VolatilityRegimeResult(VolatilityRegime.NORMAL, "IV_RANK", 0.40));

        EngineRunResponse response = runEngine(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.WEAK_SIGNAL.name());
            assertThat(abstention.detail()).contains("conviction 30")
                    .contains("trade floor of " + engineConfig.conviction().tradeFloor());
        });
        assertThat(emittedSnapshots).hasSize(1);
        verifyNoInteractions(strategySelector, incomeOverlaySelector, recommendationRepository);
    }

    /**
     * AC7 — §2 no-data abstain, price variant: a chain that exists but carries a
     * non-positive underlying price is as unusable as a missing chain; the engine
     * abstains {@code NO_MARKET_DATA} before any signal/regime work.
     */
    @Test
    void chainWithNonPositiveUnderlyingPriceAbstainsWithNoMarketData() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of());
        when(positionSource.listOptions()).thenReturn(List.of());
        when(marketDataProvider.getChain("AAPL"))
                .thenReturn(new OptionChain("AAPL", 0.0, AS_OF, true, List.of()));

        EngineRunResponse response = runEngine(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.NO_MARKET_DATA.name());
        });
        assertThat(emittedSnapshots).isEmpty();
        verifyNoInteractions(technicalSignalCalculator, volatilityRegimeCalculator,
                strategySelector, incomeOverlaySelector, recommendationRepository);
    }

    /**
     * AC7 — vendor-failure isolation across the fan-out: one symbol's provider
     * exception degrades to a {@code NO_MARKET_DATA} abstention for that symbol
     * only; the rest of the portfolio still trades.
     */
    @Test
    void vendorExceptionForOneSymbolAbstainsThatSymbolWithoutAbortingTheRun() throws Exception {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("100"), new BigDecimal("500.00"), TODAY),
                new StockPosition("MSFT", new BigDecimal("100"), new BigDecimal("500.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        // AAPL's vendor call blows up mid-run.
        when(marketDataProvider.getChain("AAPL")).thenThrow(new RuntimeException("vendor 500"));

        // MSFT resolves to a full sized candidate.
        PriceHistory history = new PriceHistory("MSFT", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 99.0, 101.0, 98.0, 100.0, 1_000_000),
                new PriceBar(TODAY, 100.0, 102.0, 99.0, 101.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("MSFT")).thenReturn(history);
        DirectionSignal signal = new DirectionSignal(
                Direction.BULLISH, 60, 0.75, 0.8, 0.7, 0.6, 102.0, 100.0, 0.5, 55.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(signal);
        OptionChain chain = new OptionChain("MSFT", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("MSFT")).thenReturn(chain);
        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        when(volatilityRegimeCalculator.calculate("MSFT", chain, history, engineConfig.regime()))
                .thenReturn(regime);
        RecommendationRationale rationale = new RecommendationRationale(
                new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", null),
                new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                new RecommendationRationale.Pricing(10.0, 3.50, 103.50, 0.72, 6.50, 350.0, 1.86, 1.34),
                null);
        RecommendationCandidate candidate = new RecommendationCandidate(
                "MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, EXPIRY,
                List.of(new RecommendationLeg("BUY", "MSFT-100C", CallPut.CALL, 100.0, EXPIRY,
                                5.0, 5.20, 5.10, 0.55),
                        new RecommendationLeg("SELL", "MSFT-110C", CallPut.CALL, 110.0, EXPIRY,
                                1.50, 1.70, 1.60, 0.30)),
                3.50, 0.72, 6.50, 350.0, 1.86, 85.0, 0, rationale);
        when(strategySelector.selectWithRejections(
                eq("MSFT"), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                eq(chain), eq(engineConfig)))
                .thenReturn(new CandidateSelection(Optional.of(candidate), List.of()));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(recommendationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act — run over the whole portfolio.
        EngineRunResponse response = runEngine(new EngineRunRequest(null));

        assertThat(response.recommendations()).singleElement()
                .satisfies(rec -> assertThat(rec.symbol()).isEqualTo("MSFT"));
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.NO_MARKET_DATA.name());
        });
        verify(recommendationRepository).save(any());
    }

    /**
     * AC7 — NEUTRAL × HIGH IV routes to the income overlay, and when nothing on
     * the chain qualifies (selector returns empty) the symbol abstains with an
     * explicit {@code NO_QUALIFYING_CANDIDATE}, never a silent drop.
     */
    @Test
    void neutralHighIvWithNoQualifyingIncomeOverlayAbstainsWithNoQualifyingCandidate() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        // 200 shares held → the covered-call arm is the one consulted.
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("200"), new BigDecimal("150.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 100.0, 100.5, 99.5, 100.0, 1_000_000),
                new PriceBar(TODAY, 100.0, 100.5, 99.5, 100.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        DirectionSignal neutral = new DirectionSignal(
                Direction.NEUTRAL, 20, 0.05, 0.1, 0.0, 0.0, 100.0, 100.0, 0.0, 50.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(neutral);
        VolatilityRegimeResult high = new VolatilityRegimeResult(VolatilityRegime.HIGH, "IV_RANK", 0.80);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(high);
        when(incomeOverlaySelector.selectCoveredCall(eq("AAPL"), eq(neutral), eq(high), eq(chain),
                eq(engineConfig), eq(200), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Optional.empty());

        EngineRunResponse response = runEngine(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.NO_QUALIFYING_CANDIDATE.name());
        });
        verifyNoInteractions(strategySelector, recommendationRepository);
    }

    /**
     * AC7 — the defensive tail of the guardrail bubble: if the selector comes
     * back empty without recording any rejection, the abstention falls back to
     * {@code NO_QUALIFYING_CANDIDATE} rather than inventing a guardrail reason.
     */
    @Test
    void selectorEmptyWithoutRecordedRejectionsFallsBackToNoQualifyingCandidate() {
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
        VolatilityRegimeResult regime = new VolatilityRegimeResult(VolatilityRegime.NORMAL, "IV_RANK", 0.40);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(regime);
        when(strategySelector.selectWithRejections(
                eq("AAPL"), eq(StrategyType.BULL_CALL_DEBIT_SPREAD), eq(signal), eq(regime),
                eq(chain), eq(engineConfig)))
                .thenReturn(new CandidateSelection(Optional.empty(), List.of()));

        EngineRunResponse response = runEngine(engineRunRequest);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.abstentions()).singleElement().satisfies(abstention -> {
            assertThat(abstention.symbol()).isEqualTo("AAPL");
            assertThat(abstention.reason()).isEqualTo(AbstainReason.NO_QUALIFYING_CANDIDATE.name());
        });
        verifyNoInteractions(recommendationRepository);
    }

    /**
     * AC7 — income eligibility at the engine seam: held shares aggregate across
     * every long lot of the underlying (60 + 60 = 120 → eligible), while short
     * lots are excluded from the count rather than netted (the positive-lot
     * filter in {@code heldShares} is deliberate — a short position elsewhere
     * must not block writing calls against shares actually held).
     */
    @Test
    void coveredCallEligibilityAggregatesHeldSharesAcrossLotsAndIgnoresShortLots() {
        when(engineConfigProvider.getActive()).thenReturn(activeEngineConfig);
        when(positionSource.listStocks()).thenReturn(List.of(
                new StockPosition("AAPL", new BigDecimal("60"), new BigDecimal("140.00"), TODAY),
                new StockPosition("AAPL", new BigDecimal("60"), new BigDecimal("160.00"), TODAY),
                new StockPosition("AAPL", new BigDecimal("-100"), new BigDecimal("150.00"), TODAY),
                new StockPosition("MSFT", new BigDecimal("500"), new BigDecimal("300.00"), TODAY)));
        when(positionSource.listOptions()).thenReturn(List.of());

        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                new PriceBar(TODAY.minusDays(1), 100.0, 100.5, 99.5, 100.0, 1_000_000),
                new PriceBar(TODAY, 100.0, 100.5, 99.5, 100.0, 1_000_000)));
        when(marketDataProvider.getDailyBars("AAPL")).thenReturn(history);
        OptionChain chain = new OptionChain("AAPL", 100.0, AS_OF, true, List.of());
        when(marketDataProvider.getChain("AAPL")).thenReturn(chain);

        DirectionSignal neutral = new DirectionSignal(
                Direction.NEUTRAL, 20, 0.05, 0.1, 0.0, 0.0, 100.0, 100.0, 0.0, 50.0);
        when(technicalSignalCalculator.calculate(eq(history), any())).thenReturn(neutral);
        VolatilityRegimeResult high = new VolatilityRegimeResult(VolatilityRegime.HIGH, "IV_RANK", 0.80);
        when(volatilityRegimeCalculator.calculate("AAPL", chain, history, engineConfig.regime()))
                .thenReturn(high);
        when(incomeOverlaySelector.selectCoveredCall(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Optional.empty());

        runEngine(engineRunRequest);

        // 60 + 60 long, the -100 short lot ignored → 120 held shares, covered-call arm.
        verify(incomeOverlaySelector).selectCoveredCall(eq("AAPL"), eq(neutral), eq(high),
                eq(chain), eq(engineConfig), eq(120), org.mockito.ArgumentMatchers.anyDouble());
        verify(incomeOverlaySelector, org.mockito.Mockito.never()).selectCashSecuredPut(
                any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble());
    }
}
