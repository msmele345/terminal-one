package com.terminalone.engine;

/**
 * Engine-level "no trade" reasons surfaced explicitly on the run response
 * (strategy-matrix §2: abstaining is a first-class output). Per-candidate
 * guardrail failures keep their own {@link GuardrailReason} code, bubbled up
 * verbatim when they are what makes a symbol abstain; §7's sizing abstain reuses
 * {@link PositionSizer#RISK_TOO_LARGE}.
 */
enum AbstainReason {
    /** §2 override: the directional signal is NEUTRAL, or conviction is below the trade floor. */
    WEAK_SIGNAL,
    /** §2 no-data abstain: the option chain / underlying price could not be sourced — never guess. */
    NO_MARKET_DATA,
    /** A structure was mapped but nothing qualified and no specific guardrail reason was recorded. */
    NO_QUALIFYING_CANDIDATE
}
