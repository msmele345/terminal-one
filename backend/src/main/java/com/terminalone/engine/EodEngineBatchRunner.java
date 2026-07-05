package com.terminalone.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class EodEngineBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(EodEngineBatchRunner.class);

    private final RecommendationEngine engine;
    private final EngineBatchRunRepository batchRuns;
    private final SignalSnapshotRepository signalSnapshots;
    private final Clock clock;

    public EodEngineBatchRunner(RecommendationEngine engine,
            EngineBatchRunRepository batchRuns,
            SignalSnapshotRepository signalSnapshots,
            Clock clock) {
        this.engine = engine;
        this.batchRuns = batchRuns;
        this.signalSnapshots = signalSnapshots;
        this.clock = clock;
    }

    /** Scheduled post-close EOD engine batch (16:30 ET, weekdays). */
    @Scheduled(cron = "${app.engine.eod-cron:0 30 16 * * MON-FRI}", zone = "America/New_York")
    void scheduledRun() {
        runEodBatch();
    }

    public EngineBatchRunResponse runEodBatch() {
        EngineBatchRun run = batchRuns.saveAndFlush(EngineBatchRun.startEod(clock.instant()));
        AtomicInteger snapshotCount = new AtomicInteger();
        try {
            EngineRunResponse response = engine.runEodBatch(run.getId(), snapshot -> {
                signalSnapshots.save(snapshot);
                snapshotCount.incrementAndGet();
            });
            run.complete(response, snapshotCount.get(), clock.instant());
            EngineBatchRun saved = batchRuns.save(run);
            log.info("EOD engine batch {} completed: {} recommendation(s), {} abstention(s), {} signal snapshot(s)",
                    saved.getId(), saved.getRecommendationCount(), saved.getAbstentionCount(),
                    saved.getSignalSnapshotCount());
            return EngineBatchRunResponse.from(saved);
        } catch (RuntimeException e) {
            run.fail(errorMessage(e), clock.instant());
            EngineBatchRun saved = batchRuns.save(run);
            log.warn("EOD engine batch {} failed: {}", saved.getId(), saved.getErrorMessage(), e);
            throw e;
        }
    }

    public Optional<EngineBatchRunResponse> latestRun() {
        return batchRuns.findTopByOrderByStartedAtDesc()
                .map(EngineBatchRunResponse::from);
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 2048 ? message.substring(0, 2048) : message;
    }
}
