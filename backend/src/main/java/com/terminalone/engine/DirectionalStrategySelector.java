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
import java.util.function.Predicate;

/**
 * Builds the concrete option structure for a directional strategy-matrix cell:
 * debit verticals (§4.2), credit verticals (§4.1), and the high-conviction long
 * single leg (§4.3). Strikes are chosen by target |delta| off the live chain,
 * expiries from the structure's DTE window (§5), and POP/max-profit/loss via
 * the same in-house Black-Scholes analytics (D22).
 *
 * <p>Sign convention: {@code entryDebit} is the net premium paid per share —
 * positive for debit/long structures, negative (a credit received) for credit
 * spreads.</p>
 *
 * <p>Phase 5 AC4 applies the §6 candidate filters for liquidity, min-credit,
 * POP, reward:risk, and DTE with logged rejection reasons. §7 contract sizing
 * and the §8 regime-fit ranking land with later Phase 5 ACs.</p>
 */
@Component
class DirectionalStrategySelector {

    private static final Logger logger = LoggerFactory.getLogger(DirectionalStrategySelector.class);
    private static final double RISK_FREE_RATE = 0.04;
    private static final double DAYS_PER_YEAR = 365.0;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    private final OptionAnalytics analytics;
    private final Clock clock;
    private final EarningsCalendar earningsCalendar;

    DirectionalStrategySelector(OptionAnalytics analytics, Clock clock) {
        this(analytics, clock, EarningsCalendar.unavailable());
    }

    @Autowired
    DirectionalStrategySelector(OptionAnalytics analytics, Clock clock, EarningsCalendar earningsCalendar) {
        this.analytics = analytics;
        this.clock = clock;
        this.earningsCalendar = earningsCalendar;
    }

    Optional<RecommendationCandidate> select(String symbol, StrategyType strategy,
                                             DirectionSignal signal,
                                             VolatilityRegimeResult regime,
                                             OptionChain chain,
                                             EngineConfig config) {
        return selectWithRejections(symbol, strategy, signal, regime, chain, config).candidate();
    }

    CandidateSelection selectWithRejections(String symbol, StrategyType strategy,
                                            DirectionSignal signal,
                                            VolatilityRegimeResult regime,
                                            OptionChain chain,
                                            EngineConfig config) {
        List<GuardrailRejection> rejections = new ArrayList<>();
        List<RecommendationRationale.Warning> warnings = new ArrayList<>();
        if (chain == null || chain.underlyingPrice() <= 0.0 || chain.contracts().isEmpty()) {
            return rejected(rejections);
        }
        Structure structure = Structure.of(strategy);
        LocalDate today = LocalDate.now(clock);
        EngineConfig.IntRange dteTarget = structure.credit()
                ? config.expiry().creditDteTarget() : config.expiry().debitDteTarget();
        EngineConfig.IntRange dteWindow = structure.credit()
                ? config.expiry().creditDteWindow() : config.expiry().debitDteWindow();
        ExpirySelection expiry = selectExpiry(symbol, strategy, chain, today, dteTarget, dteWindow,
                config, rejections, warnings);
        if (expiry.expiry().isEmpty()) {
            if (!expiry.hadWindowExpiry()) {
                rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.NO_VALID_EXPIRY,
                        "No expiration falls inside DTE window [%d,%d]".formatted(
                                dteWindow.min(), dteWindow.max())));
            }
            return rejected(rejections);
        }
        LocalDate selectedExpiry = expiry.expiry().orElseThrow();
        int dte = (int) ChronoUnit.DAYS.between(today, selectedExpiry);
        double timeToExpiry = dte / DAYS_PER_YEAR;
        String band = convictionBand(signal.conviction(), config.conviction());

        List<PricedContract> contracts = chain.contracts().stream()
                .filter(c -> c.callPut() == structure.optionType())
                .filter(c -> c.expiration().equals(selectedExpiry))
                .filter(c -> passesOpenInterest(symbol, strategy, c, config, rejections))
                .filter(c -> passesBidAskLiquidity(symbol, strategy, c, config, rejections))
                .map(c -> price(c, chain.underlyingPrice(), timeToExpiry))
                .flatMap(Optional::stream)
                .toList();

        Selected selected = switch (structure.kind()) {
            case DEBIT_SPREAD -> debitSpread(structure, band, contracts, config);
            case CREDIT_SPREAD -> creditSpread(structure, band, contracts, config);
            case LONG_SINGLE -> longSingleLeg(structure, contracts, config);
        };
        if (selected == null) {
            return rejected(rejections);
        }

        double pop = probabilityOfFinishing(structure, chain.underlyingPrice(), selected.breakeven(),
                timeToExpiry, selected.averageIv());
        double rawEv = pop * selected.maxProfit() - (1.0 - pop) * selected.maxLoss();
        double convFactor = 0.5 + 0.5 * (signal.conviction() / 100.0);
        // Phase 5 AC6 (§8): regime-fit factor matches credit↔HIGH / debit-long↔LOW,
        // neutral at NORMAL, counter otherwise — applied to the candidate score.
        double regimeFit = regimeFactor(strategy, regime.regime(), config.ranking());
        double score = rawEv * regimeFit * convFactor;
        double riskReward = selected.maxProfit() / selected.maxLoss();
        if (structure.credit()) {
            double credit = -selected.entryDebit();
            double minimum = selected.width() * config.filters().minCreditToWidthRatio();
            if (credit < minimum) {
                rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.MIN_CREDIT,
                        "credit %.4f < %.4f required for %.4f width".formatted(
                                credit, minimum, selected.width())));
                return rejected(rejections);
            }
            if (pop < config.filters().minPopCredit()) {
                rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.POP_BELOW_FLOOR,
                        "POP %.4f < %.4f".formatted(pop, config.filters().minPopCredit())));
                return rejected(rejections);
            }
        } else if (riskReward < config.filters().minRewardRiskDebit()) {
            rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.REWARD_RISK_BELOW_FLOOR,
                    "reward:risk %.4f < %.4f".formatted(riskReward,
                            config.filters().minRewardRiskDebit())));
            return rejected(rejections);
        }

        RecommendationRationale rationale = new RecommendationRationale(
                signal.toRationale(),
                new RecommendationRationale.Regime(regime.regime(), regime.reason(), selected.averageIv()),
                new RecommendationRationale.Selection(band, dte,
                        selected.longDeltaTarget(), selected.shortDeltaTarget(),
                        selected.selectedLongDelta(), selected.selectedShortDelta()),
                new RecommendationRationale.Pricing(selected.width(), selected.entryDebit(),
                        selected.breakeven(), pop, selected.maxProfit(), selected.maxLoss(),
                        riskReward, rawEv),
                null,
                null,
                null,
                warnings);

        return new CandidateSelection(Optional.of(new RecommendationCandidate(symbol, strategy, signal, regime, selectedExpiry,
                selected.legs(), selected.entryDebit(), pop, selected.maxProfit(), selected.maxLoss(),
                riskReward, score, 0, rationale)), List.copyOf(rejections));
    }

    /**
     * §8 regime-fit factor: credit↔HIGH or debit/long↔LOW = match (1.15 default);
     * NORMAL = neutral (1.00); the cross pairings = counter (0.85). Pure fn of
     * {@code (strategy, regime, ranking config)}, folded into the candidate's
     * {@code score} here at selection time; the {@link CandidateRanker} then sorts
     * on that pre-stamped score without re-deriving the factor.
     */
    static double regimeFactor(StrategyType strategy, VolatilityRegime regime,
                               EngineConfig.Ranking ranking) {
        boolean credit = strategy.isCredit();
        return switch (regime) {
            case NORMAL -> ranking.regimeFitNeutral();
            case HIGH -> credit ? ranking.regimeFitMatch() : ranking.regimeFitCounter();
            case LOW -> credit ? ranking.regimeFitCounter() : ranking.regimeFitMatch();
        };
    }

    private static CandidateSelection rejected(List<GuardrailRejection> rejections) {
        logRejections(rejections);
        return new CandidateSelection(Optional.empty(), List.copyOf(rejections));
    }

    private static void logRejections(List<GuardrailRejection> rejections) {
        for (GuardrailRejection rejection : rejections) {
            logger.info("Rejected {} {} candidate: {} ({})",
                    rejection.symbol(), rejection.strategy(), rejection.reason(), rejection.detail());
        }
    }

    private static boolean passesOpenInterest(String symbol, StrategyType strategy, OptionContract contract,
                                              EngineConfig config, List<GuardrailRejection> rejections) {
        if (contract.openInterest() >= config.filters().minOpenInterest()) {
            return true;
        }
        rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.LOW_OPEN_INTEREST,
                "%s OI %d < %d".formatted(contract.optionSymbol(), contract.openInterest(),
                        config.filters().minOpenInterest())));
        return false;
    }

    private static boolean passesBidAskLiquidity(String symbol, StrategyType strategy, OptionContract contract,
                                                 EngineConfig config, List<GuardrailRejection> rejections) {
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
        rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.ILLIQUID_BID_ASK,
                "%s bid/ask %.4f/%.4f exceeds spread limits".formatted(
                        contract.optionSymbol(), contract.bid(), contract.ask())));
        return false;
    }

    /** §4.2 — buy the long leg by delta, cap with a further-OTM short leg by delta. */
    private Selected debitSpread(Structure structure, String band, List<PricedContract> contracts,
                                 EngineConfig config) {
        if (contracts.size() < 2) {
            return null;
        }
        double longTarget = deltaForBand(config.strikes().debitLongDelta(), band);
        double shortTarget = deltaForBand(config.strikes().debitShortDelta(), band);
        PricedContract longLeg = nearestAbsDelta(contracts, longTarget);
        Optional<PricedContract> shortLeg = contracts.stream()
                .filter(furtherOtmThan(structure, longLeg.strike()))
                .min(Comparator.comparingDouble(c -> Math.abs(c.absDelta() - shortTarget)));
        if (shortLeg.isEmpty()) {
            return null;
        }

        double width = Math.abs(shortLeg.get().strike() - longLeg.strike());
        double debit = longLeg.mid() - shortLeg.get().mid();
        if (width <= 0.0 || debit <= 0.0 || debit >= width) {
            return null;
        }
        double maxLoss = debit * 100.0;
        double maxProfit = (width - debit) * 100.0;
        double breakeven = structure.optionType() == CallPut.CALL
                ? longLeg.strike() + debit
                : longLeg.strike() - debit;
        return new Selected(
                List.of(leg("BUY", longLeg), leg("SELL", shortLeg.get())),
                debit, width, breakeven, maxProfit, maxLoss,
                0.5 * (longLeg.iv() + shortLeg.get().iv()),
                longTarget, shortTarget, longLeg.absDelta(), shortLeg.get().absDelta());
    }

    /** §4.1 — sell the short leg by delta, buy protection one spread-width further OTM. */
    private Selected creditSpread(Structure structure, String band, List<PricedContract> contracts,
                                  EngineConfig config) {
        if (contracts.size() < 2) {
            return null;
        }
        double shortTarget = deltaForBand(config.strikes().creditShortDelta(), band);
        PricedContract shortLeg = nearestAbsDelta(contracts, shortTarget);
        double protectionStrike = structure.optionType() == CallPut.PUT
                ? shortLeg.strike() - config.strikes().creditSpreadWidth()
                : shortLeg.strike() + config.strikes().creditSpreadWidth();
        Optional<PricedContract> longLeg = contracts.stream()
                .filter(furtherOtmThan(structure, shortLeg.strike()))
                .min(Comparator.comparingDouble(c -> Math.abs(c.strike() - protectionStrike)));
        if (longLeg.isEmpty()) {
            return null;
        }

        double width = Math.abs(shortLeg.strike() - longLeg.get().strike());
        double credit = shortLeg.mid() - longLeg.get().mid();
        if (width <= 0.0 || credit <= 0.0 || credit >= width) {
            return null;
        }
        double maxProfit = credit * 100.0;
        double maxLoss = (width - credit) * 100.0;
        double breakeven = structure.optionType() == CallPut.PUT
                ? shortLeg.strike() - credit
                : shortLeg.strike() + credit;
        // The protection leg is width-driven (§4.1), so it carries no delta target.
        return new Selected(
                List.of(leg("SELL", shortLeg), leg("BUY", longLeg.get())),
                -credit, width, breakeven, maxProfit, maxLoss,
                0.5 * (shortLeg.iv() + longLeg.get().iv()),
                0.0, shortTarget, longLeg.get().absDelta(), shortLeg.absDelta());
    }

    /** §4.3 — buy a single slightly-ITM leg (high conviction + LOW IV only, enforced upstream). */
    private Selected longSingleLeg(Structure structure, List<PricedContract> contracts,
                                   EngineConfig config) {
        if (contracts.isEmpty()) {
            return null;
        }
        double target = config.strikes().longSingleLegDelta();
        PricedContract chosen = nearestAbsDelta(contracts, target);
        double premium = chosen.mid();
        if (premium <= 0.0) {
            return null;
        }
        double maxLoss = premium * 100.0;
        boolean call = structure.optionType() == CallPut.CALL;
        double breakeven = call ? chosen.strike() + premium : chosen.strike() - premium;
        // A long option's true max profit is unbounded; model it at a one-sigma
        // favorable move so §8's EV has a finite, comparable basis.
        double oneSigmaMove = chosen.spot()
                * Math.exp((call ? 1.0 : -1.0) * chosen.iv() * Math.sqrt(chosen.timeToExpiry()));
        double intrinsicAtMove = call ? oneSigmaMove - chosen.strike() : chosen.strike() - oneSigmaMove;
        double modeledProfit = (intrinsicAtMove - premium) * 100.0;
        if (modeledProfit <= 0.0) {
            return null;
        }
        return new Selected(
                List.of(leg("BUY", chosen)),
                premium, 0.0, breakeven, modeledProfit, maxLoss, chosen.iv(),
                target, 0.0, chosen.absDelta(), 0.0);
    }

    private ExpirySelection selectExpiry(String symbol,
                                         StrategyType strategy,
                                         OptionChain chain,
                                         LocalDate today,
                                         EngineConfig.IntRange target,
                                         EngineConfig.IntRange window,
                                         EngineConfig config,
                                         List<GuardrailRejection> rejections,
                                         List<RecommendationRationale.Warning> warnings) {
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
            return new ExpirySelection(Optional.empty(), false);
        }
        for (LocalDate expiry : expiries) {
            if (!config.filters().avoidEarnings()) {
                return new ExpirySelection(Optional.of(expiry), true);
            }
            EarningsCheck check = earningsCalendar.check(symbol, today, expiry);
            if (check.status() == EarningsCheck.Status.SPANS_EARNINGS) {
                rejections.add(new GuardrailRejection(symbol, strategy, GuardrailReason.EARNINGS_SPANS_EXPIRY,
                        "expiration %s spans earnings date %s".formatted(expiry, check.earningsDate())));
                continue;
            }
            if (check.status() == EarningsCheck.Status.UNAVAILABLE) {
                warnings.add(RecommendationRationale.Warning.earningsCalendarUnavailable());
            }
            return new ExpirySelection(Optional.of(expiry), true);
        }
        return new ExpirySelection(Optional.empty(), true);
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
        return Optional.of(new PricedContract(contract, iv, valuation.delta(), contract.mid(),
                spot, timeToExpiry));
    }

    private static PricedContract nearestAbsDelta(List<PricedContract> contracts, double target) {
        return contracts.stream()
                .min(Comparator.comparingDouble(c -> Math.abs(c.absDelta() - target)))
                .orElseThrow();
    }

    /** Further out-of-the-money than the given strike, for this structure's option type. */
    private static Predicate<PricedContract> furtherOtmThan(Structure structure, double strike) {
        return structure.optionType() == CallPut.CALL
                ? c -> c.strike() > strike
                : c -> c.strike() < strike;
    }

    private static RecommendationLeg leg(String action, PricedContract priced) {
        OptionContract c = priced.contract();
        return new RecommendationLeg(action, c.optionSymbol(), c.callPut(), c.strike(), c.expiration(),
                c.bid(), c.ask(), priced.mid(), priced.delta());
    }

    private static String convictionBand(int conviction, EngineConfig.Conviction config) {
        if (conviction >= config.bands().high().min()) {
            return "high";
        }
        if (conviction >= config.bands().confident().min()) {
            return "confident";
        }
        return "standard";
    }

    private static double deltaForBand(EngineConfig.DeltaBands deltas, String band) {
        return switch (band) {
            case "high" -> deltas.high();
            case "confident" -> deltas.confident();
            default -> deltas.standard();
        };
    }

    /**
     * Risk-neutral probability that the underlying finishes on the structure's
     * profitable side of breakeven: above it for bullish structures, below it
     * for bearish ones.
     */
    private static double probabilityOfFinishing(Structure structure, double spot, double breakeven,
                                                 double timeToExpiry, double iv) {
        double above = probabilityAbove(spot, breakeven, timeToExpiry, iv);
        return structure.bullish() ? above : 1.0 - above;
    }

    private static double probabilityAbove(double spot, double breakeven, double timeToExpiry, double iv) {
        if (spot <= 0.0 || breakeven <= 0.0 || timeToExpiry <= 0.0 || iv <= 0.0) {
            return 0.0;
        }
        double d2 = (Math.log(spot / breakeven)
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

    private enum Kind { DEBIT_SPREAD, CREDIT_SPREAD, LONG_SINGLE }

    /** How a strategy trades: structure kind, which side of the chain, and its profitable direction. */
    private record Structure(Kind kind, CallPut optionType, boolean bullish) {

        static Structure of(StrategyType strategy) {
            return switch (strategy) {
                case BULL_CALL_DEBIT_SPREAD -> new Structure(Kind.DEBIT_SPREAD, CallPut.CALL, true);
                case BEAR_PUT_DEBIT_SPREAD -> new Structure(Kind.DEBIT_SPREAD, CallPut.PUT, false);
                case BULL_PUT_CREDIT_SPREAD -> new Structure(Kind.CREDIT_SPREAD, CallPut.PUT, true);
                case BEAR_CALL_CREDIT_SPREAD -> new Structure(Kind.CREDIT_SPREAD, CallPut.CALL, false);
                case LONG_CALL -> new Structure(Kind.LONG_SINGLE, CallPut.CALL, true);
                case LONG_PUT -> new Structure(Kind.LONG_SINGLE, CallPut.PUT, false);
                case COVERED_CALL -> throw new IllegalArgumentException(
                        "Covered calls are selected by IncomeOverlaySelector");
                case CASH_SECURED_PUT -> throw new IllegalArgumentException(
                        "Cash-secured puts are selected by IncomeOverlaySelector");
            };
        }

        boolean credit() {
            return kind == Kind.CREDIT_SPREAD;
        }
    }

    /** The chosen legs plus the structure economics they imply. */
    private record Selected(
            List<RecommendationLeg> legs,
            double entryDebit,
            double width,
            double breakeven,
            double maxProfit,
            double maxLoss,
            double averageIv,
            double longDeltaTarget,
            double shortDeltaTarget,
            double selectedLongDelta,
            double selectedShortDelta) {
    }

    private record ExpirySelection(Optional<LocalDate> expiry, boolean hadWindowExpiry) {
    }

    private record PricedContract(OptionContract contract, double iv, double delta, double mid,
                                  double spot, double timeToExpiry) {

        double strike() {
            return contract.strike();
        }

        double absDelta() {
            return Math.abs(delta);
        }
    }
}
