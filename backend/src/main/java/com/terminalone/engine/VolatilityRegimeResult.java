package com.terminalone.engine;

public record VolatilityRegimeResult(
        VolatilityRegime regime,
        String reason,
        Double currentIv) {

    static VolatilityRegimeResult phase4Normal(Double currentIv) {
        return new VolatilityRegimeResult(VolatilityRegime.NORMAL, "PHASE4_SINGLE_CELL_NORMAL", currentIv);
    }
}
