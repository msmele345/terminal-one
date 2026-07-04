package com.terminalone.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terminalone.engine.config.ActiveEngineConfig;
import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigProvider;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.PriceHistory;
import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.PositionPnl;
import com.terminalone.portfolio.PositionSource;
import com.terminalone.portfolio.StockPosition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

@Service
public class RecommendationEngine {

    private final EngineConfigProvider configProvider;
    private final PositionSource positions;
    private final MarketDataProvider marketData;
    private final TechnicalSignalCalculator signals;
    private final VolatilityRegimeCalculator regimes;
    private final DirectionalStrategySelector strategySelector;
    private final RecommendationRepository recommendations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private static final Logger logger = LoggerFactory.getLogger(RecommendationEngine.class);

    public RecommendationEngine(EngineConfigProvider configProvider,
            PositionSource positions,
            MarketDataProvider marketData,
            TechnicalSignalCalculator signals,
            VolatilityRegimeCalculator regimes,
            DirectionalStrategySelector strategySelector,
            RecommendationRepository recommendations,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configProvider = configProvider;
        this.positions = positions;
        this.marketData = marketData;
        this.signals = signals;
        this.regimes = regimes;
        this.strategySelector = strategySelector;
        this.recommendations = recommendations;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public EngineRunResponse run(EngineRunRequest request) {
        ActiveEngineConfig active = configProvider.getActive();
        EngineConfig config = active.config();
        double portfolioValue = portfolioValueAtCost();

        // Phase 5 AC6 (§8): collect every surviving sized candidate across the
        // portfolio, then rank them globally — top-N is decided across all
        // underlyings, not the first N symbols encountered.
        List<RecommendationCandidate> survivors = new ArrayList<>();
        for (String symbol : symbolsFor(request)) {
            sizeCandidate(symbol, active, portfolioValue).ifPresent(survivors::add);
        }
        List<RecommendationCandidate> ranked = CandidateRanker.rank(survivors, config.ranking());

        List<RecommendationResponse> emitted = new ArrayList<>();
        for (RecommendationCandidate candidate : ranked) {
            Recommendation saved = recommendations.save(toEntity(candidate, active.version()));
            emitted.add(RecommendationResponse.from(saved, candidate));
        }
        return new EngineRunResponse(emitted);
    }

    private java.util.Optional<RecommendationCandidate> sizeCandidate(String symbol, ActiveEngineConfig active,
                                                                       double portfolioValue) {
        EngineConfig config = active.config();
        PriceHistory history = safe(() -> marketData.getDailyBars(symbol));
        DirectionSignal signal = signals.calculate(history, config.signal());
        OptionChain chain = safe(() -> marketData.getChain(symbol));

        VolatilityRegimeResult regime = regimes.calculate(symbol, chain, history, config.regime());
        java.util.Optional<StrategyType> strategy = DirectionalStrategyMatrix.select(
                signal.direction(), regime.regime(), signal.conviction(), config.conviction());
        if (strategy.isEmpty()) {
            return java.util.Optional.empty();
        }

        java.util.Optional<RecommendationCandidate> candidate = strategySelector.select(
                symbol, strategy.get(), signal, regime, chain, config);
        if (candidate.isEmpty()) {
            return java.util.Optional.empty();
        }

        RecommendationCandidate unsized = candidate.get();
        // Phase 5 AC5 (§7): cap defined risk to perTradeRiskPct of the portfolio.
        PositionSizer.Sizing sizing = PositionSizer.size(unsized.maxLoss(), portfolioValue,
                config.sizing().perTradeRiskPct());
        if (sizing.abstain()) {
            logger.info("Rejected {} {} candidate: {} (max loss ${} exceeds ${} cap)",
                    unsized.symbol(), unsized.strategy(), PositionSizer.RISK_TOO_LARGE,
                    unsized.maxLoss(), portfolioValue * config.sizing().perTradeRiskPct());
            return java.util.Optional.empty();
        }

        RecommendationRationale.Sizing rationaleSizing = new RecommendationRationale.Sizing(
                sizing.contracts(), unsized.maxLoss(), portfolioValue,
                config.sizing().perTradeRiskPct(), sizing.riskAmount());
        return java.util.Optional.of(unsized.withSizing(
                sizing.contracts(), unsized.rationale().withSizing(rationaleSizing)));
    }

    /**
     * The portfolio's total absolute-cost basis — the deterministic size base for §7.
     * Stocks contribute {@code |qty × costBasis|}; options contribute
     * {@code |qty × costBasis × 100|} (per {@link com.terminalone.portfolio.PositionPnl}).
     */
    private double portfolioValueAtCost() {
        double value = 0.0;
        for (StockPosition stock : positions.listStocks()) {
            value += Math.abs(stock.getQuantity().doubleValue() * stock.getCostBasis().doubleValue());
        }
        for (OptionPosition option : positions.listOptions()) {
            value += Math.abs(option.getQuantity().doubleValue()
                    * option.getCostBasis().doubleValue() * PositionPnl.OPTION_MULTIPLIER);
        }
        return value;
    }

    private Recommendation toEntity(RecommendationCandidate candidate, int configVersion) {
        // "Long" columns hold the bought leg, "short" the sold one; long
        // single-leg structures (LONG_CALL / LONG_PUT) have no sold leg.
        RecommendationLeg bought = candidate.legs().stream()
                .filter(l -> "BUY".equals(l.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("candidate has no bought leg"));
        RecommendationLeg sold = candidate.legs().stream()
                .filter(l -> "SELL".equals(l.action()))
                .findFirst()
                .orElse(null);
        return new Recommendation(
                candidate.symbol(),
                candidate.strategy(),
                candidate.signal().direction(),
                candidate.regime().regime(),
                candidate.signal().conviction(),
                RecommendationStatus.PAPER,
                configVersion,
                candidate.expiry(),
                bought.optionSymbol(),
                bought.strike(),
                sold == null ? null : sold.optionSymbol(),
                sold == null ? null : sold.strike(),
                candidate.entryDebit(),
                candidate.probabilityOfProfit(),
                candidate.maxProfit(),
                candidate.maxLoss(),
                candidate.riskReward(),
                candidate.score(),
                candidate.contracts(),
                writeRationale(candidate.rationale()),
                Instant.now(clock));
    }

    private List<String> symbolsFor(EngineRunRequest request) {
        if (request != null && request.symbol() != null && !request.symbol().isBlank()) {
            return List.of(normalize(request.symbol()));
        }
        TreeSet<String> symbols = new TreeSet<>();
        for (StockPosition stock : positions.listStocks()) {
            symbols.add(normalize(stock.getSymbol()));
        }
        for (OptionPosition option : positions.listOptions()) {
            symbols.add(normalize(option.getUnderlying()));
        }
        return new ArrayList<>(symbols);
    }

    private String writeRationale(RecommendationRationale rationale) {
        try {
            return objectMapper.writeValueAsString(rationale);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Recommendation rationale could not be serialized", e);
        }
    }

    private static String normalize(String symbol) {
        return symbol.strip().toUpperCase(Locale.ROOT);
    }

    private static <T> T safe(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            logger.warn("Exception Occurred During safe() call. msg: " + e.getLocalizedMessage());
            return null;
        }
    }
}
