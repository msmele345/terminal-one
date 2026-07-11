package com.terminalone.engine;

enum GuardrailReason {
    NO_VALID_EXPIRY,
    ILLIQUID_BID_ASK,
    LOW_OPEN_INTEREST,
    MIN_CREDIT,
    POP_BELOW_FLOOR,
    REWARD_RISK_BELOW_FLOOR,
    EARNINGS_SPANS_EXPIRY
}
