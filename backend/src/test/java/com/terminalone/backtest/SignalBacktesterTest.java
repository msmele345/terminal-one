package com.terminalone.backtest;

import com.terminalone.engine.TechnicalSignalCalculator;
import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 8 AC4 (FR-19, D8): the pure directional-signal backtest harness. It
 * reuses the production {@link TechnicalSignalCalculator}, so a persistent
 * uptrend must produce BULLISH calls that hit as price keeps rising, and a
 * flat/short series produces no evaluable signal at all.
 */
class SignalBacktesterTest {

    private static final Instant AS_OF = Instant.parse("2026-07-01T20:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 2, 1);

    private final EngineConfig.Signal config = EngineConfigDefaults.load().signal();
    private final SignalBacktester backtester = new SignalBacktester(new TechnicalSignalCalculator());

    @Test
    void persistentUptrendYieldsBullishHitsAndPositiveReturn() {
        // Monotonic ramp: every directional call is BULLISH and every 5-day
        // forward window is up, so the harness must score a perfect hit rate.
        PriceHistory history = history("AAPL", i -> 80.0 + i * 1.5);

        SignalBacktestSummary summary = backtester.backtest(history, config, 5);

        assertThat(summary.symbol()).isEqualTo("AAPL");
        assertThat(summary.horizonDays()).isEqualTo(5);
        assertThat(summary.barsAnalyzed()).isEqualTo(160);
        assertThat(summary.signalsEvaluated()).isPositive();
        assertThat(summary.bullishSignals()).isEqualTo(summary.signalsEvaluated());
        assertThat(summary.bearishSignals()).isZero();
        assertThat(summary.hits()).isEqualTo(summary.signalsEvaluated());
        assertThat(summary.misses()).isZero();
        assertThat(summary.hitRate()).isEqualTo(1.0);
        assertThat(summary.avgReturnPerSignal()).isPositive();
        assertThat(summary.cumulativeReturn()).isPositive();
    }

    @Test
    void persistentDowntrendYieldsBearishHitsAndPositiveDirectionalReturn() {
        // A falling series: BEARISH calls that keep paying off. Directional
        // return is measured in the signal's own direction, so it stays positive.
        PriceHistory history = history("TSLA", i -> 320.0 - i * 1.5);

        SignalBacktestSummary summary = backtester.backtest(history, config, 5);

        assertThat(summary.bearishSignals()).isEqualTo(summary.signalsEvaluated());
        assertThat(summary.bullishSignals()).isZero();
        assertThat(summary.hitRate()).isEqualTo(1.0);
        assertThat(summary.avgReturnPerSignal()).isPositive();
    }

    @Test
    void flatSeriesEvaluatesNoSignalsAndReportsNullHitRate() {
        PriceHistory history = history("MSFT", i -> 100.0);

        SignalBacktestSummary summary = backtester.backtest(history, config, 5);

        assertThat(summary.signalsEvaluated()).isZero();
        assertThat(summary.hits()).isZero();
        assertThat(summary.misses()).isZero();
        assertThat(summary.hitRate()).isNull();
        assertThat(summary.avgReturnPerSignal()).isCloseTo(0.0, within(1e-9));
        assertThat(summary.cumulativeReturn()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void seriesShorterThanTheForwardWindowEvaluatesNothing() {
        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                bar(START, 100.0),
                bar(START.plusDays(1), 101.0),
                bar(START.plusDays(2), 102.0)));

        SignalBacktestSummary summary = backtester.backtest(history, config, 5);

        assertThat(summary.barsAnalyzed()).isEqualTo(3);
        assertThat(summary.signalsEvaluated()).isZero();
        assertThat(summary.hitRate()).isNull();
    }

    @Test
    void rejectsANonPositiveHorizon() {
        PriceHistory history = history("AAPL", i -> 100.0 + i);

        assertThatThrownBy(() -> backtester.backtest(history, config, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PriceHistory history(String symbol, IntToDoubleFunction closeAt) {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            bars.add(bar(START.plusDays(i), closeAt.applyAsDouble(i)));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }

    private static PriceBar bar(LocalDate date, double close) {
        return new PriceBar(date, close - 0.50, close + 0.75, close - 1.00, close, 1_000_000);
    }
}
