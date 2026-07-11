package com.terminalone.engine.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounds validation (FR-29, Phase 4 AC2): invalid configs are rejected before
 * activation, never persisted. Pure function over the typed config.
 */
class EngineConfigValidatorTest {

    private final EngineConfigValidator validator = new EngineConfigValidator();

    @Test
    void theFactoryDefaultsAreValid() {
        assertThat(validator.validate(EngineConfigDefaults.load())).isEmpty();
    }

    @Test
    void rejectsADeltaOutsideZeroToOne() {
        EngineConfig config = withStrikes(s -> new EngineConfig.Strikes(
                new EngineConfig.DeltaBands(0.20, 1.30, 0.38), // confident > 1
                s.debitLongDelta(), s.debitShortDelta(),
                s.longSingleLegDelta(), s.coveredCallDelta(), s.cspDelta(), s.creditSpreadWidth()));

        List<String> violations = validator.validate(config);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).contains("creditShortDelta.confident");
    }

    @Test
    void rejectsAPerTradeRiskPctOutsideItsBounds() {
        EngineConfig d = EngineConfigDefaults.load();

        EngineConfig zeroRisk = new EngineConfig(d.signal(), d.regime(), d.conviction(), d.strikes(),
                d.expiry(), d.filters(), new EngineConfig.Sizing(0.0), d.ranking());
        EngineConfig oversizedRisk = new EngineConfig(d.signal(), d.regime(), d.conviction(), d.strikes(),
                d.expiry(), d.filters(), new EngineConfig.Sizing(0.30), d.ranking());

        assertThat(validator.validate(zeroRisk))
                .singleElement().asString().contains("sizing.perTradeRiskPct");
        assertThat(validator.validate(oversizedRisk))
                .singleElement().asString().contains("sizing.perTradeRiskPct");
    }

    @Test
    void rejectsUnorderedOrOutOfRangeIvRankCutoffs() {
        EngineConfig d = EngineConfigDefaults.load();

        EngineConfig unordered = withRegime(d, new EngineConfig.Regime(RegimeMetric.IV_RANK, 60, 30, 60));
        EngineConfig outOfRange = withRegime(d, new EngineConfig.Regime(RegimeMetric.IV_RANK, 30, 120, 60));

        assertThat(validator.validate(unordered))
                .singleElement().asString().contains("ivRankLow").contains("ivRankHigh");
        assertThat(validator.validate(outOfRange))
                .singleElement().asString().contains("ivRankHigh");
    }

    @Test
    void rejectsMalformedDteRanges() {
        EngineConfig d = EngineConfigDefaults.load();

        // credit window inverted; debit target outside its window; income target non-positive
        EngineConfig.Expiry expiry = new EngineConfig.Expiry(
                d.expiry().creditDteTarget(), new EngineConfig.IntRange(50, 25),
                new EngineConfig.IntRange(0, 45), d.expiry().incomeDteWindow(),
                new EngineConfig.IntRange(45, 80), d.expiry().debitDteWindow());
        EngineConfig config = new EngineConfig(d.signal(), d.regime(), d.conviction(), d.strikes(),
                expiry, d.filters(), d.sizing(), d.ranking());

        List<String> violations = validator.validate(config);

        assertThat(violations).anySatisfy(v -> assertThat(v).contains("creditDteWindow"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("incomeDteTarget"));
        assertThat(violations).anySatisfy(v ->
                assertThat(v).contains("debitDteTarget").contains("debitDteWindow"));
    }

    @Test
    void rejectsADirectionThresholdOutsideZeroToOneExclusive() {
        EngineConfig d = EngineConfigDefaults.load();
        EngineConfig.Signal s = d.signal();
        EngineConfig config = new EngineConfig(
                new EngineConfig.Signal(s.ema(), s.macd(), s.rsi(), s.weights(), 1.0, s.convictionScale()),
                d.regime(), d.conviction(), d.strikes(), d.expiry(), d.filters(), d.sizing(), d.ranking());

        assertThat(validator.validate(config))
                .singleElement().asString().contains("signal.directionThreshold");
    }

    @Test
    void rejectsANonPositiveTopN() {
        EngineConfig d = EngineConfigDefaults.load();
        EngineConfig.Ranking r = d.ranking();
        EngineConfig config = new EngineConfig(d.signal(), d.regime(), d.conviction(), d.strikes(),
                d.expiry(), d.filters(), d.sizing(),
                new EngineConfig.Ranking(0, r.regimeFitMatch(), r.regimeFitNeutral(),
                        r.regimeFitCounter(), r.requirePositiveEV()));

        assertThat(validator.validate(config))
                .singleElement().asString().contains("ranking.topN");
    }

    private static EngineConfig withRegime(EngineConfig d, EngineConfig.Regime regime) {
        return new EngineConfig(d.signal(), regime, d.conviction(), d.strikes(),
                d.expiry(), d.filters(), d.sizing(), d.ranking());
    }

    private static EngineConfig withStrikes(
            java.util.function.UnaryOperator<EngineConfig.Strikes> change) {
        EngineConfig d = EngineConfigDefaults.load();
        return new EngineConfig(d.signal(), d.regime(), d.conviction(),
                change.apply(d.strikes()), d.expiry(), d.filters(), d.sizing(), d.ranking());
    }
}
