package com.terminalone.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No recommendation exists for the id supplied to "mark as taken" (Phase 8 AC3). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RecommendationNotFoundException extends RuntimeException {

    public RecommendationNotFoundException(long recommendationId) {
        super("No recommendation with id " + recommendationId);
    }
}
