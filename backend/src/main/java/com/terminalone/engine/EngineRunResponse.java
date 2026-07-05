package com.terminalone.engine;

import java.util.List;

/**
 * Result of a lever-pull / EOD run: the surfaced recommendations plus the
 * explicit {@link Abstention}s for every underlying that produced no trade
 * (strategy-matrix §2 — the engine says why it abstained, it never drops silently).
 */
public record EngineRunResponse(
        List<RecommendationResponse> recommendations,
        List<Abstention> abstentions) {

    public EngineRunResponse {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        abstentions = abstentions == null ? List.of() : List.copyOf(abstentions);
    }

    /** Back-compat for callers that only care about the surfaced recommendations. */
    public EngineRunResponse(List<RecommendationResponse> recommendations) {
        this(recommendations, List.of());
    }
}
