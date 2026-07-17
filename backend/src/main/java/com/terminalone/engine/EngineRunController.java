package com.terminalone.engine;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lever-pull API (Phase 4 AC3): runs the deterministic engine path and returns
 * any recommendations it persisted. Routed through {@link EngineBatchRunner}
 * so the on-demand pull shares the scheduled batch's engine path and records
 * an observable ON_DEMAND run (Phase 6 AC6). Authentication is handled
 * globally.
 */
@RestController
@RequestMapping("/api/engine")
public class EngineRunController {

    private final EngineBatchRunner batchRunner;

    public EngineRunController(EngineBatchRunner batchRunner) {
        this.batchRunner = batchRunner;
    }

    @PostMapping("/run")
    public EngineRunResponse run(@RequestBody(required = false) EngineRunRequest request) {
        return batchRunner.runOnDemand(request);
    }

    /**
     * Phase 9 AC1 (FR-24): latest scheduled EOD batch summary, polled by the
     * desktop main process to fire the single post-batch notification.
     * 204 until the first scheduled batch has ever run.
     */
    @GetMapping("/eod/latest")
    public ResponseEntity<EodBatchSummaryResponse> latestEodBatch() {
        return batchRunner.latestEodSummary()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
