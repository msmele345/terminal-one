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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

@Service
public class RecommendationEngine {

    private final EngineConfigProvider configProvider;
    private final PositionSource positions;
    private final MarketDataProvider marketData;
    private final TechnicalSignalCalculator signals;
    private final VolatilityRegimeCalculator regimes;
    private final DirectionalStrategySelector strategySelector;
    private final IncomeOverlaySelector incomeOverlaySelector;
    private final RecommendationRepository recommendations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private static final Logger logger = LoggerFactory.getLogger(RecommendationEngine.class);

    /** A covered call needs one round lot of the underlying (§2 eligibility). */
    private static final int SHARES_PER_LOT = 100;

    public RecommendationEngine(EngineConfigProvider configProvider,
            PositionSource positions,
            MarketDataProvider marketData,
            TechnicalSignalCalculator signals,
            VolatilityRegimeCalculator regimes,
            DirectionalStrategySelector strategySelector,
            IncomeOverlaySelector incomeOverlaySelector,
            RecommendationRepository recommendations,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configProvider = configProvider;
        this.positions = positions;
        this.marketData = marketData;
        this.signals = signals;
        this.regimes = regimes;
        this.strategySelector = strategySelector;
        this.incomeOverlaySelector = incomeOverlaySelector;
        this.recommendations = recommendations;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * The one engine path (Phase 6 AC6): every trigger — scheduled EOD batch or
     * on-demand lever-pull — runs through here via {@link EngineBatchRunner},
     * always inside a recorded batch run and always emitting signal snapshots.
     */
    @Transactional
    public EngineRunResponse run(EngineRunRequest request,
            long batchRunId,
            Consumer<SignalSnapshot> signalSnapshotSink) {
        Objects.requireNonNull(signalSnapshotSink, "signalSnapshotSink is required");
        ActiveEngineConfig active = configProvider.getActive();
        EngineConfig config = active.config();
        double portfolioValue = portfolioValueAtCost();

        // Phase 5 AC6 (§8): collect every surviving sized candidate across the
        // portfolio, then rank them globally — top-N is decided across all
        // underlyings, not the first N symbols encountered. Phase 6 AC3 (§2):
        // every underlying that produces no trade contributes an explicit
        // Abstention instead of being silently dropped.
        List<RecommendationCandidate> survivors = new ArrayList<>();
        List<Abstention> abstentions = new ArrayList<>();
        for (String symbol : symbolsFor(request)) {
            switch (resolveSymbol(symbol, active, portfolioValue, batchRunId, signalSnapshotSink)) {
                case Traded traded -> survivors.add(traded.candidate());
                case Abstained abstained -> abstentions.add(abstained.abstention());
            }
        }
        List<RecommendationCandidate> ranked = CandidateRanker.rank(survivors, config.ranking());

        List<RecommendationResponse> emitted = new ArrayList<>();
        for (RecommendationCandidate candidate : ranked) {
            Recommendation saved = recommendations.save(toEntity(candidate, active.version(), batchRunId));
            emitted.add(RecommendationResponse.from(saved, candidate));
        }
        return new EngineRunResponse(emitted, abstentions);
    }

    /**
     * Resolve one underlying to either a sized candidate or an explicit abstain
     * reason (strategy-matrix §2). The order of gates mirrors the matrix: no-data
     * first, then the direction × regime cell (weak/neutral abstain or a HIGH-IV
     * income overlay), then §6 guardrails and the §7 risk cap.
     */
    private SymbolOutcome resolveSymbol(String symbol,
            ActiveEngineConfig active,
            double portfolioValue,
            long batchRunId,
            Consumer<SignalSnapshot> signalSnapshotSink) {
        EngineConfig config = active.config();

        // §2 no-data abstain — source the chain first and never guess when it's missing.
        OptionChain chain = safe(() -> marketData.getChain(symbol));
        if (chain == null || chain.underlyingPrice() <= 0.0) {
            return new Abstained(new Abstention(symbol, AbstainReason.NO_MARKET_DATA.name(),
                    "Option chain / underlying price unavailable for " + symbol));
        }

        PriceHistory history = safe(() -> marketData.getDailyBars(symbol));
        DirectionSignal signal = signals.calculate(history, config.signal());
        VolatilityRegimeResult regime = regimes.calculate(symbol, chain, history, config.regime());
        captureSignalSnapshot(batchRunId, signalSnapshotSink, active.version(), symbol, signal, regime, chain);
        Optional<StrategyType> strategy = DirectionalStrategyMatrix.select(
                signal.direction(), regime.regime(), signal.conviction(), config.conviction());

        if (strategy.isEmpty()) {
            // §2 NEUTRAL × HIGH IV → an income overlay (covered call when a lot is
            // held, else a flagged cash-secured put entry). Every other empty cell
            // is a weak/neutral directional signal → explicit WEAK_SIGNAL abstain.
            if (signal.direction() == Direction.NEUTRAL && regime.regime() == VolatilityRegime.HIGH) {
                int held = heldShares(symbol);
                Optional<RecommendationCandidate> income = held >= SHARES_PER_LOT
                        ? incomeOverlaySelector.selectCoveredCall(symbol, signal, regime, chain, config,
                                held, portfolioValue)
                        : incomeOverlaySelector.selectCashSecuredPut(symbol, signal, regime, chain, config,
                                portfolioValue);
                return income.<SymbolOutcome>map(Traded::new).orElseGet(() -> new Abstained(
                        new Abstention(symbol, AbstainReason.NO_QUALIFYING_CANDIDATE.name(),
                                "No qualifying income overlay for " + symbol
                                        + " (see engine log for the guardrail detail)")));
            }
            return new Abstained(new Abstention(symbol, AbstainReason.WEAK_SIGNAL.name(),
                    weakSignalDetail(signal, config.conviction())));
        }

        CandidateSelection selection = strategySelector.selectWithRejections(
                symbol, strategy.get(), signal, regime, chain, config);
        if (selection.candidate().isEmpty()) {
            return new Abstained(abstentionFromRejections(symbol, selection.rejections()));
        }

        RecommendationCandidate unsized = selection.candidate().get();
        // Phase 5 AC5 (§7): cap defined risk to perTradeRiskPct of the portfolio.
        PositionSizer.Sizing sizing = PositionSizer.size(unsized.maxLoss(), portfolioValue,
                config.sizing().perTradeRiskPct());
        if (sizing.abstain()) {
            double budget = portfolioValue * config.sizing().perTradeRiskPct();
            logger.info("Rejected {} {} candidate: {} (max loss ${} exceeds ${} cap)",
                    unsized.symbol(), unsized.strategy(), PositionSizer.RISK_TOO_LARGE,
                    unsized.maxLoss(), budget);
            return new Abstained(new Abstention(symbol, PositionSizer.RISK_TOO_LARGE,
                    "max loss $%.2f exceeds the $%.2f per-trade cap".formatted(unsized.maxLoss(), budget)));
        }

        RecommendationRationale.Sizing rationaleSizing = new RecommendationRationale.Sizing(
                sizing.contracts(), unsized.maxLoss(), portfolioValue,
                config.sizing().perTradeRiskPct(), sizing.riskAmount());
        return new Traded(unsized.withSizing(
                sizing.contracts(), unsized.rationale().withSizing(rationaleSizing)));
    }

    private void captureSignalSnapshot(long batchRunId,
            Consumer<SignalSnapshot> signalSnapshotSink,
            int configVersion,
            String symbol,
            DirectionSignal signal,
            VolatilityRegimeResult regime,
            OptionChain chain) {
        signalSnapshotSink.accept(SignalSnapshot.fromBatchRun(
                batchRunId,
                configVersion,
                normalize(symbol),
                signal,
                regime,
                chain,
                LocalDate.now(clock),
                clock.instant()));
    }

    private static String weakSignalDetail(DirectionSignal signal, EngineConfig.Conviction conviction) {
        if (signal.direction() == Direction.NEUTRAL) {
            return "Signal NEUTRAL (direction score %.3f within the ±threshold); no directional edge"
                    .formatted(signal.directionScore());
        }
        return "%s conviction %d below the trade floor of %d"
                .formatted(signal.direction(), signal.conviction(), conviction.tradeFloor());
    }

    /**
     * Bubble the terminal §6 guardrail reason up as the explicit abstention so the
     * caller sees the precise cause (e.g. {@code NO_VALID_EXPIRY}); the detail is
     * preserved. Falls back to {@code NO_QUALIFYING_CANDIDATE} if the selector
     * returned empty without recording a specific reason.
     */
    private static Abstention abstentionFromRejections(String symbol, List<GuardrailRejection> rejections) {
        if (rejections.isEmpty()) {
            return new Abstention(symbol, AbstainReason.NO_QUALIFYING_CANDIDATE.name(),
                    "No qualifying structure for " + symbol);
        }
        GuardrailRejection primary = rejections.get(rejections.size() - 1);
        return new Abstention(symbol, primary.reason().name(), primary.detail());
    }

    /** One underlying resolves to exactly one of: a sized candidate, or an abstain reason. */
    private sealed interface SymbolOutcome permits Traded, Abstained {
    }

    private record Traded(RecommendationCandidate candidate) implements SymbolOutcome {
    }

    private record Abstained(Abstention abstention) implements SymbolOutcome {
    }

    private int heldShares(String symbol) {
        String normalized = normalize(symbol);
        return (int) Math.floor(positions.listStocks().stream()
                .filter(stock -> normalize(stock.getSymbol()).equals(normalized))
                .mapToDouble(stock -> stock.getQuantity().doubleValue())
                .filter(quantity -> quantity > 0.0)
                .sum());
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

    private Recommendation toEntity(RecommendationCandidate candidate, int configVersion, long batchRunId) {
        // Existing columns separate bought and sold option legs. Directional
        // long-single structures have no sold leg; covered calls have no bought
        // option leg because the stock shares are already held in the portfolio.
        RecommendationLeg bought = candidate.legs().stream()
                .filter(l -> "BUY".equals(l.action()))
                .findFirst()
                .orElse(null);
        RecommendationLeg sold = candidate.legs().stream()
                .filter(l -> "SELL".equals(l.action()))
                .findFirst()
                .orElse(null);
        if (bought == null && sold == null) {
            throw new IllegalStateException("candidate has no option legs");
        }
        return new Recommendation(
                candidate.symbol(),
                candidate.strategy(),
                candidate.signal().direction(),
                candidate.regime().regime(),
                candidate.signal().conviction(),
                RecommendationStatus.PAPER,
                configVersion,
                candidate.expiry(),
                bought == null ? null : bought.optionSymbol(),
                bought == null ? null : bought.strike(),
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
                Instant.now(clock),
                batchRunId);
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
