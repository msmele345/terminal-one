package com.terminalone.engine;

import java.util.List;

public record EngineRunResponse(List<RecommendationResponse> recommendations) {
}
