package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.RegimeMetric;
import com.terminalone.marketdata.AtmIv;
import com.terminalone.marketdata.AtmIvCalculator;
import com.terminalone.marketdata.IvHistory;
import com.terminalone.marketdata.IvHistoryRepository;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Phase 5 AC2 volatility-regime read. IV rank is primary once enough persisted
 * ATM-IV readings exist; until then the documented bootstrap metric is the
 * 20-day Bollinger-width percentile over trailing daily bars.
 */
@Component
public class VolatilityRegimeCalculator {

    private static final int BOLLINGER_PERIOD = 20;
    private static final int TRADING_YEAR_DAYS = 252;
    private static final double RISK_FREE_RATE = 0.04;
    private static final double EPS = 1e-9;

    private final IvHistoryRepository ivHistory;
    private final OptionAnalytics analytics;
    private final Clock clock;

    public VolatilityRegimeCalculator(IvHistoryRepository ivHistory, OptionAnalytics analytics, Clock clock) {
        this.ivHistory = ivHistory;
        this.analytics = analytics;
        this.clock = clock;
    }

    public VolatilityRegimeResult calculate(String symbol, OptionChain chain, PriceHistory priceHistory,
            EngineConfig.Regime config) {
        String normalized = normalize(symbol);
        List<IvHistory> history = ivHistory.findBySymbolOrderByAsOfDateAsc(normalized);
        Optional<AtmIv> currentAtmIv = AtmIvCalculator.compute(
                chain, LocalDate.now(clock), analytics, RISK_FREE_RATE);
        OptionalDouble currentIv = currentAtmIv.isPresent()
                ? OptionalDouble.of(currentAtmIv.get().impliedVol())
                : latestStoredIv(history);

        if (config.metric() == RegimeMetric.IV_RANK && history.size() >= config.minIvHistoryDays()) {
            Optional<VolatilityRegimeResult> ivRank = fromIvRank(history, currentIv, config);
            if (ivRank.isPresent()) {
                return ivRank.get();
            }
        }

        OptionalDouble widthPercentile = bollingerWidthPercentile(priceHistory);
        if (widthPercentile.isPresent()) {
            double metric = widthPercentile.getAsDouble();
            VolatilityRegime regime = classify(metric, config);
            return new VolatilityRegimeResult(regime,
                    String.format(Locale.US,
                            "BOLLINGER_WIDTH_PCTL %.1f fallback: IV history %d/%d; using 20-day width over %d daily bars",
                            metric, history.size(), config.minIvHistoryDays(), barCount(priceHistory)),
                    currentIv.isPresent() ? currentIv.getAsDouble() : null);
        }

        return new VolatilityRegimeResult(VolatilityRegime.NORMAL,
                String.format(Locale.US,
                        "VOL_REGIME_UNAVAILABLE: IV history %d/%d and Bollinger fallback needs at least %d usable bars; defaulting NORMAL",
                        history.size(), config.minIvHistoryDays(), BOLLINGER_PERIOD + 1),
                currentIv.isPresent() ? currentIv.getAsDouble() : null);
    }

    private Optional<VolatilityRegimeResult> fromIvRank(List<IvHistory> history, OptionalDouble currentIv,
            EngineConfig.Regime config) {
        if (currentIv.isEmpty()) {
            return Optional.empty();
        }
        List<IvHistory> trailing = trailingYear(history);
        double min = trailing.stream()
                .mapToDouble(IvHistory::getAtmIv)
                .min()
                .orElse(Double.NaN);
        double max = trailing.stream()
                .mapToDouble(IvHistory::getAtmIv)
                .max()
                .orElse(Double.NaN);
        if (!Double.isFinite(min) || !Double.isFinite(max) || Math.abs(max - min) < EPS) {
            return Optional.empty();
        }

        double iv = currentIv.getAsDouble();
        double rank = clamp100(((iv - min) / (max - min)) * 100.0);
        VolatilityRegime regime = classify(rank, config);
        return Optional.of(new VolatilityRegimeResult(regime,
                String.format(Locale.US,
                        "IV_RANK %.1f from %d ATM-IV readings (current IV %.1f%%, trailing range %.1f%%-%.1f%%)",
                        rank, trailing.size(), iv * 100.0, min * 100.0, max * 100.0),
                iv));
    }

    private OptionalDouble bollingerWidthPercentile(PriceHistory history) {
        if (history == null || history.bars() == null || history.bars().size() < BOLLINGER_PERIOD + 1) {
            return OptionalDouble.empty();
        }

        List<Double> closes = history.bars().stream()
                .sorted(Comparator.comparing(PriceBar::date))
                .skip(Math.max(0, history.bars().size() - (TRADING_YEAR_DAYS + BOLLINGER_PERIOD - 1)))
                .map(PriceBar::close)
                .filter(c -> Double.isFinite(c) && c > 0.0)
                .toList();
        if (closes.size() < BOLLINGER_PERIOD + 1) {
            return OptionalDouble.empty();
        }

        List<Double> widths = new ArrayList<>();
        for (int i = BOLLINGER_PERIOD - 1; i < closes.size(); i++) {
            OptionalDouble width = bollingerWidth(closes.subList(i - BOLLINGER_PERIOD + 1, i + 1));
            width.ifPresent(widths::add);
        }
        if (widths.size() < 2) {
            return OptionalDouble.empty();
        }

        double min = widths.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        double max = widths.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        if (!Double.isFinite(min) || !Double.isFinite(max) || Math.abs(max - min) < EPS) {
            return OptionalDouble.empty();
        }

        double current = widths.get(widths.size() - 1);
        long lessOrEqual = widths.stream().filter(w -> w <= current + EPS).count();
        double percentile = ((lessOrEqual - 1.0) / (widths.size() - 1.0)) * 100.0;
        return OptionalDouble.of(clamp100(percentile));
    }

    private OptionalDouble bollingerWidth(List<Double> closes) {
        double mean = closes.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        if (!Double.isFinite(mean) || mean <= 0.0) {
            return OptionalDouble.empty();
        }
        double variance = closes.stream()
                .mapToDouble(c -> {
                    double diff = c - mean;
                    return diff * diff;
                })
                .average()
                .orElse(Double.NaN);
        if (!Double.isFinite(variance)) {
            return OptionalDouble.empty();
        }
        double stdDev = Math.sqrt(variance);
        return OptionalDouble.of((4.0 * stdDev) / mean);
    }

    private static OptionalDouble latestStoredIv(List<IvHistory> history) {
        if (history.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(history.get(history.size() - 1).getAtmIv());
    }

    private static List<IvHistory> trailingYear(List<IvHistory> history) {
        return history.stream()
                .sorted(Comparator.comparing(IvHistory::getAsOfDate))
                .skip(Math.max(0, history.size() - TRADING_YEAR_DAYS))
                .toList();
    }

    private static VolatilityRegime classify(double metric, EngineConfig.Regime config) {
        if (metric < config.ivRankLow()) {
            return VolatilityRegime.LOW;
        }
        if (metric > config.ivRankHigh()) {
            return VolatilityRegime.HIGH;
        }
        return VolatilityRegime.NORMAL;
    }

    private static int barCount(PriceHistory history) {
        return history == null || history.bars() == null ? 0 : history.bars().size();
    }

    private static double clamp100(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
    }
}
