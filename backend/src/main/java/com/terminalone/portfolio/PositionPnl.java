package com.terminalone.portfolio;

import java.math.BigDecimal;

/**
 * Pure delayed-valuation math (FR-5): given a position's quantity/cost basis and
 * a current mark, compute market value and unrealized P&L ($ and %). No I/O —
 * every method is a deterministic function of its arguments, so the engine and
 * console can rely on it being unit-test-pinned.
 *
 * <p><b>Sign convention.</b> Market value and cost value are <em>signed by
 * exposure</em>: a long is a positive asset, a short option is a negative
 * liability financed by the credit collected. P&amp;L is always
 * {@code marketValue − costValue}, which holds for both. The percentage is taken
 * over the <em>absolute</em> capital basis (premium paid/collected, or share
 * cost), so a short that profits reads as a positive return on its premium.
 */
public final class PositionPnl {

    /** Standard equity option contract multiplier (shares per contract). */
    public static final double OPTION_MULTIPLIER = 100.0;

    private PositionPnl() {
    }

    /**
     * Market value + unrealized P&amp;L for a stock position. Quantity sign encodes
     * long (+) vs short (−) shares.
     *
     * @param quantity  shares held (signed)
     * @param costBasis per-share cost basis
     * @param markPrice current delayed price per share
     */
    public static Valuation forStock(BigDecimal quantity, BigDecimal costBasis, double markPrice) {
        double qty = quantity.doubleValue();
        double cost = costBasis.doubleValue();
        double marketValue = markPrice * qty;
        double costValue = cost * qty;
        double basis = Math.abs(costValue);
        return new Valuation(marketValue, costValue, marketValue - costValue, basis);
    }

    /**
     * Market value + unrealized P&amp;L for an option leg. Quantity is in contracts
     * (positive); {@code side} carries the long/short direction.
     *
     * @param quantity  contracts held (positive)
     * @param costBasis per-contract premium (debit if LONG, credit if SHORT)
     * @param side      LONG or SHORT
     * @param markPrice current delayed per-share option mark (e.g. bid/ask mid)
     */
    public static Valuation forOption(BigDecimal quantity, BigDecimal costBasis, PositionSide side, double markPrice) {
        double qty = quantity.doubleValue();
        double cost = costBasis.doubleValue();
        double sign = side == PositionSide.SHORT ? -1.0 : 1.0;
        double marketValue = sign * markPrice * qty * OPTION_MULTIPLIER;
        double costValue = sign * cost * qty * OPTION_MULTIPLIER;
        double basis = Math.abs(cost * qty * OPTION_MULTIPLIER);
        return new Valuation(marketValue, costValue, marketValue - costValue, basis);
    }

    /**
     * A position's delayed valuation. {@code basis} is the absolute capital base
     * used as the denominator for {@link #pnlPct()} (kept separate from the signed
     * {@code costValue} so shorts report a sensible return-on-premium).
     */
    public record Valuation(double marketValue, double costValue, double unrealizedPnl, double basis) {

        /** Unrealized P&amp;L as a percentage of capital basis, or {@code null} when basis is 0. */
        public Double pnlPct() {
            return basis > 0.0 ? unrealizedPnl / basis * 100.0 : null;
        }
    }
}
