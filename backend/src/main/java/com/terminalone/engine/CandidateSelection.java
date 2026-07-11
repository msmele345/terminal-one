package com.terminalone.engine;

import java.util.List;
import java.util.Optional;

record CandidateSelection(
        Optional<RecommendationCandidate> candidate,
        List<GuardrailRejection> rejections) {
}
