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
        int contracts,
        RecommendationRationale rationale) {

    /** Selector-built candidates carry no sizing; the engine stamps it after §7 sizing runs. */
    RecommendationCandidate withSizing(int contracts, RecommendationRationale sized) {
        return new RecommendationCandidate(symbol, strategy, signal, regime, expiry, legs, entryDebit,
                probabilityOfProfit, maxProfit, maxLoss, riskReward, score, contracts, sized);
    }
}
