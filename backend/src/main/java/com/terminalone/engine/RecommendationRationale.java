package com.terminalone.engine;

public record RecommendationRationale(
        Signals signals,
        Regime regime,
        Selection selection,
        Pricing pricing,
        Sizing sizing) {

    /** Phase 5 AC5 (§7): how many contracts the engine sized the structure to. */
    public record Sizing(
            int contracts,
            double maxLossPerContract,
            double portfolioValue,
            double perTradeRiskPct,
            double riskAmount) {
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
        return new RecommendationRationale(signals, regime, selection, pricing, sizing);
    }
}
