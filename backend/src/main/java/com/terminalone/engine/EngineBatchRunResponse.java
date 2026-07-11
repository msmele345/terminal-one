package com.terminalone.engine;

import java.time.Instant;

public record EngineBatchRunResponse(
        Long id,
        EngineBatchKind kind,
        EngineBatchStatus status,
        Instant startedAt,
        Instant completedAt,
        int recommendationCount,
        int abstentionCount,
        int signalSnapshotCount,
        String errorMessage) {

    static EngineBatchRunResponse from(EngineBatchRun run) {
        return new EngineBatchRunResponse(
                run.getId(),
                run.getKind(),
                run.getStatus(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getRecommendationCount(),
                run.getAbstentionCount(),
                run.getSignalSnapshotCount(),
                run.getErrorMessage());
    }
}
