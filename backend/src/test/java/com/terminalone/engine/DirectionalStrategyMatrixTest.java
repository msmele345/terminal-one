package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;

/**
 * Phase 5 AC1: the strategy-matrix §2 master decision matrix, directional rows.
 * (direction × regime × conviction) → strategy, including the conviction-floor
 * override and the long single-leg unlock (conviction ≥ 80 AND LOW IV only).
 */
class DirectionalStrategyMatrixTest {

    private final EngineConfig.Conviction conviction = EngineConfigDefaults.load().conviction();

    @ParameterizedTest(name = "{0} + {1} IV @ conviction {2} -> {3}")
    @CsvSource({
            // §2 BULLISH row
            "BULLISH, LOW,    45,  BULL_CALL_DEBIT_SPREAD",
            "BULLISH, LOW,    79,  BULL_CALL_DEBIT_SPREAD", // one below the long unlock
            "BULLISH, LOW,    80,  LONG_CALL",              // high band + LOW IV unlocks the long
            "BULLISH, LOW,    100, LONG_CALL",
            "BULLISH, NORMAL, 40,  BULL_CALL_DEBIT_SPREAD", // trade floor is inclusive
            "BULLISH, NORMAL, 65,  BULL_CALL_DEBIT_SPREAD",
            "BULLISH, NORMAL, 95,  BULL_CALL_DEBIT_SPREAD", // conviction alone never unlocks a long
            "BULLISH, HIGH,   45,  BULL_PUT_CREDIT_SPREAD",
            "BULLISH, HIGH,   95,  BULL_PUT_CREDIT_SPREAD", // ... nor does it at HIGH IV
            // §2 BEARISH row
            "BEARISH, LOW,    45,  BEAR_PUT_DEBIT_SPREAD",
            "BEARISH, LOW,    79,  BEAR_PUT_DEBIT_SPREAD",
            "BEARISH, LOW,    80,  LONG_PUT",
            "BEARISH, NORMAL, 65,  BEAR_PUT_DEBIT_SPREAD",
            "BEARISH, HIGH,   70,  BEAR_CALL_CREDIT_SPREAD",
            "BEARISH, HIGH,   100, BEAR_CALL_CREDIT_SPREAD",
    })
    void directionalCellsMapToTheMatrixStrategy(Direction direction, VolatilityRegime regime,
            int conv, StrategyType expected) {
        assertThat(DirectionalStrategyMatrix.select(direction, regime, conv, conviction))
                .contains(expected);
    }

    @ParameterizedTest(name = "{0} + {1} IV @ conviction {2} -> abstain")
    @CsvSource({
            // conviction floor (§2 override): no directional trade below 40
            "BULLISH, LOW,    39",
            "BULLISH, NORMAL, 0",
            "BULLISH, HIGH,   39",
            "BEARISH, NORMAL, 39",
            "BEARISH, HIGH,   25",
            // NEUTRAL row: abstain — the HIGH-IV income overlays are Phase 6
            "NEUTRAL, LOW,    100",
            "NEUTRAL, NORMAL, 50",
            "NEUTRAL, HIGH,   50",
    })
    void weakOrNeutralSignalsYieldNoDirectionalStrategy(Direction direction, VolatilityRegime regime,
            int conv) {
        assertThat(DirectionalStrategyMatrix.select(direction, regime, conv, conviction)).isEmpty();
    }
}
