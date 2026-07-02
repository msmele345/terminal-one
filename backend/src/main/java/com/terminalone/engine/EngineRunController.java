package com.terminalone.engine;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lever-pull API (Phase 4 AC3): runs the deterministic engine path and returns
 * any recommendations it persisted. Authentication is handled globally.
 */
@RestController
@RequestMapping("/api/engine")
public class EngineRunController {

    private final RecommendationEngine engine;

    public EngineRunController(RecommendationEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/run")
    public EngineRunResponse run(@RequestBody(required = false) EngineRunRequest request) {
        return engine.run(request == null ? new EngineRunRequest(null) : request);
    }
}
