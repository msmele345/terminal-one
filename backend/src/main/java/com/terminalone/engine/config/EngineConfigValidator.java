package com.terminalone.engine.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounds-checks a config before it may be activated (FR-29, runbook §6).
 * Returns every violation (not just the first) so a hand-edited config can be
 * fixed in one pass. Pure function — no I/O, no state.
 */
@Component
public class EngineConfigValidator {

    public List<String> validate(EngineConfig config) {
        List<String> violations = new ArrayList<>();
        deltas(config.strikes(), violations);
        double riskPct = config.sizing().perTradeRiskPct();
        if (riskPct <= 0 || riskPct > 0.25) {
            violations.add("sizing.perTradeRiskPct must be within (0, 0.25], was " + riskPct);
        }
        ivRankCutoffs(config.regime(), violations);
        dteRanges(config.expiry(), violations);
        double threshold = config.signal().directionThreshold();
        if (threshold <= 0 || threshold >= 1) {
            violations.add("signal.directionThreshold must be within (0, 1), was " + threshold);
        }
        if (config.ranking().topN() < 1) {
            violations.add("ranking.topN must be at least 1, was " + config.ranking().topN());
        }
        return violations;
    }

    private void dteRanges(EngineConfig.Expiry expiry, List<String> violations) {
        dtePair("expiry.creditDte", expiry.creditDteTarget(), expiry.creditDteWindow(), violations);
        dtePair("expiry.incomeDte", expiry.incomeDteTarget(), expiry.incomeDteWindow(), violations);
        dtePair("expiry.debitDte", expiry.debitDteTarget(), expiry.debitDteWindow(), violations);
    }

    private void dtePair(String field, EngineConfig.IntRange target, EngineConfig.IntRange window,
                         List<String> violations) {
        boolean targetOk = dteRange(field + "Target", target, violations);
        boolean windowOk = dteRange(field + "Window", window, violations);
        if (targetOk && windowOk && !window.contains(target)) {
            violations.add(field + "Target " + target + " must lie inside " + field + "Window " + window);
        }
    }

    private boolean dteRange(String field, EngineConfig.IntRange range, List<String> violations) {
        if (range.min() < 1 || range.min() > range.max()) {
            violations.add(field + " must satisfy 1 <= min <= max, was " + range);
            return false;
        }
        return true;
    }

    private void ivRankCutoffs(EngineConfig.Regime regime, List<String> violations) {
        boolean lowInRange = regime.ivRankLow() >= 0 && regime.ivRankLow() <= 100;
        boolean highInRange = regime.ivRankHigh() >= 0 && regime.ivRankHigh() <= 100;
        if (!lowInRange) {
            violations.add("regime.ivRankLow must be within [0, 100], was " + regime.ivRankLow());
        }
        if (!highInRange) {
            violations.add("regime.ivRankHigh must be within [0, 100], was " + regime.ivRankHigh());
        }
        if (lowInRange && highInRange && regime.ivRankLow() >= regime.ivRankHigh()) {
            violations.add("regime.ivRankLow (" + regime.ivRankLow()
                    + ") must be below regime.ivRankHigh (" + regime.ivRankHigh() + ")");
        }
    }

    private void deltas(EngineConfig.Strikes strikes, List<String> violations) {
        deltaBands("strikes.creditShortDelta", strikes.creditShortDelta(), violations);
        deltaBands("strikes.debitLongDelta", strikes.debitLongDelta(), violations);
        deltaBands("strikes.debitShortDelta", strikes.debitShortDelta(), violations);
        delta("strikes.longSingleLegDelta", strikes.longSingleLegDelta(), violations);
        delta("strikes.coveredCallDelta", strikes.coveredCallDelta(), violations);
        delta("strikes.cspDelta", strikes.cspDelta(), violations);
    }

    private void deltaBands(String field, EngineConfig.DeltaBands bands, List<String> violations) {
        delta(field + ".standard", bands.standard(), violations);
        delta(field + ".confident", bands.confident(), violations);
        delta(field + ".high", bands.high(), violations);
    }

    private void delta(String field, double value, List<String> violations) {
        if (value < 0 || value > 1) {
            violations.add(field + " must be within [0, 1], was " + value);
        }
    }
}
