package com.terminalone.backtest;

/**
 * Directional-signal backtest result (Phase 8 AC4, FR-19, D8): the technical
 * signal ({@link com.terminalone.engine.TechnicalSignalCalculator}) walked over
 * historical daily bars, scored against the realized forward return.
 *
 * <p>At each decision point the signal is computed from every bar up to that day
 * and its call is checked {@code horizonDays} later: a BULLISH call "hits" when
 * price rose, a BEARISH call when it fell. Neutral days are not trades and are
 * excluded from the tally.
 *
 * @param symbol             the backtested ticker
 * @param horizonDays        forward window each signal is measured over
 * @param barsAnalyzed       total daily bars fed to the harness
 * @param signalsEvaluated   directional (non-neutral) signals with a forward window
 * @param bullishSignals     of those, how many were BULLISH
 * @param bearishSignals     of those, how many were BEARISH
 * @param hits               directional signals whose forward move went their way
 * @param misses             directional signals that went against
 * @param hitRate            hits / signalsEvaluated, or null when nothing was evaluated
 * @param avgReturnPerSignal mean forward return in the signal's own direction
 * @param cumulativeReturn   sum of directional forward returns across all signals
 */
public record SignalBacktestSummary(
        String symbol,
        int horizonDays,
        int barsAnalyzed,
        int signalsEvaluated,
        int bullishSignals,
        int bearishSignals,
        int hits,
        int misses,
        Double hitRate,
        double avgReturnPerSignal,
        double cumulativeReturn) {
}
