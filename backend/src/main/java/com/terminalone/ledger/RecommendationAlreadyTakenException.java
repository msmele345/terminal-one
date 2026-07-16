package com.terminalone.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The recommendation has already been promoted to a taken position (Phase 8 AC3). */
@ResponseStatus(HttpStatus.CONFLICT)
public class RecommendationAlreadyTakenException extends RuntimeException {

    public RecommendationAlreadyTakenException(long recommendationId) {
        super("Recommendation " + recommendationId + " has already been taken");
    }
}
