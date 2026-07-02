package com.terminalone.engine.config;

/**
 * Which metric drives the volatility-regime read (§1.2). IV_RANK is primary;
 * BB_WIDTH_PCTL is the bootstrap proxy until enough IV history accumulates
 * (below {@code regime.minIvHistoryDays}); ATR_PCTL is corroboration.
 */
public enum RegimeMetric {
    IV_RANK,
    BB_WIDTH_PCTL,
    ATR_PCTL
}
