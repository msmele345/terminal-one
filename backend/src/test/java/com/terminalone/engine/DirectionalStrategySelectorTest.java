package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;

/**
 * Phase 5 AC1: every directional cell of the strategy-matrix §2 grid yields the
 * correct structure for representative (direction × regime × conviction)
 * inputs, driven through the matrix mapping + structure selection over one
 * Black-Scholes-priced synthetic chain (spot 100, σ 0.30).
 */
class DirectionalStrategySelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    /** 38 DTE — inside the credit window [25, 50] and nearest its target midpoint. */
    private static final LocalDate CREDIT_EXPIRY = TODAY.plusDays(38);
    /** 51 DTE — inside the debit/long window [35, 70] and nearest its target midpoint. */
    private static final LocalDate DEBIT_EXPIRY = LocalDate.of(2026, 8, 21);
    private static final double SPOT = 100.0;
    private static final double SIGMA = 0.30;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();
    private final DirectionalStrategySelector selector = new DirectionalStrategySelector(
            analytics, Clock.fixed(AS_OF, ZoneOffset.UTC));
    private final EngineConfig config = EngineConfigDefaults.load();

    private record Leg(String action, CallPut callPut, double strike) {
    }

    static Stream<Arguments> directionalCells() {
        return Stream.of(
                // §2 BULLISH row
                Arguments.of(Direction.BULLISH, VolatilityRegime.LOW, 45,
                        StrategyType.BULL_CALL_DEBIT_SPREAD, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.CALL, 100.0), new Leg("SELL", CallPut.CALL, 110.0))),
                Arguments.of(Direction.BULLISH, VolatilityRegime.NORMAL, 45,
                        StrategyType.BULL_CALL_DEBIT_SPREAD, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.CALL, 100.0), new Leg("SELL", CallPut.CALL, 110.0))),
                Arguments.of(Direction.BULLISH, VolatilityRegime.LOW, 85,
                        StrategyType.LONG_CALL, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.CALL, 95.0))),
                Arguments.of(Direction.BULLISH, VolatilityRegime.HIGH, 65,
                        StrategyType.BULL_PUT_CREDIT_SPREAD, CREDIT_EXPIRY,
                        List.of(new Leg("SELL", CallPut.PUT, 95.0), new Leg("BUY", CallPut.PUT, 90.0))),
                // §2 BEARISH row
                Arguments.of(Direction.BEARISH, VolatilityRegime.LOW, 45,
                        StrategyType.BEAR_PUT_DEBIT_SPREAD, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.PUT, 100.0), new Leg("SELL", CallPut.PUT, 95.0))),
                Arguments.of(Direction.BEARISH, VolatilityRegime.NORMAL, 45,
                        StrategyType.BEAR_PUT_DEBIT_SPREAD, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.PUT, 100.0), new Leg("SELL", CallPut.PUT, 95.0))),
                Arguments.of(Direction.BEARISH, VolatilityRegime.LOW, 85,
                        StrategyType.LONG_PUT, DEBIT_EXPIRY,
                        List.of(new Leg("BUY", CallPut.PUT, 105.0))),
                Arguments.of(Direction.BEARISH, VolatilityRegime.HIGH, 65,
                        StrategyType.BEAR_CALL_CREDIT_SPREAD, CREDIT_EXPIRY,
                        List.of(new Leg("SELL", CallPut.CALL, 105.0), new Leg("BUY", CallPut.CALL, 110.0))));
    }

    @ParameterizedTest(name = "{0} + {1} IV @ conviction {2} -> {3}")
    @MethodSource("directionalCells")
    void directionalCellYieldsTheCorrectStructure(Direction direction, VolatilityRegime regime,
            int conviction, StrategyType expectedStrategy, LocalDate expectedExpiry,
            List<Leg> expectedLegs) {
        StrategyType strategy = DirectionalStrategyMatrix
                .select(direction, regime, conviction, config.conviction()).orElseThrow();
        assertThat(strategy).isEqualTo(expectedStrategy);

        RecommendationCandidate candidate = selector.select("AAPL", strategy,
                signal(direction, conviction), regimeResult(regime), fullChain(), config).orElseThrow();

        assertThat(candidate.symbol()).isEqualTo("AAPL");
        assertThat(candidate.strategy()).isEqualTo(expectedStrategy);
        assertThat(candidate.expiry()).isEqualTo(expectedExpiry);
        assertThat(candidate.legs()).hasSize(expectedLegs.size());
        for (int i = 0; i < expectedLegs.size(); i++) {
            Leg expected = expectedLegs.get(i);
            RecommendationLeg actual = candidate.legs().get(i);
            assertThat(actual.action()).as("leg %d action", i).isEqualTo(expected.action());
            assertThat(actual.callPut()).as("leg %d type", i).isEqualTo(expected.callPut());
            assertThat(actual.strike()).as("leg %d strike", i).isEqualTo(expected.strike());
        }
        assertThat(candidate.legs()).filteredOn(l -> l.action().equals("BUY")).hasSize(1);

        // entry price sign follows the structure: debit paid > 0, credit received < 0
        boolean credit = expectedStrategy == StrategyType.BULL_PUT_CREDIT_SPREAD
                || expectedStrategy == StrategyType.BEAR_CALL_CREDIT_SPREAD;
        if (credit) {
            assertThat(candidate.entryDebit()).isNegative();
        } else {
            assertThat(candidate.entryDebit()).isPositive();
        }

        assertThat(candidate.probabilityOfProfit()).isStrictlyBetween(0.0, 1.0);
        assertThat(candidate.maxProfit()).isPositive();
        assertThat(candidate.maxLoss()).isPositive();
        assertThat(candidate.riskReward())
                .isCloseTo(candidate.maxProfit() / candidate.maxLoss(), within(1e-6));
        assertThat(candidate.score()).isFinite();

        RecommendationRationale rationale = candidate.rationale();
        assertThat(rationale.regime().value()).isEqualTo(regime);
        assertThat(rationale.signals().conviction()).isEqualTo(conviction);
        assertThat(rationale.selection().dte())
                .isEqualTo((int) ChronoUnit.DAYS.between(TODAY, expectedExpiry));
    }

    @Test
    void creditSpreadEconomicsFollowTheShortStrikeAndWidth() {
        RecommendationCandidate candidate = selector.select("AAPL",
                StrategyType.BULL_PUT_CREDIT_SPREAD, signal(Direction.BULLISH, 65),
                regimeResult(VolatilityRegime.HIGH), fullChain(), config).orElseThrow();

        double credit = -candidate.entryDebit();
        double width = 5.0;
        assertThat(credit).isStrictlyBetween(0.0, width);
        assertThat(candidate.maxProfit()).isCloseTo(credit * 100.0, within(1e-6));
        assertThat(candidate.maxLoss()).isCloseTo((width - credit) * 100.0, within(1e-6));
        // bull put spread profits when the underlying holds above short strike − credit
        assertThat(candidate.rationale().pricing().breakeven()).isCloseTo(95.0 - credit, within(1e-9));
        assertThat(candidate.probabilityOfProfit()).isGreaterThan(0.5);
        assertThat(candidate.rationale().pricing().width()).isCloseTo(width, within(1e-9));
        assertThat(candidate.rationale().selection().convictionBand()).isEqualTo("confident");
        assertThat(candidate.rationale().selection().shortDeltaTarget()).isCloseTo(0.30, within(1e-9));
        // the protection leg is width-driven (§4.1), not delta-targeted
        assertThat(candidate.rationale().selection().longDeltaTarget()).isEqualTo(0.0);
    }

    @Test
    void longSingleLegBuysTheTargetDeltaWithBoundedModeledProfit() {
        RecommendationCandidate candidate = selector.select("AAPL", StrategyType.LONG_CALL,
                signal(Direction.BULLISH, 85), regimeResult(VolatilityRegime.LOW), fullChain(), config)
                .orElseThrow();

        assertThat(candidate.legs()).hasSize(1);
        RecommendationLeg leg = candidate.legs().get(0);
        assertThat(leg.action()).isEqualTo("BUY");
        assertThat(leg.strike()).isEqualTo(95.0);

        double premium = candidate.entryDebit();
        assertThat(premium).isPositive();
        assertThat(candidate.maxLoss()).isCloseTo(premium * 100.0, within(1e-6));
        assertThat(candidate.maxProfit()).isFinite();
        assertThat(candidate.maxProfit()).isPositive();
        assertThat(candidate.rationale().pricing().breakeven()).isCloseTo(95.0 + premium, within(1e-9));
        assertThat(candidate.rationale().pricing().width()).isEqualTo(0.0);
        assertThat(candidate.rationale().selection().longDeltaTarget()).isCloseTo(0.65, within(1e-9));
        assertThat(candidate.rationale().selection().shortDeltaTarget()).isEqualTo(0.0);
        assertThat(candidate.rationale().selection().selectedShortDelta()).isEqualTo(0.0);
    }

    // ---- Phase 5 AC3: conviction bands shift strike deltas per §4 ----

    /**
     * §4.2 — across all three conviction bands, a debit vertical targets the
     * long/short-leg deltas from the config's per-band ladder (safer at low
     * conviction, more aggressive at high).
     */
    @ParameterizedTest(name = "conviction {0} -> {1} band, long {2}Δ / short {3}Δ")
    @CsvSource({
            "45, standard,  0.50, 0.25",
            "65, confident, 0.55, 0.28",
            "85, high,      0.62, 0.30",
    })
    void debitConvictionBandsShiftDeltaTargetsPerSection4(int conviction, String band,
            double longTarget, double shortTarget) {
        RecommendationCandidate candidate = selector.select("AAPL",
                StrategyType.BULL_CALL_DEBIT_SPREAD, signal(Direction.BULLISH, conviction),
                regimeResult(VolatilityRegime.NORMAL), fullChain(), config).orElseThrow();

        RecommendationRationale.Selection sel = candidate.rationale().selection();
        assertThat(sel.convictionBand()).isEqualTo(band);
        assertThat(sel.longDeltaTarget()).isCloseTo(longTarget, within(1e-9));
        assertThat(sel.shortDeltaTarget()).isCloseTo(shortTarget, within(1e-9));
    }

    /**
     * §4.1 — across all three conviction bands, a credit vertical targets the
     * short-leg delta from the config's per-band ladder; the protection leg is
     * width-driven, so it carries no delta target.
     */
    @ParameterizedTest(name = "conviction {0} -> {1} band, short {2}Δ")
    @CsvSource({
            "45, standard,  0.20",
            "65, confident, 0.30",
            "85, high,      0.38",
    })
    void creditConvictionBandsShiftShortDeltaTargetPerSection4(int conviction, String band,
            double shortTarget) {
        RecommendationCandidate candidate = selector.select("AAPL",
                StrategyType.BULL_PUT_CREDIT_SPREAD, signal(Direction.BULLISH, conviction),
                regimeResult(VolatilityRegime.HIGH), fullChain(), config).orElseThrow();

        RecommendationRationale.Selection sel = candidate.rationale().selection();
        assertThat(sel.convictionBand()).isEqualTo(band);
        assertThat(sel.shortDeltaTarget()).isCloseTo(shortTarget, within(1e-9));
        assertThat(sel.longDeltaTarget()).isEqualTo(0.0);
    }

    /**
     * The band delta targets have teeth: on a fine ($1-increment) chain, higher
     * conviction sells closer to the money — a strictly higher short-put strike
     * and larger |delta| as the band climbs standard → confident → high.
     */
    @Test
    void higherConvictionSelectsMoreAggressiveStrikes() {
        OptionChain fine = fineGrainedPutChain();
        RecommendationCandidate standard = creditAt(45, fine);
        RecommendationCandidate confident = creditAt(65, fine);
        RecommendationCandidate high = creditAt(85, fine);

        assertThat(shortStrike(standard))
                .isLessThan(shortStrike(confident));
        assertThat(shortStrike(confident))
                .isLessThan(shortStrike(high));

        assertThat(standard.rationale().selection().selectedShortDelta())
                .isLessThan(confident.rationale().selection().selectedShortDelta());
        assertThat(confident.rationale().selection().selectedShortDelta())
                .isLessThan(high.rationale().selection().selectedShortDelta());
    }

    /**
     * The §2/§4.3 unlock: the long single leg surfaces only at conviction ≥ 80
     * <em>and</em> LOW IV. One below the threshold, or outside LOW IV, resolves
     * to the two-leg vertical instead. Composed matrix → selector so the leg
     * count reflects the actual built structure.
     */
    @Test
    void longSingleLegUnlocksOnlyAtHighConvictionAndLowIv() {
        // one below the unlock, LOW IV -> two-leg debit vertical
        StrategyType belowUnlock = DirectionalStrategyMatrix
                .select(Direction.BULLISH, VolatilityRegime.LOW, 79, config.conviction()).orElseThrow();
        assertThat(belowUnlock).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
        assertThat(selector.select("AAPL", belowUnlock, signal(Direction.BULLISH, 79),
                regimeResult(VolatilityRegime.LOW), fullChain(), config).orElseThrow().legs())
                .hasSize(2);

        // at the unlock, LOW IV -> single-leg long
        StrategyType unlocked = DirectionalStrategyMatrix
                .select(Direction.BULLISH, VolatilityRegime.LOW, 80, config.conviction()).orElseThrow();
        assertThat(unlocked).isEqualTo(StrategyType.LONG_CALL);
        assertThat(selector.select("AAPL", unlocked, signal(Direction.BULLISH, 80),
                regimeResult(VolatilityRegime.LOW), fullChain(), config).orElseThrow().legs())
                .hasSize(1);

        // high conviction alone never unlocks a long outside LOW IV
        assertThat(DirectionalStrategyMatrix.select(Direction.BULLISH, VolatilityRegime.NORMAL, 100,
                config.conviction())).contains(StrategyType.BULL_CALL_DEBIT_SPREAD);
        assertThat(DirectionalStrategyMatrix.select(Direction.BULLISH, VolatilityRegime.HIGH, 100,
                config.conviction())).contains(StrategyType.BULL_PUT_CREDIT_SPREAD);
    }

    private RecommendationCandidate creditAt(int conviction, OptionChain chain) {
        return selector.select("AAPL", StrategyType.BULL_PUT_CREDIT_SPREAD,
                signal(Direction.BULLISH, conviction), regimeResult(VolatilityRegime.HIGH), chain, config)
                .orElseThrow();
    }

    private static double shortStrike(RecommendationCandidate candidate) {
        return candidate.legs().stream()
                .filter(l -> "SELL".equals(l.action()))
                .map(RecommendationLeg::strike)
                .findFirst()
                .orElseThrow();
    }

    // ---- ported Phase 4 cases, on the generalized selector ----

    @Test
    void selectsBullCallDebitSpreadByDebitDteWindowAndConfidentDeltaTargets() {
        DirectionSignal signal = signal(Direction.BULLISH, 65);
        VolatilityRegimeResult regime = regimeResult(VolatilityRegime.NORMAL);
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                priced(CallPut.CALL, 90.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 95.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 100.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 105.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 110.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 115.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 100.0, TODAY.plusDays(39), 1_000),
                priced(CallPut.CALL, 110.0, TODAY.plusDays(39), 1_000)));

        RecommendationCandidate candidate = selector.select("AAPL",
                StrategyType.BULL_CALL_DEBIT_SPREAD, signal, regime, chain, config).orElseThrow();

        assertThat(candidate.strategy()).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
        assertThat(candidate.signal()).isSameAs(signal);
        assertThat(candidate.regime()).isSameAs(regime);
        assertThat(candidate.expiry()).isEqualTo(DEBIT_EXPIRY);
        assertThat(candidate.legs()).hasSize(2);
        assertThat(candidate.legs().get(0).strike()).isEqualTo(100.0);
        assertThat(candidate.legs().get(1).strike()).isEqualTo(110.0);

        double width = 10.0;
        assertThat(candidate.maxLoss()).isCloseTo(candidate.entryDebit() * 100.0, within(1e-6));
        assertThat(candidate.maxProfit()).isCloseTo((width - candidate.entryDebit()) * 100.0, within(1e-6));

        RecommendationRationale rationale = candidate.rationale();
        assertThat(rationale.signals().directionScore()).isEqualTo(signal.directionScore());
        assertThat(rationale.selection().convictionBand()).isEqualTo("confident");
        assertThat(rationale.selection().dte()).isEqualTo(51);
        assertThat(rationale.selection().longDeltaTarget()).isCloseTo(0.55, within(1e-9));
        assertThat(rationale.selection().shortDeltaTarget()).isCloseTo(0.28, within(1e-9));
        assertThat(rationale.pricing().width()).isCloseTo(width, within(1e-9));
        assertThat(rationale.pricing().entryDebit()).isCloseTo(candidate.entryDebit(), within(1e-9));
    }

    @Test
    void highConvictionUsesHighBandDebitDeltaTargets() {
        RecommendationCandidate candidate = selector.select("AAPL",
                StrategyType.BULL_CALL_DEBIT_SPREAD, signal(Direction.BULLISH, 85),
                regimeResult(VolatilityRegime.NORMAL), fullChain(), config).orElseThrow();

        assertThat(candidate.rationale().selection().convictionBand()).isEqualTo("high");
        assertThat(candidate.rationale().selection().longDeltaTarget()).isCloseTo(0.62, within(1e-9));
        assertThat(candidate.rationale().selection().shortDeltaTarget()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void returnsEmptyWhenNoExpiryFallsInsideTheDebitWindow() {
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                priced(CallPut.CALL, 100.0, TODAY.plusDays(20), 1_000),
                priced(CallPut.CALL, 110.0, TODAY.plusDays(20), 1_000),
                priced(CallPut.CALL, 100.0, TODAY.plusDays(90), 1_000),
                priced(CallPut.CALL, 110.0, TODAY.plusDays(90), 1_000)));

        assertThat(selector.select("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                signal(Direction.BULLISH, 65), regimeResult(VolatilityRegime.NORMAL), chain, config))
                .isEmpty();
    }

    @Test
    void creditSpreadsUseTheCreditDteWindowNotTheDebitOne() {
        // 51 DTE is valid for debit structures but outside the credit window [25, 50]
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                priced(CallPut.PUT, 85.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.PUT, 90.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.PUT, 95.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.PUT, 100.0, DEBIT_EXPIRY, 1_000)));

        assertThat(selector.select("AAPL", StrategyType.BULL_PUT_CREDIT_SPREAD,
                signal(Direction.BULLISH, 65), regimeResult(VolatilityRegime.HIGH), chain, config))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenOpenInterestFiltersOutARequiredLeg() {
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                priced(CallPut.CALL, 100.0, DEBIT_EXPIRY, 1_000),
                priced(CallPut.CALL, 110.0, DEBIT_EXPIRY, 99)));

        assertThat(selector.select("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                signal(Direction.BULLISH, 65), regimeResult(VolatilityRegime.NORMAL), chain, config))
                .isEmpty();
    }

    @Test
    void returnsEmptyOnMissingOrEmptyChain() {
        DirectionSignal signal = signal(Direction.BULLISH, 65);
        VolatilityRegimeResult regime = regimeResult(VolatilityRegime.NORMAL);
        OptionChain empty = new OptionChain("AAPL", SPOT, AS_OF, true, List.of());

        assertThat(selector.select("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                signal, regime, null, config)).isEmpty();
        assertThat(selector.select("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                signal, regime, empty, config)).isEmpty();
    }

    // ---- fixtures ----

    private DirectionSignal signal(Direction direction, int conviction) {
        double sign = direction == Direction.BEARISH ? -1.0 : 1.0;
        double score = sign * conviction / 100.0 * config.signal().convictionScale();
        return new DirectionSignal(direction, conviction, score, sign * 0.80, sign * 0.70,
                sign * 0.60, 102.0, 100.0, sign * 0.50, 50.0 + sign * 8.0);
    }

    private VolatilityRegimeResult regimeResult(VolatilityRegime regime) {
        return new VolatilityRegimeResult(regime, "TEST_REGIME", null);
    }

    /** Calls + puts, strikes 75–125 in $5 steps, at one credit-window and one debit-window expiry. */
    private OptionChain fullChain() {
        List<OptionContract> contracts = new ArrayList<>();
        for (LocalDate expiry : List.of(CREDIT_EXPIRY, DEBIT_EXPIRY)) {
            for (double strike = 75.0; strike <= 125.0; strike += 5.0) {
                contracts.add(priced(CallPut.CALL, strike, expiry, 1_000));
                contracts.add(priced(CallPut.PUT, strike, expiry, 1_000));
            }
        }
        return new OptionChain("AAPL", SPOT, AS_OF, true, contracts);
    }

    /** Puts only, strikes 80–105 in $1 steps at the credit-window expiry — fine
     * enough that the per-band short-delta targets resolve to distinct strikes. */
    private OptionChain fineGrainedPutChain() {
        List<OptionContract> contracts = new ArrayList<>();
        for (double strike = 80.0; strike <= 105.0; strike += 1.0) {
            contracts.add(priced(CallPut.PUT, strike, CREDIT_EXPIRY, 1_000));
        }
        return new OptionChain("AAPL", SPOT, AS_OF, true, contracts);
    }

    private OptionContract priced(CallPut type, double strike, LocalDate expiry, int openInterest) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(SPOT, strike, t, 0.04, 0.0, SIGMA, type)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract("AAPL-" + type + "-" + strike + "-" + expiry, type, strike, expiry,
                bid, ask, openInterest);
    }
}
