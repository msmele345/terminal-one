package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;

class BullCallDebitSpreadSelectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate TARGET_EXPIRY = LocalDate.of(2026, 8, 21);
    private static final double SPOT = 100.0;
    private static final double SIGMA = 0.30;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();
    private final BullCallDebitSpreadSelector selector = new BullCallDebitSpreadSelector(
            analytics, Clock.fixed(AS_OF, ZoneOffset.UTC));
    private final EngineConfig config = EngineConfigDefaults.load();

    @Test
    void selectsBullCallDebitSpreadByDebitDteWindowAndConfidentDeltaTargets() {
        DirectionSignal signal = bullishSignal(65);
        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                pricedCall(90.0, TARGET_EXPIRY, 1_000),
                pricedCall(95.0, TARGET_EXPIRY, 1_000),
                pricedCall(100.0, TARGET_EXPIRY, 1_000),
                pricedCall(105.0, TARGET_EXPIRY, 1_000),
                pricedCall(110.0, TARGET_EXPIRY, 1_000),
                pricedCall(115.0, TARGET_EXPIRY, 1_000),
                pricedCall(100.0, TODAY.plusDays(39), 1_000),
                pricedCall(110.0, TODAY.plusDays(39), 1_000)));

        Optional<RecommendationCandidate> selected = selector.select("AAPL", signal, regime, chain, config);

        assertThat(selected).isPresent();
        RecommendationCandidate candidate = selected.orElseThrow();
        assertThat(candidate.symbol()).isEqualTo("AAPL");
        assertThat(candidate.strategy()).isEqualTo(StrategyType.BULL_CALL_DEBIT_SPREAD);
        assertThat(candidate.signal()).isSameAs(signal);
        assertThat(candidate.regime()).isSameAs(regime);
        assertThat(candidate.expiry()).isEqualTo(TARGET_EXPIRY);
        assertThat(candidate.legs()).hasSize(2);
        assertThat(candidate.legs().get(0)).satisfies(leg -> {
            assertThat(leg.action()).isEqualTo("BUY");
            assertThat(leg.callPut()).isEqualTo(CallPut.CALL);
            assertThat(leg.strike()).isEqualTo(100.0);
        });
        assertThat(candidate.legs().get(1)).satisfies(leg -> {
            assertThat(leg.action()).isEqualTo("SELL");
            assertThat(leg.callPut()).isEqualTo(CallPut.CALL);
            assertThat(leg.strike()).isEqualTo(110.0);
        });

        double width = 10.0;
        assertThat(candidate.entryDebit()).isPositive();
        assertThat(candidate.probabilityOfProfit()).isBetween(0.0, 1.0);
        assertThat(candidate.maxLoss()).isCloseTo(candidate.entryDebit() * 100.0, within(1e-6));
        assertThat(candidate.maxProfit()).isCloseTo((width - candidate.entryDebit()) * 100.0, within(1e-6));
        assertThat(candidate.riskReward()).isCloseTo(candidate.maxProfit() / candidate.maxLoss(), within(1e-6));
        assertThat(candidate.score()).isFinite();

        RecommendationRationale rationale = candidate.rationale();
        assertThat(rationale.signals().directionScore()).isEqualTo(signal.directionScore());
        assertThat(rationale.regime().value()).isEqualTo(VolatilityRegime.NORMAL);
        assertThat(rationale.regime().reason()).isEqualTo("PHASE4_SINGLE_CELL_NORMAL");
        assertThat(rationale.selection().convictionBand()).isEqualTo("confident");
        assertThat(rationale.selection().dte()).isEqualTo(51);
        assertThat(rationale.selection().longDeltaTarget()).isCloseTo(0.55, within(1e-9));
        assertThat(rationale.selection().shortDeltaTarget()).isCloseTo(0.28, within(1e-9));
        assertThat(rationale.pricing().width()).isCloseTo(width, within(1e-9));
        assertThat(rationale.pricing().entryDebit()).isCloseTo(candidate.entryDebit(), within(1e-9));
        assertThat(rationale.pricing().maxProfit()).isCloseTo(candidate.maxProfit(), within(1e-6));
        assertThat(rationale.pricing().maxLoss()).isCloseTo(candidate.maxLoss(), within(1e-6));
    }

    @Test
    void highConvictionUsesHighBandDebitDeltaTargets() {
        DirectionSignal signal = bullishSignal(85);
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                pricedCall(90.0, TARGET_EXPIRY, 1_000),
                pricedCall(95.0, TARGET_EXPIRY, 1_000),
                pricedCall(100.0, TARGET_EXPIRY, 1_000),
                pricedCall(105.0, TARGET_EXPIRY, 1_000),
                pricedCall(110.0, TARGET_EXPIRY, 1_000),
                pricedCall(115.0, TARGET_EXPIRY, 1_000)));

        RecommendationCandidate candidate = selector.select("AAPL", signal,
                VolatilityRegimeResult.phase4Normal(null), chain, config).orElseThrow();

        assertThat(candidate.rationale().selection().convictionBand()).isEqualTo("high");
        assertThat(candidate.rationale().selection().longDeltaTarget()).isCloseTo(0.62, within(1e-9));
        assertThat(candidate.rationale().selection().shortDeltaTarget()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void returnsEmptyWhenNoExpiryFallsInsideDebitWindow() {
        DirectionSignal signal = bullishSignal(65);
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                pricedCall(100.0, TODAY.plusDays(20), 1_000),
                pricedCall(110.0, TODAY.plusDays(20), 1_000),
                pricedCall(100.0, TODAY.plusDays(90), 1_000),
                pricedCall(110.0, TODAY.plusDays(90), 1_000)));

        assertThat(selector.select("AAPL", signal, VolatilityRegimeResult.phase4Normal(null), chain, config))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenOpenInterestFiltersOutARequiredLeg() {
        DirectionSignal signal = bullishSignal(65);
        OptionChain chain = new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                pricedCall(100.0, TARGET_EXPIRY, 1_000),
                pricedCall(110.0, TARGET_EXPIRY, 99)));

        assertThat(selector.select("AAPL", signal, VolatilityRegimeResult.phase4Normal(null), chain, config))
                .isEmpty();
    }

    private DirectionSignal bullishSignal(int conviction) {
        double score = conviction / 100.0 * config.signal().convictionScale();
        return new DirectionSignal(Direction.BULLISH, conviction, score, 0.80, 0.70, 0.60,
                102.0, 100.0, 0.50, 58.0);
    }

    private OptionContract pricedCall(double strike, LocalDate expiry, int openInterest) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(SPOT, strike, t, 0.04, 0.0, SIGMA, CallPut.CALL)).price();
        double bid = Math.max(0.01, price - 0.02);
        double ask = Math.max(bid + 0.02, price + 0.02);
        return new OptionContract("AAPL-" + strike + "-" + expiry, CallPut.CALL, strike, expiry,
                bid, ask, openInterest);
    }
}
