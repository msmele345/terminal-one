package com.terminalone.backtest;

/**
 * Request body for {@code POST /api/backtest/signal} (Phase 8 AC4).
 *
 * @param symbol      the ticker to backtest (required)
 * @param horizonDays forward window per signal; null defaults to
 *                    {@link SignalBacktestService#DEFAULT_HORIZON_DAYS}
 */
public record BacktestRequest(String symbol, Integer horizonDays) {
}
