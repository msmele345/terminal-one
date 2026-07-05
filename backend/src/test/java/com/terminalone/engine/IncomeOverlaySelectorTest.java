package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;

/**
 * Phase 6 AC1: the covered-call income overlay. The selector sells one call per
 * 100 held shares at the §4.4 0.30Δ target, inside the income DTE window, with
 * the upside cap called out explicitly — exercised here as a pure function over
 * one Black-Scholes-priced synthetic call chain (spot 100, σ 0.45 / HIGH IV).
 */
class IncomeOverlaySelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    /** 38 DTE — inside the income window [25, 50] and nearest its target midpoint (37.5). */
    private static final LocalDate INCOME_EXPIRY = TODAY.plusDays(38);
    private static final double SPOT = 100.0;
    private static final double HIGH_SIGMA = 0.45;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();
    private final IncomeOverlaySelector selector = new IncomeOverlaySelector(
            analytics, Clock.fixed(AS_OF, ZoneOffset.UTC));
    private final EngineConfig config = EngineConfigDefaults.load();

    @Test
    void sellsTheNearestThirtyDeltaCallSizedToHeldSharesWithCappedUpsideEconomics() {
        RecommendationCandidate candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), config, 250, 50_000.0).orElseThrow();

        assertThat(candidate.strategy()).isEqualTo(StrategyType.COVERED_CALL);
        assertThat(candidate.expiry()).isEqualTo(INCOME_EXPIRY);

        // One sold call, no bought leg — the shares are already held.
        assertThat(candidate.legs()).hasSize(1);
        RecommendationLeg leg = candidate.legs().get(0);
        assertThat(leg.action()).isEqualTo("SELL");
        assertThat(leg.callPut()).isEqualTo(CallPut.CALL);
        assertThat(leg.strike()).isGreaterThan(SPOT); // OTM income call
        assertThat(leg.strike()).isEqualTo(nearestDeltaCall(incomeCallChain("MSFT")).strike());

        // §4.4: target the 0.30Δ call; the sold delta lands near it.
        RecommendationRationale.Selection sel = candidate.rationale().selection();
        assertThat(sel.convictionBand()).isEqualTo("income");
        assertThat(sel.shortDeltaTarget()).isCloseTo(config.strikes().coveredCallDelta(), within(1e-9));
        assertThat(sel.selectedShortDelta()).isCloseTo(0.30, within(0.1));
        assertThat(sel.longDeltaTarget()).isEqualTo(0.0);

        // Sizing: floor(250 / 100) = 2 contracts.
        assertThat(candidate.contracts()).isEqualTo(2);
        assertThat(candidate.rationale().sizing().contracts()).isEqualTo(2);
        assertThat(candidate.rationale().sizing().portfolioValue()).isEqualTo(50_000.0);

        // Economics identities: premium is pure income (credit), upside caps at the strike,
        // and the overlay adds no defined downside beyond the already-held shares.
        double premium = leg.mid();
        assertThat(candidate.entryDebit()).isCloseTo(-premium, within(1e-9));
        assertThat(candidate.maxProfit()).isCloseTo((leg.strike() - SPOT + premium) * 100.0, within(1e-6));
        assertThat(candidate.maxLoss()).isEqualTo(0.0);
        assertThat(candidate.riskReward()).isEqualTo(0.0);
        assertThat(candidate.rationale().pricing().breakeven()).isCloseTo(SPOT - premium, within(1e-9));
        assertThat(candidate.rationale().pricing().width()).isEqualTo(0.0);
        assertThat(candidate.rationale().pricing().rawExpectedValue()).isCloseTo(premium * 100.0, within(1e-6));
        assertThat(candidate.probabilityOfProfit()).isGreaterThan(0.5);

        // Score folds the credit↔HIGH regime-fit match factor into the raw EV.
        assertThat(candidate.score())
                .isCloseTo(premium * 100.0 * config.ranking().regimeFitMatch(), within(1e-6));
    }

    @Test
    void stampsAnExplicitCappedUpsideRationaleConsistentWithTheLeg() {
        RecommendationCandidate candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), config, 200, 40_000.0).orElseThrow();

        RecommendationLeg leg = candidate.legs().get(0);
        RecommendationRationale.IncomeOverlay overlay = candidate.rationale().incomeOverlay();
        assertThat(overlay).isNotNull();
        assertThat(overlay.label()).isEqualTo("CAPS_UPSIDE_ABOVE_STRIKE");
        assertThat(overlay.note()).containsIgnoringCase("upside is capped");
        assertThat(overlay.heldShares()).isEqualTo(200);
        assertThat(overlay.contracts()).isEqualTo(2);
        assertThat(overlay.capStrike()).isEqualTo(leg.strike());
        assertThat(overlay.premiumPerShare()).isCloseTo(leg.mid(), within(1e-9));
        assertThat(overlay.cappedUpsidePerContract()).isCloseTo(candidate.maxProfit(), within(1e-9));
    }

    @ParameterizedTest(name = "held {0} shares -> no covered call")
    @ValueSource(ints = {0, 1, 50, 99})
    void requiresAtLeastOneHundredHeldShares(int heldShares) {
        Optional<RecommendationCandidate> candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), config, heldShares, 50_000.0);

        assertThat(candidate).isEmpty();
    }

    @ParameterizedTest(name = "held {0} shares -> {1} contracts")
    @CsvSource({
            "100, 1",
            "200, 2",
            "250, 2", // floor(250 / 100)
            "500, 5",
    })
    void sizesOneContractPerHundredHeldShares(int heldShares, int expectedContracts) {
        RecommendationCandidate candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), config, heldShares, 50_000.0).orElseThrow();

        assertThat(candidate.contracts()).isEqualTo(expectedContracts);
    }

    @Test
    void abstainsWhenNoExpiryFallsInsideTheIncomeWindow() {
        OptionChain chain = new OptionChain("MSFT", SPOT, AS_OF, true, List.of(
                priced(CallPut.CALL, 105.0, TODAY.plusDays(10), 1_000),
                priced(CallPut.CALL, 110.0, TODAY.plusDays(10), 1_000),
                priced(CallPut.CALL, 105.0, TODAY.plusDays(90), 1_000),
                priced(CallPut.CALL, 110.0, TODAY.plusDays(90), 1_000)));

        Optional<RecommendationCandidate> candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                chain, config, 200, 50_000.0);

        assertThat(candidate).isEmpty();
    }

    @Test
    void abstainsWhenProbabilityBelowTheShortStrikeIsUnderThePopFloor() {
        // A 0.30Δ short call finishes below-strike ~70% of the time; a 0.95 floor rejects it.
        EngineConfig strictPop = withMinPopCredit(0.95);

        Optional<RecommendationCandidate> candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), strictPop, 200, 50_000.0);

        assertThat(candidate).isEmpty();
    }

    @Test
    void returnsEmptyForMissingEmptyOrUnpricedChain() {
        OptionChain empty = new OptionChain("MSFT", SPOT, AS_OF, true, List.of());
        OptionChain unpriced = new OptionChain("MSFT", 0.0, AS_OF, true,
                List.of(priced(CallPut.CALL, 105.0, INCOME_EXPIRY, 1_000)));

        assertThat(selector.selectCoveredCall("MSFT", signal(Direction.NEUTRAL, 30),
                regimeResult(VolatilityRegime.HIGH), null, config, 200, 50_000.0)).isEmpty();
        assertThat(selector.selectCoveredCall("MSFT", signal(Direction.NEUTRAL, 30),
                regimeResult(VolatilityRegime.HIGH), empty, config, 200, 50_000.0)).isEmpty();
        assertThat(selector.selectCoveredCall("MSFT", signal(Direction.NEUTRAL, 30),
                regimeResult(VolatilityRegime.HIGH), unpriced, config, 200, 50_000.0)).isEmpty();
    }

    /**
     * §10 Example C — MSFT neutral high-IV covered call. A NEUTRAL signal
     * (conviction 30) under HIGH IV with 200 shares held resolves to a Covered
     * Call: sell the 0.30Δ call in the 30–45 DTE income window, 2 contracts
     * (200 shares / 100), premium is pure income flagged "caps upside above
     * strike." This encodes the worked example Phase 5 deferred until the
     * covered-call machinery existed; it pins the deterministic claims the engine
     * owns (mapping, delta target, sizing, credit sign, cap label), not the doc's
     * illustrative real-ticker dollar values.
     */
    @Test
    void section10ExampleC_neutralHighIvCoveredCall() {
        RecommendationCandidate candidate = selector.selectCoveredCall("MSFT",
                signal(Direction.NEUTRAL, 30), regimeResult(VolatilityRegime.HIGH),
                incomeCallChain("MSFT"), config, 200, 50_000.0).orElseThrow();

        assertThat(candidate.strategy()).isEqualTo(StrategyType.COVERED_CALL);
        assertThat(candidate.legs()).singleElement().satisfies(leg -> {
            assertThat(leg.action()).isEqualTo("SELL");
            assertThat(leg.callPut()).isEqualTo(CallPut.CALL);
        });
        assertThat(candidate.rationale().selection().shortDeltaTarget())
                .isCloseTo(0.30, within(1e-9));
        assertThat(candidate.rationale().selection().dte()).isBetween(30, 45);
        assertThat(candidate.contracts()).isEqualTo(2); // 200 shares / 100
        assertThat(candidate.entryDebit()).isNegative(); // credit received (pure income)
        assertThat(candidate.maxLoss()).isEqualTo(0.0);
        assertThat(candidate.rationale().incomeOverlay().label())
                .isEqualTo("CAPS_UPSIDE_ABOVE_STRIKE");
    }

    // ---- fixtures ----

    private DirectionSignal signal(Direction direction, int conviction) {
        double sign = direction == Direction.BEARISH ? -1.0 : 1.0;
        double score = sign * conviction / 100.0 * config.signal().convictionScale();
        return new DirectionSignal(direction, conviction, score, sign * 0.10, sign * 0.05,
                sign * 0.05, 100.5, 100.0, sign * 0.05, 50.0);
    }

    private VolatilityRegimeResult regimeResult(VolatilityRegime regime) {
        return new VolatilityRegimeResult(regime, "TEST_REGIME", 0.45);
    }

    /** OTM+ATM calls, strikes 100–135 in $5 steps, at one in-window income expiry. */
    private OptionChain incomeCallChain(String symbol) {
        List<OptionContract> contracts = new ArrayList<>();
        for (double strike = 100.0; strike <= 135.0; strike += 5.0) {
            contracts.add(priced(symbol, CallPut.CALL, strike, INCOME_EXPIRY, 1_000));
        }
        return new OptionChain(symbol, SPOT, AS_OF, true, contracts);
    }

    /** Replicates the selector's price→delta→nearest-target logic to pin the sold strike. */
    private OptionContract nearestDeltaCall(OptionChain chain) {
        double target = config.strikes().coveredCallDelta();
        return chain.contracts().stream()
                .filter(c -> c.callPut() == CallPut.CALL)
                .min(Comparator.comparingDouble(c -> Math.abs(deltaOf(chain.underlyingPrice(), c) - target)))
                .orElseThrow();
    }

    private double deltaOf(double spot, OptionContract c) {
        double t = ChronoUnit.DAYS.between(TODAY, c.expiration()) / 365.0;
        double iv = analytics.impliedVolatilityFromQuote(
                new OptionInput(spot, c.strike(), t, 0.04, 0.0, 0.0, CallPut.CALL), c.bid(), c.ask());
        return Math.abs(analytics.value(
                new OptionInput(spot, c.strike(), t, 0.04, 0.0, iv, CallPut.CALL)).delta());
    }

    private OptionContract priced(CallPut type, double strike, LocalDate expiry, int openInterest) {
        return priced("MSFT", type, strike, expiry, openInterest);
    }

    private OptionContract priced(String symbol, CallPut type, double strike, LocalDate expiry,
            int openInterest) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(SPOT, strike, t, 0.04, 0.0, HIGH_SIGMA, type)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract(symbol + "-" + type + "-" + strike + "-" + expiry, type, strike, expiry,
                bid, ask, openInterest);
    }

    private EngineConfig withMinPopCredit(double minPopCredit) {
        EngineConfig.Filters f = config.filters();
        return new EngineConfig(config.signal(), config.regime(), config.conviction(), config.strikes(),
                config.expiry(),
                new EngineConfig.Filters(f.maxBidAskPctOfMid(), f.maxBidAskAbsolute(), f.minOpenInterest(),
                        f.minCreditToWidthRatio(), minPopCredit, f.minRewardRiskDebit(), f.avoidEarnings()),
                config.sizing(), config.ranking());
    }
}
