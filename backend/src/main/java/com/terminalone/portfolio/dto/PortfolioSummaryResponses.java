package com.terminalone.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response shapes for {@code GET /api/portfolio/summary} (FR-5): each position
 * carries its static fields (so the thin client can render and edit from one
 * payload) plus a delayed mark, market value, and unrealized P&amp;L ($ and %).
 *
 * <p>Computed money fields are nullable {@link Double}s: when a symbol can't be
 * priced (no quote, or the leg isn't in the returned chain), {@code priced} is
 * {@code false} and the money fields are {@code null} rather than a misleading 0.
 */
public final class PortfolioSummaryResponses {

    private PortfolioSummaryResponses() {
    }

    public record StockSummary(
            Long id,
            String kind,
            String symbol,
            BigDecimal quantity,
            BigDecimal costBasis,
            LocalDate openedDate,
            Double markPrice,
            Double marketValue,
            Double unrealizedPnl,
            Double unrealizedPnlPct,
            boolean priced) {
    }

    public record OptionSummary(
            Long id,
            String kind,
            String underlying,
            String optionType,
            BigDecimal strike,
            LocalDate expiry,
            BigDecimal quantity,
            BigDecimal costBasis,
            String side,
            LocalDate openedDate,
            Double markPrice,
            Double marketValue,
            Double unrealizedPnl,
            Double unrealizedPnlPct,
            boolean priced) {
    }

    /** Portfolio roll-up across all priced positions. {@code pct} is null when basis is 0. */
    public record Totals(
            double costValue,
            double marketValue,
            double unrealizedPnl,
            Double unrealizedPnlPct) {
    }

    /**
     * The full console payload.
     *
     * @param delayed     true if any priced quote/chain was a delayed vendor response
     * @param asOf        oldest (most stale) vendor timestamp among priced sources, or null
     * @param unpriced    count of positions that could not be priced
     */
    public record PortfolioSummary(
            List<StockSummary> stocks,
            List<OptionSummary> options,
            Totals totals,
            boolean delayed,
            Instant asOf,
            int unpriced) {
    }
}
