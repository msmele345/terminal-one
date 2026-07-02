package com.terminalone.engine.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The V1 {@link EngineConfigProvider}: versioned JSONB rows in engine_config
 * (D21, runbook §2). Writes are bounds-validated (FR-29) and flip the active
 * flag in one transaction, so the one-active-row invariant survives every
 * activation; rows themselves are immutable (FR-28).
 */
@Service
public class DbEngineConfigProvider implements EngineConfigProvider {

    private final EngineConfigRepository repository;
    private final EngineConfigValidator validator;
    private final ObjectMapper objectMapper;

    public DbEngineConfigProvider(EngineConfigRepository repository,
                                  EngineConfigValidator validator,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveEngineConfig getActive() {
        EngineConfigVersion row = repository.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "engine_config has no active row — the v1 seed is missing"));
        return new ActiveEngineConfig(row.getVersion(), read(row));
    }

    /**
     * Validates, persists as a new immutable version, and makes it the single
     * active one — rejecting (never persisting) an out-of-bounds config.
     */
    @Transactional
    public ActiveEngineConfig activate(EngineConfig config, String note) {
        List<String> violations = validator.validate(config);
        if (!violations.isEmpty()) {
            throw new InvalidEngineConfigException(violations);
        }
        repository.findByActiveTrue().ifPresent(EngineConfigVersion::deactivate);
        EngineConfigVersion saved = repository.save(
                new EngineConfigVersion(write(config), true, note, Instant.now()));
        return new ActiveEngineConfig(saved.getVersion(), config);
    }

    private EngineConfig read(EngineConfigVersion row) {
        try {
            return objectMapper.readValue(row.getConfig(), EngineConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "engine_config version " + row.getVersion() + " holds unparseable JSON", e);
        }
    }

    private String write(EngineConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Engine config could not be serialized", e);
        }
    }
}
