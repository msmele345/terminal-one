package com.terminalone.engine;

public record DirectionSignal(
        Direction direction,
        int conviction,
        double directionScore,
        double trendVote,
        double macdVote,
        double rsiVote,
        double emaFast,
        double emaSlow,
        double macdHistogram,
        double rsi) {

    RecommendationRationale.Signals toRationale() {
        return new RecommendationRationale.Signals(
                trendVote,
                macdVote,
                rsiVote,
                directionScore,
                conviction,
                emaFast,
                emaSlow,
                macdHistogram,
                rsi);
    }
}
