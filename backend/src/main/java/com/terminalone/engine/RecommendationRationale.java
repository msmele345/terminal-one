package com.terminalone.engine;

public record RecommendationRationale(
        Signals signals,
        Regime regime,
        Selection selection,
        Pricing pricing) {

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
}
