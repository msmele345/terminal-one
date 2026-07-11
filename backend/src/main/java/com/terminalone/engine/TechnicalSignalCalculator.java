package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure technical-signal calculator from strategy-matrix §1.1. It consumes daily
 * bars and emits the direction/conviction pair the engine maps into strategies.
 */
@Component
public class TechnicalSignalCalculator {

    public DirectionSignal calculate(PriceHistory history, EngineConfig.Signal config) {
        List<Double> closes = closes(history);
        int required = Math.max(config.ema().slow(), config.macd().slow() + config.macd().signal());
        if (closes.size() < Math.max(required, config.rsi().period() + 1)) {
            return neutral();
        }

        double price = closes.get(closes.size() - 1);
        double[] trendFast = emaSeries(closes, config.ema().fast());
        double[] trendSlow = emaSeries(closes, config.ema().slow());
        double emaFast = trendFast[trendFast.length - 1];
        double emaSlow = trendSlow[trendSlow.length - 1];
        double trendVote = clamp((emaFast - emaSlow) / (0.02 * price), -1.0, 1.0);

        double[] macdFast = emaSeries(closes, config.macd().fast());
        double[] macdSlow = emaSeries(closes, config.macd().slow());
        double[] macdLine = new double[closes.size()];
        for (int i = 0; i < closes.size(); i++) {
            macdLine[i] = macdFast[i] - macdSlow[i];
        }
        double[] macdSignal = emaSeries(macdLine, config.macd().signal());
        double macdHistogram = macdLine[macdLine.length - 1] - macdSignal[macdSignal.length - 1];
        double macdVote = clamp(macdHistogram / (0.01 * price), -1.0, 1.0);

        double rsi = rsi(closes, config.rsi().period());
        double rsiVote = clamp((rsi - 50.0) / 20.0, -1.0, 1.0);
        if (rsi > config.rsi().overboughtZone() || rsi < config.rsi().oversoldZone()) {
            rsiVote *= config.rsi().cautionFactor();
        }

        double score = config.weights().trend() * trendVote
                + config.weights().macd() * macdVote
                + config.weights().rsi() * rsiVote;
        Direction direction = direction(score, config.directionThreshold());
        int conviction = (int) Math.round(clamp(Math.abs(score) / config.convictionScale() * 100.0, 0.0, 100.0));
        return new DirectionSignal(direction, conviction, score, trendVote, macdVote, rsiVote,
                emaFast, emaSlow, macdHistogram, rsi);
    }

    private static List<Double> closes(PriceHistory history) {
        if (history == null || history.bars() == null) {
            return List.of();
        }
        return history.bars().stream()
                .map(PriceBar::close)
                .filter(v -> Double.isFinite(v) && v > 0.0)
                .toList();
    }

    private static DirectionSignal neutral() {
        return new DirectionSignal(Direction.NEUTRAL, 0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 50.0);
    }

    private static Direction direction(double score, double threshold) {
        if (score > threshold) {
            return Direction.BULLISH;
        }
        if (score < -threshold) {
            return Direction.BEARISH;
        }
        return Direction.NEUTRAL;
    }

    private static double[] emaSeries(List<Double> values, int period) {
        double[] raw = values.stream().mapToDouble(Double::doubleValue).toArray();
        return emaSeries(raw, period);
    }

    private static double[] emaSeries(double[] values, int period) {
        double[] out = new double[values.length];
        if (values.length == 0) {
            return out;
        }
        double alpha = 2.0 / (period + 1.0);
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1.0 - alpha) * out[i - 1];
        }
        return out;
    }

    private static double rsi(List<Double> closes, int period) {
        int start = closes.size() - period;
        double gains = 0.0;
        double losses = 0.0;
        for (int i = start; i < closes.size(); i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff >= 0.0) {
                gains += diff;
            } else {
                losses -= diff;
            }
        }
        if (gains == 0.0 && losses == 0.0) {
            return 50.0;
        }
        if (losses == 0.0) {
            return 100.0;
        }
        double rs = gains / losses;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
