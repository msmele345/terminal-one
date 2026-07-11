package com.terminalone.engine;

/** Optional lever-pull request body. If {@code symbol} is absent, run over portfolio underlyings. */
public record EngineRunRequest(String symbol) {
}
