package com.terminalone.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Public liveness endpoint (Phase 1 acceptance: GET /api/health returns 200
 * publicly). Kept dependency-free so it answers even if downstream is degraded.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "terminal-one-backend",
                "time", Instant.now().toString());
    }
}
