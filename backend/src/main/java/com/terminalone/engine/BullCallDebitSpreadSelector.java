package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;
import com.terminalone.marketdata.OptionValuation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Phase 4's single supported matrix cell: BULLISH + NORMAL IV -> Bull Call
 * Debit Spread. Later phases can add sibling selectors without changing the
 * engine/config/repository boundary established here.
 */
@Component
class BullCallDebitSpreadSelector {

    private static final double RISK_FREE_RATE = 0.04;
    private static final double DAYS_PER_YEAR = 365.0;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    private final OptionAnalytics analytics;
    private final Clock clock;

    BullCallDebitSpreadSelector(OptionAnalytics analytics, Clock clock) {
        this.analytics = analytics;
        this.clock = clock;
    }

    Optional<RecommendationCandidate> select(String symbol, DirectionSignal signal,
                                             VolatilityRegimeResult regime,
                                             OptionChain chain,
                                             EngineConfig config) {
        if (chain == null || chain.underlyingPrice() <= 0.0 || chain.contracts().isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock);
        Optional<LocalDate> expiry = selectExpiry(chain, today, config.expiry().debitDteTarget(),
                config.expiry().debitDteWindow());
        if (expiry.isEmpty()) {
            return Optional.empty();
        }
        long dte = ChronoUnit.DAYS.between(today, expiry.get());
        double timeToExpiry = dte / DAYS_PER_YEAR;
        String band = convictionBand(signal.conviction(), config.conviction());
        double longTarget = deltaForBand(config.strikes().debitLongDelta(), band);
        double shortTarget = deltaForBand(config.strikes().debitShortDelta(), band);

        List<PricedContract> calls = chain.contracts().stream()
                .filter(c -> c.callPut() == CallPut.CALL)
                .filter(c -> c.expiration().equals(expiry.get()))
                .filter(c -> c.openInterest() >= config.filters().minOpenInterest())
                .filter(c -> c.bid() > 0.0 && c.ask() > c.bid())
                .map(c -> price(c, chain.underlyingPrice(), timeToExpiry))
                .flatMap(Optional::stream)
                .toList();
        if (calls.size() < 2) {
            return Optional.empty();
        }

        PricedContract longLeg = nearestDelta(calls, longTarget);
        Optional<PricedContract> shortLeg = calls.stream()
                .filter(c -> c.contract().strike() > longLeg.contract().strike())
                .min(Comparator.comparingDouble(c -> Math.abs(c.delta() - shortTarget)));
        if (shortLeg.isEmpty()) {
            return Optional.empty();
        }

        double width = shortLeg.get().contract().strike() - longLeg.contract().strike();
        double debit = longLeg.mid() - shortLeg.get().mid();
        if (width <= 0.0 || debit <= 0.0 || debit >= width) {
            return Optional.empty();
        }

        double maxLoss = debit * 100.0;
        double maxProfit = (width - debit) * 100.0;
        double riskReward = maxProfit / maxLoss;
        if (riskReward < config.filters().minRewardRiskDebit()) {
            return Optional.empty();
        }

        double averageIv = 0.5 * (longLeg.iv() + shortLeg.get().iv());
        double breakeven = longLeg.contract().strike() + debit;
        double pop = probabilityAbove(chain.underlyingPrice(), breakeven, timeToExpiry, averageIv);
        double rawEv = pop * maxProfit - (1.0 - pop) * maxLoss;
        double convFactor = 0.5 + 0.5 * (signal.conviction() / 100.0);
        double score = rawEv * config.ranking().regimeFitNeutral() * convFactor;

        List<RecommendationLeg> legs = List.of(
                leg("BUY", longLeg),
                leg("SELL", shortLeg.get()));
        RecommendationRationale rationale = new RecommendationRationale(
                signal.toRationale(),
                new RecommendationRationale.Regime(regime.regime(), regime.reason(), averageIv),
                new RecommendationRationale.Selection(band, (int) dte, longTarget, shortTarget,
                        longLeg.delta(), shortLeg.get().delta()),
                new RecommendationRationale.Pricing(width, debit, breakeven, pop, maxProfit, maxLoss,
                        riskReward, rawEv));

        return Optional.of(new RecommendationCandidate(symbol, StrategyType.BULL_CALL_DEBIT_SPREAD,
                signal, regime, expiry.get(), legs, debit, pop, maxProfit, maxLoss, riskReward, score,
                rationale));
    }

    private Optional<LocalDate> selectExpiry(OptionChain chain, LocalDate today,
                                             EngineConfig.IntRange target,
                                             EngineConfig.IntRange window) {
        double targetMid = 0.5 * (target.min() + target.max());
        return chain.contracts().stream()
                .map(OptionContract::expiration)
                .distinct()
                .filter(e -> e.isAfter(today))
                .filter(e -> {
                    long dte = ChronoUnit.DAYS.between(today, e);
                    return dte >= window.min() && dte <= window.max();
                })
                .min(Comparator
                        .comparingDouble((LocalDate e) -> Math.abs(ChronoUnit.DAYS.between(today, e) - targetMid))
                        .thenComparing(Comparator.naturalOrder()));
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

    private static PricedContract nearestDelta(List<PricedContract> contracts, double target) {
        return contracts.stream()
                .min(Comparator.comparingDouble(c -> Math.abs(c.delta() - target)))
                .orElseThrow();
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

    /** Risk-neutral probability that the underlying finishes above breakeven. */
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

    private record PricedContract(OptionContract contract, double iv, double delta, double mid) {
    }
}
