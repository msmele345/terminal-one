package com.terminalone.engine;

public record RecommendationRationale(
        Signals signals,
        Regime regime,
        Selection selection,
        Pricing pricing,
        Sizing sizing,
        IncomeOverlay incomeOverlay,
        EntrySuggestion entrySuggestion) {

    public RecommendationRationale(
            Signals signals,
            Regime regime,
            Selection selection,
            Pricing pricing,
            Sizing sizing) {
        this(signals, regime, selection, pricing, sizing, null, null);
    }

    public RecommendationRationale(
            Signals signals,
            Regime regime,
            Selection selection,
            Pricing pricing,
            Sizing sizing,
            IncomeOverlay incomeOverlay) {
        this(signals, regime, selection, pricing, sizing, incomeOverlay, null);
    }

    /** Phase 5 AC5 (§7): how many contracts the engine sized the structure to. */
    public record Sizing(
            int contracts,
            double maxLossPerContract,
            double portfolioValue,
            double perTradeRiskPct,
            double riskAmount) {
    }

    /** Phase 6 AC1: explicit user-facing label for income-overlay mechanics. */
    public record IncomeOverlay(
            String label,
            String note,
            int heldShares,
            int contracts,
            double capStrike,
            double premiumPerShare,
            double cappedUpsidePerContract) {
    }

    /**
     * Phase 6 AC2: a cash-secured-put entry suggestion. V1 does not track a cash
     * balance, so the CSP is always presentable but flagged with the collateral it
     * requires (strike × 100 × contracts) — never assuming the cash is on hand.
     */
    public record EntrySuggestion(
            String label,
            String note,
            int contracts,
            double strike,
            double premiumPerShare,
            double requiredCapital) {
    }

    public record Signals(
            double trendVote,
            double macdVote,
            double rsiVote,
            double directionScore,
            int conviction,
            double emaFast,
            double emaSlow,
            double macdHistogram,
            double rsi) {
    }

    public record Regime(
            VolatilityRegime value,
            String reason,
            Double currentIv) {
    }

    public record Selection(
            String convictionBand,
            int dte,
            double longDeltaTarget,
            double shortDeltaTarget,
            double selectedLongDelta,
            double selectedShortDelta) {
    }

    public record Pricing(
            double width,
            double entryDebit,
            double breakeven,
            double probabilityOfProfit,
            double maxProfit,
            double maxLoss,
            double riskReward,
            double rawExpectedValue) {
    }

    /** Selector-built rationales carry no sizing; the engine fills it after §7 sizing runs. */
    public RecommendationRationale withSizing(Sizing sizing) {
        return new RecommendationRationale(signals, regime, selection, pricing, sizing, incomeOverlay,
                entrySuggestion);
    }
}
