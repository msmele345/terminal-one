package com.terminalone.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the immutable factory-reset baseline — strategy-matrix §9 — from the
 * classpath (FR-27). This is what seeds engine_config v1 at first boot; it is
 * never edited to retune the engine (that's a new DB version, per the runbook).
 */
public final class EngineConfigDefaults {

    private static final String RESOURCE = "/engine/engine-config-defaults.json";

    private EngineConfigDefaults() {
    }

    public static EngineConfig load() {
        try (InputStream in = EngineConfigDefaults.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing factory defaults resource: " + RESOURCE);
            }
            return new ObjectMapper().readValue(in, EngineConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable factory defaults resource: " + RESOURCE, e);
        }
    }
}
