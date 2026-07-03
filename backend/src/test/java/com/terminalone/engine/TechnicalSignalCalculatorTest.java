package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;

class TechnicalSignalCalculatorTest {

    private static final Instant AS_OF = Instant.parse("2026-07-01T20:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 2, 1);

    private final TechnicalSignalCalculator calculator = new TechnicalSignalCalculator();
    private final EngineConfig.Signal config = EngineConfigDefaults.load().signal();

    @Test
    void acceleratingUptrendProducesBullishDirectionAndHighConviction() {
        DirectionSignal signal = calculator.calculate(
                history("AAPL", i -> 80.0 + Math.max(0, i - 80) * 3.0),
                config);

        assertThat(signal.direction()).isEqualTo(Direction.BULLISH);
        assertThat(signal.conviction()).isGreaterThanOrEqualTo(80);
        assertThat(signal.directionScore()).isGreaterThan(config.directionThreshold());
        assertThat(signal.trendVote()).isPositive();
        assertThat(signal.macdVote()).isPositive();
        assertThat(signal.rsi()).isEqualTo(100.0);
        assertThat(signal.rsiVote()).isCloseTo(0.50, within(1e-9));
        assertThat(signal.emaFast()).isGreaterThan(signal.emaSlow());
    }

    @Test
    void acceleratingDowntrendProducesBearishDirectionAndHighConviction() {
        DirectionSignal signal = calculator.calculate(
                history("AAPL", i -> 220.0 - Math.max(0, i - 80) * 3.0),
                config);

        assertThat(signal.direction()).isEqualTo(Direction.BEARISH);
        assertThat(signal.conviction()).isGreaterThanOrEqualTo(80);
        assertThat(signal.directionScore()).isLessThan(-config.directionThreshold());
        assertThat(signal.trendVote()).isNegative();
        assertThat(signal.macdVote()).isNegative();
        assertThat(signal.rsi()).isEqualTo(0.0);
        assertThat(signal.rsiVote()).isCloseTo(-0.50, within(1e-9));
        assertThat(signal.emaFast()).isLessThan(signal.emaSlow());
    }

    @Test
    void flatPriceHistoryStaysNeutralWithZeroConviction() {
        DirectionSignal signal = calculator.calculate(history("AAPL", i -> 100.0), config);

        assertThat(signal.direction()).isEqualTo(Direction.NEUTRAL);
        assertThat(signal.conviction()).isZero();
        assertThat(signal.directionScore()).isCloseTo(0.0, within(1e-9));
        assertThat(signal.trendVote()).isCloseTo(0.0, within(1e-9));
        assertThat(signal.macdVote()).isCloseTo(0.0, within(1e-9));
        assertThat(signal.rsi()).isEqualTo(50.0);
        assertThat(signal.rsiVote()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void insufficientHistoryReturnsNeutralNoSignal() {
        PriceHistory history = new PriceHistory("AAPL", AS_OF, true, List.of(
                bar(START, 100.0),
                bar(START.plusDays(1), 101.0),
                bar(START.plusDays(2), 102.0)));

        DirectionSignal signal = calculator.calculate(history, config);

        assertThat(signal.direction()).isEqualTo(Direction.NEUTRAL);
        assertThat(signal.conviction()).isZero();
        assertThat(signal.directionScore()).isCloseTo(0.0, within(1e-9));
        assertThat(signal.rsi()).isEqualTo(50.0);
    }

    private static PriceHistory history(String symbol, IntToDoubleFunction closeAt) {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            bars.add(bar(START.plusDays(i), closeAt.applyAsDouble(i)));
        }
        return new PriceHistory(symbol, AS_OF, true, bars);
    }

    private static PriceBar bar(LocalDate date, double close) {
        return new PriceBar(date, close - 0.50, close + 0.75, close - 1.00, close, 1_000_000);
    }
}
