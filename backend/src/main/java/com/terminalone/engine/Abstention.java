package com.terminalone.engine;

/**
 * An explicit "no trade" outcome for one underlying, returned on the engine-run
 * response so the caller sees <em>why</em> nothing was recommended (strategy-matrix
 * §2 — abstaining is a first-class output, never a silent drop).
 *
 * <p>{@code reason} is a stable UPPER_SNAKE code: an {@link AbstainReason}, a
 * {@link GuardrailReason} bubbled up from candidate selection, or
 * {@link PositionSizer#RISK_TOO_LARGE} from §7 sizing. {@code detail} carries the
 * human-readable specifics (the failing threshold, the weak-signal score, …).</p>
 */
record Abstention(String symbol, String reason, String detail) {
}
