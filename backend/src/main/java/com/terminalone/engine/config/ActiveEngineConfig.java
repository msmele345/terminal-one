package com.terminalone.engine.config;

/**
 * The active config plus the version number that must be stamped onto every
 * recommendation it produces (FR-14/FR-28), keeping the paper-trade ledger
 * interpretable across tuning changes.
 */
public record ActiveEngineConfig(int version, EngineConfig config) {
}
