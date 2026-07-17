package com.terminalone.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single orchestration path for every engine run (Phase 6 AC6): the scheduled
 * EOD batch and the on-demand lever-pull both execute here, so each records an
 * observable {@code engine_batch_runs} row, persists {@code signal_snapshots},
 * and links its recommendations — the only difference is the {@link
 * EngineBatchKind} stamp and, for the lever, an optional symbol filter.
 */
@Component
public class EngineBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(EngineBatchRunner.class);

    private final RecommendationEngine engine;
    private final EngineBatchRunRepository batchRuns;
    private final SignalSnapshotRepository signalSnapshots;
    private final RecommendationRepository recommendations;
    private final Clock clock;

    public EngineBatchRunner(RecommendationEngine engine,
            EngineBatchRunRepository batchRuns,
            SignalSnapshotRepository signalSnapshots,
            RecommendationRepository recommendations,
            Clock clock) {
        this.engine = engine;
        this.batchRuns = batchRuns;
        this.signalSnapshots = signalSnapshots;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    /** Scheduled post-close EOD engine batch (16:30 ET, weekdays). */
    @Scheduled(cron = "${app.engine.eod-cron:0 30 16 * * MON-FRI}", zone = "America/New_York")
    void scheduledRun() {
        runEodBatch();
    }

    public EngineBatchRunResponse runEodBatch() {
        return EngineBatchRunResponse.from(
                execute(EngineBatchKind.SCHEDULED_EOD, new EngineRunRequest(null)).run());
    }

    /** On-demand lever-pull: same path as the EOD batch, returns the full engine response. */
    public EngineRunResponse runOnDemand(EngineRunRequest request) {
        return execute(EngineBatchKind.ON_DEMAND,
                request == null ? new EngineRunRequest(null) : request).response();
    }

    private BatchExecution execute(EngineBatchKind kind, EngineRunRequest request) {
        EngineBatchRun run = batchRuns.saveAndFlush(EngineBatchRun.start(kind, clock.instant()));
        AtomicInteger snapshotCount = new AtomicInteger();
        try {
            EngineRunResponse response = engine.run(request, run.getId(), snapshot -> {
                signalSnapshots.save(snapshot);
                snapshotCount.incrementAndGet();
            });
            run.complete(response, snapshotCount.get(), clock.instant());
            EngineBatchRun saved = batchRuns.save(run);
            log.info("{} engine batch {} completed: {} recommendation(s), {} abstention(s), {} signal snapshot(s)",
                    saved.getKind(), saved.getId(), saved.getRecommendationCount(), saved.getAbstentionCount(),
                    saved.getSignalSnapshotCount());
            return new BatchExecution(saved, response);
        } catch (RuntimeException e) {
            run.fail(errorMessage(e), clock.instant());
            EngineBatchRun saved = batchRuns.save(run);
            log.warn("{} engine batch {} failed: {}", saved.getKind(), saved.getId(), saved.getErrorMessage(), e);
            throw e;
        }
    }

    public Optional<EngineBatchRunResponse> latestRun() {
        return batchRuns.findTopByOrderByStartedAtDesc()
                .map(EngineBatchRunResponse::from);
    }

    /**
     * Latest scheduled EOD batch with its top-conviction recommendation, for the
     * Phase 9 AC1 desktop notification. On-demand lever pulls are excluded —
     * they happen in-app and must never become alert noise.
     */
    public Optional<EodBatchSummaryResponse> latestEodSummary() {
        return batchRuns.findTopByKindOrderByStartedAtDesc(EngineBatchKind.SCHEDULED_EOD)
                .map(run -> EodBatchSummaryResponse.from(run,
                        recommendations.findFirstByBatchRunIdOrderByConvictionDescScoreDesc(run.getId())
                                .orElse(null)));
    }

    private record BatchExecution(EngineBatchRun run, EngineRunResponse response) {
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 2048 ? message.substring(0, 2048) : message;
    }
}
