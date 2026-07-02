package com.terminalone.engine.config;

import java.util.List;

/**
 * A config failed bounds validation (FR-29): it was rejected and never
 * activated. Carries every violation so the config can be fixed in one pass.
 */
public class InvalidEngineConfigException extends RuntimeException {

    private final List<String> violations;

    public InvalidEngineConfigException(List<String> violations) {
        super("Engine config rejected: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
