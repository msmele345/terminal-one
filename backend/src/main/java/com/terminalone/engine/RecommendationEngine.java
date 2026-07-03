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
    private final DirectionalStrategySelector strategySelector;
    private final RecommendationRepository recommendations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private static final Logger logger = LoggerFactory.getLogger(RecommendationEngine.class);

    public RecommendationEngine(EngineConfigProvider configProvider,
            PositionSource positions,
            MarketDataProvider marketData,
            TechnicalSignalCalculator signals,
            DirectionalStrategySelector strategySelector,
            RecommendationRepository recommendations,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configProvider = configProvider;
        this.positions = positions;
        this.marketData = marketData;
        this.signals = signals;
        this.strategySelector = strategySelector;
        this.recommendations = recommendations;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public EngineRunResponse run(EngineRunRequest request) {
        ActiveEngineConfig active = configProvider.getActive();
        List<RecommendationResponse> emitted = new ArrayList<>();
        for (String symbol : symbolsFor(request)) {
            runSymbol(symbol, active).ifPresent(emitted::add);
            if (emitted.size() >= active.config().ranking().topN()) {
                break;
            }
        }
        return new EngineRunResponse(emitted);
    }

    private java.util.Optional<RecommendationResponse> runSymbol(String symbol, ActiveEngineConfig active) {
        EngineConfig config = active.config();
        PriceHistory history = safe(() -> marketData.getDailyBars(symbol));
        DirectionSignal signal = signals.calculate(history, config.signal());

        // The real IV-rank regime computation lands with Phase 5 AC2; until then
        // every run is treated as NORMAL, so only the debit cells are reachable.
        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        java.util.Optional<StrategyType> strategy = DirectionalStrategyMatrix.select(
                signal.direction(), regime.regime(), signal.conviction(), config.conviction());
        if (strategy.isEmpty()) {
            return java.util.Optional.empty();
        }

        OptionChain chain = safe(() -> marketData.getChain(symbol));
        return strategySelector.select(symbol, strategy.get(), signal, regime, chain, config)
                .map(candidate -> {
                    Recommendation saved = recommendations.save(toEntity(candidate, active.version()));
                    return RecommendationResponse.from(saved, candidate);
                });
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
    