package com.terminalone.engine.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only config API for V1: inspect which version the engine will use on
 * its next run. Writes stay manual via docs/config-update-runbook.md until the
 * V1.5 Settings screen.
 */
@RestController
@RequestMapping("/api/config")
public class EngineConfigController {

    private final EngineConfigProvider provider;

    public EngineConfigController(EngineConfigProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/active")
    public ActiveEngineConfig active() {
        return provider.getActive();
    }
}
