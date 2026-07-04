package com.terminalone.engine;

/**
 * Phase 5 AC5 — strategy-matrix §7. A pure function that sizes a defined-risk
 * structure to the {@code perTradeRiskPct} cap of the portfolio: contracts are
 * {@code max(1, floor((portfolioValue × perTradeRiskPct) / maxLossPerContract))}.
 * If a single contract already overdraws the cap, sizing abstains with
 * {@link #RISK_TOO_LARGE} rather than recommend an oversized position.
 *
 * <p>No state, no I/O — fully deterministic, so the engine and the rationale
 * can rely on it being unit-pinned.</p>
 */
public final class PositionSizer {

    /** Guardrail reason emitted when one contract already exceeds the risk cap. */
    public static final String RISK_TOO_LARGE = "RISK_TOO_LARGE";

    private PositionSizer() {
    }

    /**
     * @param maxLossPerContract defined max loss for one contract ($, &gt; 0 to size)
     * @param portfolioValue     dollar value the engine has at risk across the book (&ge; 0)
     * @param perTradeRiskPct    {@code engine.sizing.perTradeRiskPct}, fraction in (0, 1]
     * @return the sized contracts, or an abstain sizing when one would exceed the cap
     */
    public static Sizing size(double maxLossPerContract, double portfolioValue, double perTradeRiskPct) {
        double riskBudget = portfolioValue * perTradeRiskPct;
        if (maxLossPerContract <= 0.0 || riskBudget <= 0.0 || maxLossPerContract > riskBudget) {
            return new Sizing(true, 0, 0.0, RISK_TOO_LARGE);
        }
        // max(1, floor(budget / per-contract loss)) — at least one contract,
        // since the cap above already proves one fits.
        long contracts = Math.max(1L, (long) Math.floor(riskBudget / maxLossPerContract));
        double riskAmount = contracts * maxLossPerContract;
        return new Sizing(false, (int) contracts, riskAmount, null);
    }

    /**
     * Sizing outcome for a surviving candidate.
     *
     * @param abstain     true one contract already exceeds the cap (skip persisting)
     * @param contracts   recommended contracts; {@code 0} when abstaining
     * @param riskAmount  dollar risk the sized position takes (&Sigma; per-contract max loss)
     * @param reason      {@link #RISK_TOO_LARGE} when abstaining, otherwise {@code null}
     */
    public record Sizing(boolean abstain, int contracts, double riskAmount, String reason) {
    }
}