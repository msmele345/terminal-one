package com.terminalone.marketdata;

import java.time.LocalDate;

/**
 * One daily OHLCV bar of historical stock price (FR-8). Drives the per-symbol
 * console chart (Phase 3 AC #4) and, later, indicator/backtest computation.
 *
 * @param date   the trading day
 * @param open   session open
 * @param high   session high
 * @param low    session low
 * @param close  session close
 * @param volume shares traded
 */
public record PriceBar(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume) {
}
