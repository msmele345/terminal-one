package com.terminalone.engine;

/** The option structures surfaced by the deterministic strategy matrix. */
public enum StrategyType {
    BULL_CALL_DEBIT_SPREAD,
    BEAR_PUT_DEBIT_SPREAD,
    BULL_PUT_CREDIT_SPREAD,
    BEAR_CALL_CREDIT_SPREAD,
    LONG_CALL,
    LONG_PUT,
    COVERED_CALL;

    /** Credit spreads receive premium; debit/long structures pay it. Drives the §8 regime-fit lookup. */
    public boolean isCredit() {
        return this == BULL_PUT_CREDIT_SPREAD || this == BEAR_CALL_CREDIT_SPREAD;
    }
}
