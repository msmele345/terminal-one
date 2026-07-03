package com.terminalone.engine;

/**
 * The defined-risk structures of the strategy-matrix §2 directional cells
 * (D4). Income overlays (covered call / cash-secured put) arrive in Phase 6.
 */
public enum StrategyType {
    BULL_CALL_DEBIT_SPREAD,
    BEAR_PUT_DEBIT_SPREAD,
    BULL_PUT_CREDIT_SPREAD,
    BEAR_CALL_CREDIT_SPREAD,
    LONG_CALL,
    LONG_PUT
}
