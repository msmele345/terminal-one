package com.terminalone.engine;

import java.time.LocalDate;
import java.util.List;

public record RecommendationResponse(
        Long id,
        String symbol,
        StrategyType strategy,
        Direction direction,
        VolatilityRegime regime,
        int conviction,
        int configVersion,
        LocalDate expiry,
        List<RecommendationLeg> legs,
        double entryDebit,
        double probabilityOfProfit,
        double maxProfit,
        double maxLoss,
        double riskReward,
        double score,
        RecommendationRationale rationale) {

    static RecommendationResponse from(Recommendation saved, RecommendationCandidate candidate) {
        return new RecommendationResponse(
                saved.getId(),
                saved.getSymbol(),
                saved.getStrategy(),
                saved.getDirection(),
                saved.getRegime(),
                saved.getConviction(),
                saved.getConfigVersion(),
                saved.getExpiry(),
                candidate.legs(),
                saved.getEntryDebit(),
                saved.getProbabilityOfProfit(),
                saved.getMaxProfit(),
                saved.getMaxLoss(),
                saved.getRiskReward(),
                saved.getScore(),
                candidate.rationale());
    }
}
