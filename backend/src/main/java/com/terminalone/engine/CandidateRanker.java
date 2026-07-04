package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Phase 5 AC6 (§8): the pure global ranking objective. Surviving candidates
 * (already selected, guardrail-checked, and regime-fit-scored by
 * {@link DirectionalStrategySelector}) are sorted by their {@code score}
 * ({@code rawEV × regimeFit × convFactor}) descending, with tie-breakers:
 * higher POP → tighter liquidity (smaller relative bid/ask spread across legs)
 * → better reward:risk. Candidates with non-positive raw EV are dropped when
 * {@code requirePositiveEV} is set, and the surface is truncated to {@code topN}.
 *
 * <p>Pure and side-effect-free — the engine wires it after per-symbol selection
 * and §7 sizing, and before persistence.</p>
 */
final class CandidateRanker {

    private CandidateRanker() {
    }

    static List<RecommendationCandidate> rank(List<RecommendationCandidate> candidates,
                                               EngineConfig.Ranking ranking) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Stream<RecommendationCandidate> stream = candidates.stream();
        if (ranking.requirePositiveEV()) {
            stream = stream.filter(c -> c.rationale().pricing().rawExpectedValue() > 0.0);
        }
        int limit = Math.max(0, ranking.topN());
        return stream
                .sorted(BY_RANK)
                .limit(limit)
                .toList();
    }

    private static final Comparator<RecommendationCandidate> BY_RANK = Comparator
            .comparingDouble(RecommendationCandidate::score).reversed()
            .thenComparing(Comparator.comparingDouble(RecommendationCandidate::probabilityOfProfit).reversed())
            .thenComparingDouble(CandidateRanker::liquidity)
            .thenComparing(Comparator.comparingDouble(RecommendationCandidate::riskReward).reversed());

    /** Average relative bid/ask spread across legs (lower = tighter, better for tie-breaks). */
    private static double liquidity(RecommendationCandidate c) {
        double sum = 0.0;
        int n = 0;
        for (RecommendationLeg leg : c.legs()) {
            double mid = leg.mid();
            if (mid > 0.0) {
                // abs() guards crossed delayed quotes (bid > ask by a tick, per Phase 3):
                // width is a magnitude, so a crossed leg must never rank as "tightest".
                sum += Math.abs(leg.ask() - leg.bid()) / mid;
                n++;
            }
        }
        return n == 0 ? Double.POSITIVE_INFINITY : sum / n;
    }
}