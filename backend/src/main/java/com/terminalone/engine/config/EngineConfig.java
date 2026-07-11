package com.terminalone.engine.config;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * The complete tunable engine configuration — strategy-matrix §9 as a typed
 * object (D21/FR-27). Nothing here is hardcoded into the engine: the active
 * version is read from the {@code engine_config} table at the start of every
 * run via {@link EngineConfigProvider}, and every recommendation is stamped
 * with the version that produced it. Serialized 1:1 as the row's JSONB body.
 */
public record EngineConfig(
        Signal signal,
        Regime regime,
        Conviction conviction,
        Strikes strikes,
        Expiry expiry,
        Filters filters,
        Sizing sizing,
        Ranking ranking) {

    public record Signal(
            Ema ema,
            Macd macd,
            Rsi rsi,
            Weights weights,
            double directionThreshold,
            double convictionScale) {
    }

    public record Ema(int fast, int slow) {
    }

    public record Macd(int fast, int slow, int signal) {
    }

    public record Rsi(int period, double overboughtZone, double oversoldZone, double cautionFactor) {
    }

    public record Weights(double trend, double macd, double rsi) {
    }

    public record Regime(RegimeMetric metric, double ivRankLow, double ivRankHigh, int minIvHistoryDays) {
    }

    public record Conviction(int tradeFloor, Bands bands, int longSingleLegMinConviction) {
    }

    public record Bands(IntRange standard, IntRange confident, IntRange high) {
    }

    public record Strikes(
            DeltaBands creditShortDelta,
            DeltaBands debitLongDelta,
            DeltaBands debitShortDelta,
            double longSingleLegDelta,
            double coveredCallDelta,
            double cspDelta,
            double creditSpreadWidth) {
    }

    /** A per-conviction-band delta target (§4). */
    public record DeltaBands(double standard, double confident, double high) {
    }

    public record Expiry(
            IntRange creditDteTarget, IntRange creditDteWindow,
            IntRange incomeDteTarget, IntRange incomeDteWindow,
            IntRange debitDteTarget, IntRange debitDteWindow) {
    }

    public record Filters(
            double maxBidAskPctOfMid,
            double maxBidAskAbsolute,
            int minOpenInterest,
            double minCreditToWidthRatio,
            double minPopCredit,
            double minRewardRiskDebit,
            boolean avoidEarnings) {
    }

    public record Sizing(double perTradeRiskPct) {
    }

    public record Ranking(
            int topN,
            double regimeFitMatch,
            double regimeFitNeutral,
            double regimeFitCounter,
            boolean requirePositiveEV) {
    }

    /** An inclusive [min, max] pair, serialized as a two-element JSON array (e.g. {@code [30, 45]}). */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record IntRange(int min, int max) {

        public boolean contains(IntRange other) {
            return min <= other.min && other.max <= max;
        }
    }
}
