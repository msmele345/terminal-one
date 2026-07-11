package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;

import java.util.Optional;

/**
 * The strategy-matrix §2 master decision matrix, directional rows only:
 * (direction × regime × conviction) → structure. Pure and stateless (FR-16).
 *
 * <p>Applied before the grid (§2 overrides): a directional signal below the
 * conviction trade floor abstains. The long single-leg only unlocks at
 * conviction ≥ {@code longSingleLegMinConviction} <em>and</em> LOW IV (§3/§4.3).
 * The NEUTRAL row (abstain / HIGH-IV income overlays) is Phase 6.</p>
 */
final class DirectionalStrategyMatrix {

    private DirectionalStrategyMatrix() {
    }

    static Optional<StrategyType> select(Direction direction, VolatilityRegime regime, int conviction,
            EngineConfig.Conviction config) {
        if (direction == Direction.NEUTRAL || conviction < config.tradeFloor()) {
            return Optional.empty();
        }
        boolean longSingleLegUnlocked = regime == VolatilityRegime.LOW
                && conviction >= config.longSingleLegMinConviction();
        return Optional.of(switch (direction) {
            case BULLISH -> switch (regime) {
                case HIGH -> StrategyType.BULL_PUT_CREDIT_SPREAD;
                case LOW -> longSingleLegUnlocked ? StrategyType.LONG_CALL : StrategyType.BULL_CALL_DEBIT_SPREAD;
                case NORMAL -> StrategyType.BULL_CALL_DEBIT_SPREAD;
            };
            case BEARISH -> switch (regime) {
                case HIGH -> StrategyType.BEAR_CALL_CREDIT_SPREAD;
                case LOW -> longSingleLegUnlocked ? StrategyType.LONG_PUT : StrategyType.BEAR_PUT_DEBIT_SPREAD;
                case NORMAL -> StrategyType.BEAR_PUT_DEBIT_SPREAD;
            };
            case NEUTRAL -> throw new IllegalStateException("unreachable: guarded above");
        });
    }
}
