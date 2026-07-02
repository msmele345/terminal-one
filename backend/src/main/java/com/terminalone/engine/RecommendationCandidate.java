package com.terminalone.engine;

import java.time.LocalDate;
import java.util.List;

record RecommendationCandidate(
        String symbol,
        StrategyType strategy,
        DirectionSignal signal,
        VolatilityRegimeResult regime,
        LocalDate expiry,
        List<RecommendationLeg> legs,
        double entryDebit,
        double probabilityOfProfit,
        double maxProfit,
        double maxLoss,
        double riskReward,
        double score,
        RecommendationRationale rationale) {
}
