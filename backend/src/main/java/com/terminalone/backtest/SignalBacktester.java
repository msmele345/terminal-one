package com.terminalone.backtest;

import com.terminalone.engine.Direction;
import com.terminalone.engine.DirectionSignal;
import com.terminalone.engine.TechnicalSignalCalculator;
import com.terminalone.engine.config.EngineConfig;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure directional-signal backtest harness (Phase 8 AC4, FR-19). Reuses the
 * production {@link TechnicalSignalCalculator} so the backtest measures exactly
 * the signal the engine trades on — no parallel implementation to drift.
 *
 * <p>Deterministic and side-effect free: same bars + config → same summary.
 */
@Component
public class SignalBacktester {

    private final TechnicalSignalCalculator signals;

    public SignalBacktester(TechnicalSignalCalculator signals) {
        this.signals = signals;
    }

    /**
     * Walk {@code history} bar by bar. At each day with a full {@code horizonDays}
     * forward window, compute the signal from bars[0..i] and score its directional
     * call against the realized move to bars[i+horizonDays].
     *
     * @throws IllegalArgumentException if {@code horizonDays < 1} or history is null
     */
    public SignalBacktestSummary backtest(PriceHistory history, EngineConfig.Signal config, int horizonDays) {
        if (history == null) {
            throw new IllegalArgumentException("history is required");
        }
        if (horizonDays < 1) {
            throw new IllegalArgumentException("horizonDays must be >= 1");
        }

        List<PriceBar> bars = history.bars() == null ? List.of() : history.bars();
        int n = bars.size();
        int signalsEvaluated = 0;
        int bullish = 0;
        int bearish = 0;
        int hits = 0;
        double cumulativeReturn = 0.0;

        for (int i = 0; i + horizonDays < n; i++) {
            PriceHistory window = new PriceHistory(
                    history.symbol(), history.asOf(), history.delayed(), bars.subList(0, i + 1));
            DirectionSignal signal = signals.calculate(window, config);
            if (signal.direction() == Direction.NEUTRAL) {
                continue;
            }

            double entry = bars.get(i).close();
            double exit = bars.get(i + horizonDays).close();
            if (!(entry > 0.0) || !Double.isFinite(exit)) {
                continue;
            }

            double forwardReturn = (exit - entry) / entry;
            double directionalReturn = signal.direction() == Direction.BULLISH ? forwardReturn : -forwardReturn;

            signalsEvaluated++;
            if (signal.direction() == Direction.BULLISH) {
                bullish++;
            } else {
                bearish++;
            }
            if (directionalReturn > 0.0) {
                hits++;
            }
            cumulativeReturn += directionalReturn;
        }

        Double hitRate = signalsEvaluated == 0 ? null : (double) hits / signalsEvaluated;
        double avgReturn = signalsEvaluated == 0 ? 0.0 : cumulativeReturn / signalsEvaluated;

        return new SignalBacktestSummary(
                history.symbol(), horizonDays, n, signalsEvaluated, bullish, bearish,
                hits, signalsEvaluated - hits, hitRate, avgReturn, cumulativeReturn);
    }
}
