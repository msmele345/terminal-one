package com.terminalone.engine;

record GuardrailRejection(
        String symbol,
        StrategyType strategy,
        GuardrailReason reason,
        String detail) {
}
