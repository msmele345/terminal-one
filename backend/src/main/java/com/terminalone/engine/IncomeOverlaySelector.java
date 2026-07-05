package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;
import com.terminalone.marketdata.OptionValuation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Phase 6 income overlays. AC1 implements covered calls only: sell one call per
 * 100 held shares, inside the income DTE window, with the upside cap called out
 * explicitly in the rationale. CSP and richer neutral/abstain behavior follow
 * in later Phase 6 acceptance criteria.
 */
@Component
class IncomeOverlaySelector {

    private static final Logger logger = LoggerFactory.getLogger(IncomeOverlaySelector.class);
    private static final double RISK_FREE_RATE = 0.04;
    private static final double DAYS_PER_YEAR = 365.0;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);
    private static final int SHARES_PER_CONTRACT = 100;

    private final OptionAnalytics analytics;
    private final Clock clock;
    private final EarningsCalendar earningsCalendar;

    IncomeOverlaySelector(OptionAnalytics analytics, Clock clock) {
        this(analytics, clock, EarningsCalendar.unavailable());
    }

    @Autowired
    IncomeOverlaySelector(OptionAnalytics analytics, Clock clock, EarningsCalendar earningsCalendar) {
        this.analytics = analytics;
        this.clock = clock;
        this.earningsCalendar = earningsCalendar;
    }

    Optional<RecommendationCandidate> selectCoveredCall(String symbol,
                                                        DirectionSignal signal,
                                                        VolatilityRegimeResult regime,
                                                        OptionChain chain,
                                                        EngineConfig config,
                                                        int heldShares,
                                                        double portfolioValue) {
        if (heldShares < SHARES_PER_CONTRACT || chain == null
                || chain.underlyingPrice() <= 0.0 || chain.contracts().isEmpty()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now(clock);
        ExpirySelection expiry = selectExpiry(symbol, StrategyType.COVERED_CALL, chain, today,
                config.expiry().incomeDteTarget(), config.expiry().incomeDteWindow(), config);
        if (expiry.expiry().isEmpty()) {
            if (!expiry.hadWindowExpiry()) {
                logger.info("Rejected {} {} candidate: {} (No expiration falls inside income DTE window [{},{}])",
                        symbol, StrategyType.COVERED_CALL, GuardrailReason.NO_VALID_EXPIRY,
                        config.expiry().incomeDteWindow().min(), config.expiry().incomeDteWindow().max());
            }
            return Optional.empty();
        }
        LocalDate selectedExpiry = expiry.expiry().orElseThrow();

        int dte = (int) ChronoUnit.DAYS.between(today, selectedExpiry);
        double timeToExpiry = dte / DAYS_PER_YEAR;
        List<PricedContract> calls = chain.contracts().stream()
                .filter(c -> c.callPut() == CallPut.CALL)
                .filter(c -> c.expiration().equals(selectedExpiry))
                .filter(c -> passesOpenInterest(symbol, StrategyType.COVERED_CALL, c, config))
                .filter(c -> passesBidAskLiquidity(symbol, StrategyType.COVERED_CALL, c, config))
                .map(c -> price(c, chain.underlyingPrice(), timeToExpiry))
                .flatMap(Optional::stream)
                .toList();
        if (calls.isEmpty()) {
            return Optional.empty();
        }

        PricedContract shortCall = calls.stream()
                .min(Comparator.comparingDouble(c -> Math.abs(c.absDelta() - config.strikes().coveredCallDelta())))
                .orElseThrow();
        int contracts = heldShares / SHARES_PER_CONTRACT;
        double premium = shortCall.mid();
        double spot = chain.underlyingPrice();
        double maxProfit = Math.max(0.0, (shortCall.strike() - spot + premium) * SHARES_PER_CONTRACT);
        double breakeven = spot - premium;
        double pop = probabilityBelow(spot, shortCall.strike(), timeToExpiry, shortCall.iv());
        if (pop < config.filters().minPopCredit()) {
            logger.info("Rejected {} {} candidate: {} (POP {} < {})",
                    symbol, StrategyType.COVERED_CALL, GuardrailReason.POP_BELOW_FLOOR,
                    pop, config.filters().minPopCredit());
            return Optional.empty();
        }

        double rawEv = premium * SHARES_PER_CONTRACT;
        double score = rawEv * config.ranking().regimeFitMatch();
        RecommendationRationale rationale = new RecommendationRationale(
                signal.toRationale(),
                new RecommendationRationale.Regime(regime.regime(), regime.reason(), shortCall.iv()),
                new RecommendationRationale.Selection("income", dte,
                        0.0, config.strikes().coveredCallDelta(), 0.0, shortCall.absDelta()),
                new RecommendationRationale.Pricing(0.0, -premium, breakeven, pop,
                        maxProfit, 0.0, 0.0, rawEv),
                new RecommendationRationale.Sizing(contracts, 0.0, portfolioValue,
                        config.sizing().perTradeRiskPct(), 0.0),
                new RecommendationRationale.IncomeOverlay(
                        "CAPS_UPSIDE_ABOVE_STRIKE",
                        "Covered call income on held shares; upside is capped above the short-call strike.",
                        heldShares,
                        contracts,
                        shortCall.strike(),
                        premium,
                        maxProfit),
                null,
                expiry.warnings());

        return Optional.of(new RecommendationCandidate(symbol, StrategyType.COVERED_CALL, signal, regime,
                selectedExpiry, List.of(leg("SELL", shortCall)), -premium, pop,
                maxProfit, 0.0, 0.0, score, contracts, rationale));
    }

    /**
     * Phase 6 AC2: cash-secured put. When the NEUTRAL + HIGH IV cell has no held
     * lot for a covered call, offer selling one {@code cspDelta} (0.30Δ) put in the
     * income DTE window as an <em>entry</em> suggestion. It is always presentable
     * but flagged with the collateral it requires (strike × 100 × contracts) — V1
     * never assumes a tracked cash balance, so it is sized to a single lot rather
     * than the per-trade risk cap.
     */
    Optional<RecommendationCandidate> selectCashSecuredPut(String symbol,
                                                           DirectionSignal signal,
                                                           VolatilityRegimeResult regime,
                                                           OptionChain chain,
                                                           EngineConfig config,
                                                           double portfolioValue) {
        if (chain == null || chain.underlyingPrice() <= 0.0 || chain.contracts().isEmpty()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now(clock);
        ExpirySelection expiry = selectExpiry(symbol, StrategyType.CASH_SECURED_PUT, chain, today,
                config.expiry().incomeDteTarget(), config.expiry().incomeDteWindow(), config);
        if (expiry.expiry().isEmpty()) {
            if (!expiry.hadWindowExpiry()) {
                logger.info("Rejected {} {} candidate: {} (No expiration falls inside income DTE window [{},{}])",
                        symbol, StrategyType.CASH_SECURED_PUT, GuardrailReason.NO_VALID_EXPIRY,
                        config.expiry().incomeDteWindow().min(), config.expiry().incomeDteWindow().max());
            }
            return Optional.empty();
        }
        LocalDate selectedExpiry = expiry.expiry().orElseThrow();

        int dte = (int) ChronoUnit.DAYS.between(today, selectedExpiry);
        double timeToExpiry = dte / DAYS_PER_YEAR;
        List<PricedContract> puts = chain.contracts().stream()
                .filter(c -> c.callPut() == CallPut.PUT)
                .filter(c -> c.expiration().equals(selectedExpiry))
                .filter(c -> passesOpenInterest(symbol, StrategyType.CASH_SECURED_PUT, c, config))
                .filter(c -> passesBidAskLiquidity(symbol, StrategyType.CASH_SECURED_PUT, c, config))
                .map(c -> price(c, chain.underlyingPrice(), timeToExpiry))
                .flatMap(Optional::stream)
                .toList();
        if (puts.isEmpty()) {
            return Optional.empty();
        }

        PricedContract shortPut = puts.stream()
                .min(Comparator.comparingDouble(c -> Math.abs(c.absDelta() - config.strikes().cspDelta())))
                .orElseThrow();
        int contracts = 1; // entry suggestion; V1 does not track cash — never assume more than one lot
        double premium = shortPut.mid();
        double spot = chain.underlyingPrice();
        double strike = shortPut.strike();
        // A short put keeps the full premium while the underlying holds above the strike.
        double pop = probabilityAbove(spot, strike, timeToExpiry, shortPut.iv());
        if (pop < config.filters().minPopCredit()) {
            logger.info("Rejected {} {} candidate: {} (POP {} < {})",
                    symbol, StrategyType.CASH_SECURED_PUT, GuardrailReason.POP_BELOW_FLOOR,
                    pop, config.filters().minPopCredit());
            return Optional.empty();
        }

        double maxProfit = premium * SHARES_PER_CONTRACT;                          // keep the premium
        double maxLoss = Math.max(0.0, (strike - premium) * SHARES_PER_CONTRACT);  // assigned, then → 0
        double breakeven = strike - premium;
        double requiredCapital = strike * SHARES_PER_CONTRACT * contracts;         // cash-secured collateral
        double riskReward = maxLoss > 0.0 ? maxProfit / maxLoss : 0.0;
        double rawEv = premium * SHARES_PER_CONTRACT;
        double score = rawEv * config.ranking().regimeFitMatch();
        RecommendationRationale rationale = new RecommendationRationale(
                signal.toRationale(),
                new RecommendationRationale.Regime(regime.regime(), regime.reason(), shortPut.iv()),
                new RecommendationRationale.Selection("income", dte,
                        0.0, config.strikes().cspDelta(), 0.0, shortPut.absDelta()),
                new RecommendationRationale.Pricing(0.0, -premium, breakeven, pop,
                        maxProfit, maxLoss, riskReward, rawEv),
                new RecommendationRationale.Sizing(contracts, maxLoss, portfolioValue,
                        config.sizing().perTradeRiskPct(), maxLoss),
                null,
                new RecommendationRationale.EntrySuggestion(
                        "REQUIRES_CASH_COLLATERAL",
                        "Cash-secured put entry suggestion; requires strike × 100 × contracts in cash "
                                + "collateral (V1 does not track your cash balance).",
                        contracts,
                        strike,
                        premium,
                        requiredCapital),
                expiry.warnings());

        return Optional.of(new RecommendationCandidate(symbol, StrategyType.CASH_SECURED_PUT, signal, regime,
                selectedExpiry, List.of(leg("SELL", shortPut)), -premium, pop,
                maxProfit, maxLoss, riskReward, score, contracts, rationale));
    }

    private ExpirySelection selectExpiry(String symbol,
                                         StrategyType strategy,
                                         OptionChain chain,
                                         LocalDate today,
                                         EngineConfig.IntRange target,
                                         EngineConfig.IntRange window,
                                         EngineConfig config) {
        double targetMid = 0.5 * (target.min() + target.max());
        List<LocalDate> expiries = chain.contracts().stream()
                .map(OptionContract::expiration)
                .distinct()
                .filter(e -> e.isAfter(today))
                .filter(e -> {
                    long dte = ChronoUnit.DAYS.between(today, e);
                    return dte >= window.min() && dte <= window.max();
                })
                .sorted(Comparator
                        .comparingDouble((LocalDate e) -> Math.abs(ChronoUnit.DAYS.between(today, e) - targetMid))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        if (expiries.isEmpty()) {
            return new ExpirySelection(Optional.empty(), false, List.of());
        }
        List<RecommendationRationale.Warning> warnings = new ArrayList<>();
        for (LocalDate expiry : expiries) {
            if (!config.filters().avoidEarnings()) {
                return new ExpirySelection(Optional.of(expiry), true, warnings);
            }
            EarningsCheck check = earningsCalendar.check(symbol, today, expiry);
            if (check.status() == EarningsCheck.Status.SPANS_EARNINGS) {
                logger.info("Rejected {} {} candidate: {} (expiration {} spans earnings date {})",
                        symbol, strategy, GuardrailReason.EARNINGS_SPANS_EXPIRY, expiry,
                        check.earningsDate());
                continue;
            }
            if (check.status() == EarningsCheck.Status.UNAVAILABLE) {
                warnings.add(RecommendationRationale.Warning.earningsCalendarUnavailable());
            }
            return new ExpirySelection(Optional.of(expiry), true, warnings);
        }
        return new ExpirySelection(Optional.empty(), true, warnings);
    }

    private boolean passesOpenInterest(String symbol, StrategyType strategy, OptionContract contract,
            EngineConfig config) {
        if (contract.openInterest() >= config.filters().minOpenInterest()) {
            return true;
        }
        logger.info("Rejected {} {} candidate: {} ({} OI {} < {})",
                symbol, strategy, GuardrailReason.LOW_OPEN_INTEREST,
                contract.optionSymbol(), contract.openInterest(), config.filters().minOpenInterest());
        return false;
    }

    private boolean passesBidAskLiquidity(String symbol, StrategyType strategy, OptionContract contract,
            EngineConfig config) {
        double mid = contract.mid();
        double width = contract.ask() - contract.bid();
        boolean liquid = contract.bid() > 0.0
                && contract.ask() > contract.bid()
                && mid > 0.0
                && (width <= config.filters().maxBidAskAbsolute()
                        || width / mid <= config.filters().maxBidAskPctOfMid());
        if (liquid) {
            return true;
        }
        logger.info("Rejected {} {} candidate: {} ({} bid/ask {}/{} exceeds spread limits)",
                symbol, strategy, GuardrailReason.ILLIQUID_BID_ASK,
                contract.optionSymbol(), contract.bid(), contract.ask());
        return false;
    }

    private Optional<PricedContract> price(OptionContract contract, double spot, double timeToExpiry) {
        OptionInput base = new OptionInput(spot, contract.strike(), timeToExpiry,
                RISK_FREE_RATE, 0.0, 0.0, contract.callPut());
        double iv = analytics.impliedVolatilityFromQuote(base, contract.bid(), contract.ask());
        if (!Double.isFinite(iv)) {
            return Optional.empty();
        }
        OptionValuation valuation = analytics.value(new OptionInput(spot, contract.strike(), timeToExpiry,
                RISK_FREE_RATE, 0.0, iv, contract.callPut()));
        return Optional.of(new PricedContract(contract, iv, valuation.delta(), contract.mid()));
    }

    private static RecommendationLeg leg(String action, PricedContract priced) {
        OptionContract c = priced.contract();
        return new RecommendationLeg(action, c.optionSymbol(), c.callPut(), c.strike(), c.expiration(),
                c.bid(), c.ask(), priced.mid(), priced.delta());
    }

    private static double probabilityBelow(double spot, double strike, double timeToExpiry, double iv) {
        return 1.0 - probabilityAbove(spot, strike, timeToExpiry, iv);
    }

    private static double probabilityAbove(double spot, double strike, double timeToExpiry, double iv) {
        if (spot <= 0.0 || strike <= 0.0 || timeToExpiry <= 0.0 || iv <= 0.0) {
            return 0.0;
        }
        double d2 = (Math.log(spot / strike)
                + (RISK_FREE_RATE - 0.5 * iv * iv) * timeToExpiry)
                / (iv * Math.sqrt(timeToExpiry));
        return normCdf(d2);
    }

    private static double normCdf(double x) {
        if (x < -8.0) return 0.0;
        if (x > 8.0) return 1.0;
        double ax = Math.abs(x);
        double k = 1.0 / (1.0 + 0.2316419 * ax);
        double poly = ((((1.330274429 * k - 1.821255978) * k + 1.781477937) * k - 0.356563782) * k
                + 0.319381530) * k;
        double pAx = 1.0 - Math.exp(-0.5 * ax * ax) / SQRT_2PI * poly;
        return x >= 0.0 ? pAx : 1.0 - pAx;
    }

    private record PricedContract(OptionContract contract, double iv, double delta, double mid) {

        double strike() {
            return contract.strike();
        }

        double absDelta() {
            return Math.abs(delta);
        }
    }

    private record ExpirySelection(
            Optional<LocalDate> expiry,
            boolean hadWindowExpiry,
            List<RecommendationRationale.Warning> warnings) {
    }
}
