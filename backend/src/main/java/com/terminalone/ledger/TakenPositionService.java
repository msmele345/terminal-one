package com.terminalone.ledger;

import com.terminalone.engine.Recommendation;
import com.terminalone.engine.RecommendationRepository;
import com.terminalone.engine.RecommendationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Promotes a recommendation to a real, taken position (Phase 8 AC3, FR-18): flips
 * the recommendation to {@code TAKEN} and records it in {@code taken_positions}
 * with the owner's manually entered fill price (OQ-8). The ledger reflects the
 * flipped status; the taken tracker is the separate real-position record.
 */
@Service
public class TakenPositionService {

    private final RecommendationRepository recommendations;
    private final TakenPositionRepository takenPositions;
    private final Clock clock;

    public TakenPositionService(RecommendationRepository recommendations,
            TakenPositionRepository takenPositions,
            Clock clock) {
        this.recommendations = recommendations;
        this.takenPositions = takenPositions;
        this.clock = clock;
    }

    @Transactional
    public TakenPosition markAsTaken(long recommendationId, double fillPrice) {
        Recommendation recommendation = recommendations.findById(recommendationId)
                .orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
        if (recommendation.getStatus() == RecommendationStatus.TAKEN) {
            throw new RecommendationAlreadyTakenException(recommendationId);
        }
        recommendation.markTaken();
        recommendations.save(recommendation);
        return takenPositions.save(
                TakenPosition.fromRecommendation(recommendation, fillPrice, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<TakenPosition> taken() {
        return takenPositions.findAllByOrderByTakenAtDesc();
    }
}
