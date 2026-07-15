package com.terminalone.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * "Mark as taken" API (Phase 8 AC3, FR-18): promotes a recommendation to the
 * real-positions tracker with a manually entered fill price, and lists taken
 * positions. Authentication is handled globally.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final TakenPositionService takenPositions;

    public RecommendationController(TakenPositionService takenPositions) {
        this.takenPositions = takenPositions;
    }

    @PostMapping("/{id}/take")
    public TakenPositionResponse take(@PathVariable long id, @RequestBody(required = false) MarkTakenRequest request) {
        if (request == null || request.fillPrice() == null || !Double.isFinite(request.fillPrice())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fillPrice is required");
        }
        return TakenPositionResponse.from(takenPositions.markAsTaken(id, request.fillPrice()));
    }

    @GetMapping("/taken")
    public List<TakenPositionResponse> taken() {
        return takenPositions.taken().stream().map(TakenPositionResponse::from).toList();
    }
}
