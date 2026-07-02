package com.terminalone.engine.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strategy-matrix §9 defaults are the immutable factory-reset baseline
 * (D21/FR-27): they ship as a classpath resource and seed engine_config v1.
 * This pins the resource to the spec so a stray edit can't silently retune
 * the engine's factory baseline.
 */
class EngineConfigDefaultsTest {

    @Test
    void loadsTheStrategyMatrixSection9Defaults() {
        EngineConfig config = EngineConfigDefaults.load();

        // one representative value per §9 block
        assertThat(config.signal().weights().trend()).isEqualTo(0.40);
        assertThat(config.signal().directionThreshold()).isEqualTo(0.20);
        assertThat(config.regime().metric()).isEqualTo(RegimeMetric.IV_RANK);
        assertThat(config.regime().ivRankLow()).isEqualTo(30);
        assertThat(config.regime().ivRankHigh()).isEqualTo(60);
        assertThat(config.conviction().tradeFloor()).isEqualTo(40);
        assertThat(config.strikes().creditShortDelta().standard()).isEqualTo(0.20);
        assertThat(config.strikes().creditSpreadWidth()).isEqualTo(5.00);
        assertThat(config.expiry().debitDteWindow().min()).isEqualTo(35);
        assertThat(config.expiry().debitDteWindow().max()).isEqualTo(70);
        assertThat(config.filters().minPopCredit()).isEqualTo(0.65);
        assertThat(config.sizing().perTradeRiskPct()).isEqualTo(0.03);
        assertThat(config.ranking().topN()).isEqualTo(3);
        assertThat(config.ranking().regimeFitMatch()).isEqualTo(1.15);
    }
}
