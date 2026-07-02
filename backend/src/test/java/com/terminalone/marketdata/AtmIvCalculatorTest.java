package com.terminalone.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pure-function coverage of the ATM IV selection + inversion (Phase 3 AC5). Uses
 * the real Black-Scholes analytics: synthetic contracts are priced at a known
 * sigma, so a correct inversion must round-trip back to that sigma.
 */
class AtmIvCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final double R = 0.04;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    /** A contract whose bid/ask mid equals the BS price at {@code sigma} (so IV inverts to sigma). */
    private OptionContract priced(CallPut cp, double strike, LocalDate expiry, double spot, double sigma) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, R, 0.0, sigma, cp)).price();
        return new OptionContract(cp + "-" + strike, cp, strike, expiry, price - 0.01, price + 0.01, 100);
    }

    private OptionContract quoted(CallPut cp, double strike, LocalDate expiry, double bid, double ask) {
        return new OptionContract(cp + "-" + strike, cp, strike, expiry, bid, ask, 100);
    }

    @Test
    void invertsAtmIvFromNearestStrikeAveragingCallAndPut() {
        double spot = 100.0;
        double sigma = 0.30;
        LocalDate expiry = TODAY.plusDays(45);
        OptionChain chain = new OptionChain("AAPL", spot, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, 95.0, expiry, spot, sigma),
                priced(CallPut.CALL, 100.0, expiry, spot, sigma),
                priced(CallPut.CALL, 105.0, expiry, spot, sigma),
                priced(CallPut.PUT, 100.0, expiry, spot, sigma)));

        Optional<AtmIv> atm = AtmIvCalculator.compute(chain, TODAY, analytics, R);

        assertThat(atm).isPresent();
        assertThat(atm.get().atmStrike()).isEqualTo(100.0);
        assertThat(atm.get().expiration()).isEqualTo(expiry);
        assertThat(atm.get().underlyingPrice()).isEqualTo(100.0);
        assertThat(atm.get().impliedVol()).isCloseTo(0.30, offset(1e-3));
    }

    @Test
    void picksTheStrikeNearestSpot() {
        double spot = 101.0;
        LocalDate expiry = TODAY.plusDays(30);
        // Strikes 95/100/110 — 100 is closest to 101. Only the ATM strike is priced at 0.25.
        OptionChain chain = new OptionChain("AAPL", spot, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, 95.0, expiry, spot, 0.40),
                priced(CallPut.CALL, 100.0, expiry, spot, 0.25),
                priced(CallPut.CALL, 110.0, expiry, spot, 0.40)));

        Optional<AtmIv> atm = AtmIvCalculator.compute(chain, TODAY, analytics, R);

        assertThat(atm).isPresent();
        assertThat(atm.get().atmStrike()).isEqualTo(100.0);
        assertThat(atm.get().impliedVol()).isCloseTo(0.25, offset(1e-3));
    }

    @Test
    void picksTheNearestExpiryStrictlyAfterToday() {
        double spot = 100.0;
        LocalDate near = TODAY.plusDays(20);
        LocalDate far = TODAY.plusDays(90);
        OptionChain chain = new OptionChain("AAPL", spot, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, 100.0, far, spot, 0.45),
                priced(CallPut.CALL, 100.0, near, spot, 0.22)));

        Optional<AtmIv> atm = AtmIvCalculator.compute(chain, TODAY, analytics, R);

        assertThat(atm).isPresent();
        assertThat(atm.get().expiration()).isEqualTo(near);
        assertThat(atm.get().impliedVol()).isCloseTo(0.22, offset(1e-3));
    }

    @Test
    void emptyWhenQuotesAreUnusable() {
        LocalDate expiry = TODAY.plusDays(45);
        // Zero and crossed quotes cannot be inverted -> no reading (never a zero/NaN row).
        OptionChain chain = new OptionChain("AAPL", 100.0, Instant.EPOCH, true, List.of(
                quoted(CallPut.CALL, 100.0, expiry, 0.0, 0.0),
                quoted(CallPut.PUT, 100.0, expiry, 5.0, 4.0)));

        assertThat(AtmIvCalculator.compute(chain, TODAY, analytics, R)).isEmpty();
    }

    @Test
    void emptyWhenNoFutureExpiry() {
        LocalDate past = TODAY.minusDays(1);
        OptionChain chain = new OptionChain("AAPL", 100.0, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, 100.0, past, 100.0, 0.30)));

        assertThat(AtmIvCalculator.compute(chain, TODAY, analytics, R)).isEmpty();
    }

    @Test
    void emptyForNoSpotEmptyChainOrNull() {
        LocalDate expiry = TODAY.plusDays(45);
        OptionChain noSpot = new OptionChain("AAPL", 0.0, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, 100.0, expiry, 100.0, 0.30)));

        assertThat(AtmIvCalculator.compute(noSpot, TODAY, analytics, R)).isEmpty();
        assertThat(AtmIvCalculator.compute(
                new OptionChain("AAPL", 100.0, Instant.EPOCH, true, List.of()), TODAY, analytics, R)).isEmpty();
        assertThat(AtmIvCalculator.compute(null, TODAY, analytics, R)).isEmpty();
    }
}
