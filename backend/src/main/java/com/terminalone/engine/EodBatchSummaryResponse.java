package com.terminalone.engine;

import java.time.Instant;

/**
 * Summary of the latest scheduled EOD engine batch, consumed by the Electron
 * main process to fire the single post-batch desktop notification
 * (Phase 9 AC1, FR-24/D20: "N new recommendations, top conviction: X").
 * {@code topSymbol}/{@code topConviction} are null when the batch produced no
 * trades.
 */
public record EodBatchSummaryResponse(
        Long batchRunId,
        EngineBatchStatus status,
        Instant startedAt,
        Instant completedAt,
        int recommendationCount,
        String topSymbol,
        Integer topConviction) {

    static EodBatchSummaryResponse from(EngineBatchRun run, Recommendation topRecommendation) {
        return new EodBatchSummaryResponse(
                run.getId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getRecommendationCount(),
                topRecommendation == null ? null : topRecommendation.getSymbol(),
                topRecommendation == null ? null : topRecommendation.getConviction());
    }
}
