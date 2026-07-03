package com.terminalone.marketdata;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Derives a single representative at-the-money implied volatility from a delayed
 * option chain (Phase 3 AC5, D22/FR-8a). IV is inverted in-house via
 * {@link OptionAnalytics} from the bid/ask mid — never taken from the vendor.
 *
 * <p>Selection: the nearest expiry strictly after {@code today}, then the strike
 * closest to the underlying's price. The ATM call and put at that strike are each
 * inverted from their mid; the reading is the mean of whichever legs solve to a
 * finite vol. Returns {@link Optional#empty()} — rather than a misleading
 * zero/NaN — when the chain is unusable: no spot, no future expiry, or no leg with
 * a valid two-sided quote (AC6: no silent zeros).
 */
public final class AtmIvCalculator {

    private static final double STRIKE_EPS = 1e-6;
    private static final double DAYS_PER_YEAR = 365.0;

    private AtmIvCalculator() {
    }

    public static Optional<AtmIv> compute(OptionChain chain, LocalDate today,
                                          OptionAnalytics analytics, double riskFreeRate) {
        if (chain == null || chain.contracts().isEmpty() || chain.underlyingPrice() <= 0.0) {
            return Optional.empty();
        }
        double spot = chain.underlyingPrice();

        Optional<LocalDate> nearestExpiry = chain.contracts().stream()
                .map(OptionContract::expiration)
                .filter(e -> e.isAfter(today))
                .min(Comparator.naturalOrder());
        if (nearestExpiry.isEmpty()) {
            return Optional.empty();
        }
        LocalDate expiry = nearestExpiry.get();
        double timeToExpiry = ChronoUnit.DAYS.between(today, expiry) / DAYS_PER_YEAR;
        if (timeToExpiry <= 0.0) {
            return Optional.empty();
        }

        List<OptionContract> atExpiry = chain.contracts().stream()
                .filter(c -> c.expiration().equals(expiry))
                .toList();
        double atmStrike = atExpiry.stream()
                .min(Comparator.comparingDouble(c -> Math.abs(c.strike() - spot)))
                .map(OptionContract::strike)
                .orElseThrow();

        double ivSum = 0.0;
        int legs = 0;
        for (OptionContract c : atExpiry) {
            if (Math.abs(c.strike() - atmStrike) > STRIKE_EPS) {
                continue; // only the ATM strike's call/put
            }
            OptionInput in = new OptionInput(spot, c.strike(), timeToExpiry, riskFreeRate, 0.0, 0.0, c.callPut());
            double iv = analytics.impliedVolatilityFromQuote(in, c.bid(), c.ask());
            if (Double.isFinite(iv)) {
                ivSum += iv;
                legs++;
            }
        }
        if (legs == 0) {
            return Optional.empty();
        }
        return Optional.of(new AtmIv(ivSum / legs, atmStrike, expiry, spot));
    }
}
