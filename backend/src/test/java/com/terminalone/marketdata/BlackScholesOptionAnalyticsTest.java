package com.terminalone.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-Scholes analytics contract (D22, FR-8a): the engine derives IV (inverted
 * from the bid/ask mid) and greeks (delta/gamma/theta/vega) in-house, independent
 * of any vendor-supplied greeks. Reference values use the textbook ATM set
 * S=K=100, T=1yr, r=0.05, q=0, sigma=0.2.
 */
class BlackScholesOptionAnalyticsTest {

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    private static OptionInput atm(double vol) {
        return new OptionInput(100.0, 100.0, 1.0, 0.05, 0.0, vol, CallPut.CALL);
    }

    private static OptionInput atmPut(double vol) {
        return new OptionInput(100.0, 100.0, 1.0, 0.05, 0.0, vol, CallPut.PUT);
    }

    @Test
    void pricesAnAtmCall() {
        OptionValuation v = analytics.value(atm(0.2));

        assertThat(v.price()).isCloseTo(10.4506, within(1e-4));
    }

    @Test
    void computesCallGreeksAtTheAtmSet() {
        OptionValuation v = analytics.value(atm(0.2));

        // S=K=100, T=1, r=5%, q=0, sigma=20% (d1=0.35, d2=0.15).
        assertThat(v.delta()).isCloseTo(0.6368, within(1e-3));
        assertThat(v.gamma()).isCloseTo(0.0188, within(5e-3));
        assertThat(v.vega() / 100.0).isCloseTo(0.3753, within(5e-3)); // per 1% vol
        assertThat(v.theta() / 365.0).isCloseTo(-0.017573, within(2e-3)); // per calendar day
        assertThat(v.rho() / 100.0).isCloseTo(0.5323, within(5e-3)); // per 1% absolute rate
    }

    @Test
    void putCallParityHolds() {
        OptionValuation call = analytics.value(atm(0.2));
        OptionValuation put = analytics.value(new OptionInput(100.0, 100.0, 1.0, 0.05, 0.0, 0.2, CallPut.PUT));

        // C - P = S*exp(-qT) - K*exp(-rT) = 100 - 95.1229 = 4.8771
        assertThat(call.price() - put.price())
                .isCloseTo(100.0 - 100.0 * Math.exp(-0.05), within(1e-4));
    }

    @Test
    void impliedVolRoundTripsAPrice() {
        double knownPrice = analytics.value(atm(0.37)).price();

        double iv = analytics.impliedVolatility(atm(0.0), knownPrice);

        assertThat(iv).isCloseTo(0.37, within(1e-4));
    }

    @Test
    void impliedVolRejectsPricesOutsideArbitrageBounds() {
        // A call can never be worth less than intrinsic (S - K*disc = 100 - 95.1229 = 4.8771).
        double iv = analytics.impliedVolatility(atm(0.0), 1.0);

        assertThat(iv).isNaN();
    }

    @Test
    void computesPutGreeksAtTheAtmSet() {
        OptionValuation v = analytics.value(atmPut(0.2));

        // Same ATM set (d1=0.35, d2=0.15); put greeks differ from the call by parity.
        assertThat(v.price()).isCloseTo(5.5735, within(2e-3));
        assertThat(v.delta()).isCloseTo(-0.3632, within(1e-3));   // Δput = Δcall − e^{-qT}
        assertThat(v.gamma()).isCloseTo(0.0188, within(5e-3));    // equal to the call's
        assertThat(v.vega() / 100.0).isCloseTo(0.3753, within(5e-3)); // equal to the call's
        assertThat(v.theta() / 365.0).isCloseTo(-0.004542, within(2e-3)); // per calendar day
        assertThat(v.rho() / 100.0).isCloseTo(-0.4189, within(5e-3)); // per 1% absolute rate
    }

    @Test
    void impliedVolRoundTripsAPutPrice() {
        // Regression: the put arbitrage bounds were inverted, so put IV always returned NaN.
        double knownPrice = analytics.value(atmPut(0.30)).price();

        double iv = analytics.impliedVolatility(atmPut(0.0), knownPrice);

        assertThat(iv).isCloseTo(0.30, within(1e-4));
    }

    @Test
    void impliedVolFromQuoteUsesTheMid() {
        double priceAt25 = analytics.value(atmPut(0.25)).price();

        // A symmetric bid/ask whose mid is the σ=0.25 price must invert back to 0.25.
        double iv = analytics.impliedVolatilityFromQuote(atmPut(0.0), priceAt25 - 0.05, priceAt25 + 0.05);

        assertThat(iv).isCloseTo(0.25, within(1e-4));
    }

    @Test
    void impliedVolFromQuoteRejectsCrossedOrEmptyQuotes() {
        assertThat(analytics.impliedVolatilityFromQuote(atm(0.0), 2.0, 1.0)).isNaN(); // crossed
        assertThat(analytics.impliedVolatilityFromQuote(atm(0.0), 0.0, 1.0)).isNaN(); // no bid
    }

    @Test
    void handlesDividendYieldOnBothPriceAndIv() {
        // q>0 exercises the e^{-qT} discount in pricing, the bounds, and the IV solver.
        OptionInput call = new OptionInput(100.0, 100.0, 1.0, 0.05, 0.03, 0.28, CallPut.CALL);
        OptionInput put = new OptionInput(100.0, 100.0, 1.0, 0.05, 0.03, 0.28, CallPut.PUT);

        // Put-call parity with a dividend yield: C − P = S·e^{-qT} − K·e^{-rT}.
        assertThat(analytics.value(call).price() - analytics.value(put).price())
                .isCloseTo(100.0 * Math.exp(-0.03) - 100.0 * Math.exp(-0.05), within(1e-4));

        double callIv = analytics.impliedVolatility(call, analytics.value(call).price());
        double putIv = analytics.impliedVolatility(put, analytics.value(put).price());
        assertThat(callIv).isCloseTo(0.28, within(1e-4));
        assertThat(putIv).isCloseTo(0.28, within(1e-4));
    }

    /**
     * D22 cross-check: our in-house BS IV/greeks should track the vendor's own
     * (binomial/American) figures within tolerance. Skips cleanly until a golden
     * fixture is captured via {@code spikes/capture-marketdata-fixture.mjs}.
     */
    @Test
    void crossChecksInHouseIvAgainstVendorGreeks() throws Exception {
        InputStream resource = getClass().getResourceAsStream("/fixtures/marketdata/chain-AAPL.json");
        assumeTrue(resource != null,
                "golden fixture not captured — run: node spikes/capture-marketdata-fixture.mjs AAPL");

        JsonNode root;
        try (InputStream in = resource) {
            root = new ObjectMapper().readTree(in);
        }
        assumeTrue(root.has("optionSymbol"), "fixture has no chain payload");
        JsonNode iv = root.get("iv");
        assumeTrue(iv != null && !iv.isEmpty() && !iv.get(0).isNull(),
                "vendor IV absent in fixture — no oracle to cross-check");

        JsonNode strike = root.get("strike");
        JsonNode bid = root.get("bid");
        JsonNode ask = root.get("ask");
        JsonNode dte = root.get("dte");
        JsonNode side = root.get("side");
        JsonNode delta = root.get("delta");
        JsonNode symbol = root.get("optionSymbol");
        double spot = root.get("underlyingPrice").get(0).asDouble();
        double assumedRate = 0.045; // vendor's r/q are unknown → widens the tolerance below

        int checked = 0;
        for (int i = 0; i < strike.size(); i++) {
            double b = bid.get(i).asDouble();
            double a = ask.get(i).asDouble();
            double vendorIv = iv.get(i).asDouble();
            double vendorDelta = delta.get(i).asDouble();
            double t = dte.get(i).asDouble() / 365.0;
            // Only well-conditioned contracts: a real two-sided quote, near the money.
            if (b <= 0.0 || a < b || vendorIv <= 0.0 || t <= 0.0
                    || Math.abs(vendorDelta) < 0.2 || Math.abs(vendorDelta) > 0.8) {
                continue;
            }
            CallPut cp = "call".equalsIgnoreCase(side.get(i).asText()) ? CallPut.CALL : CallPut.PUT;
            OptionInput in = new OptionInput(spot, strike.get(i).asDouble(), t, assumedRate, 0.0, 0.0, cp);

            double ourIv = analytics.impliedVolatilityFromQuote(in, b, a);
            assertThat(ourIv).as("our IV vs vendor IV for %s", symbol.get(i).asText())
                    .isCloseTo(vendorIv, within(0.10)); // BS/European vs vendor binomial/American

            double ourDelta = analytics.value(
                    new OptionInput(spot, strike.get(i).asDouble(), t, assumedRate, 0.0, vendorIv, cp)).delta();
            assertThat(ourDelta).as("our delta vs vendor delta for %s", symbol.get(i).asText())
                    .isCloseTo(vendorDelta, within(0.10));
            checked++;
        }
        assumeTrue(checked > 0, "no well-conditioned near-ATM contracts in fixture to cross-check");
        assertThat(checked).isPositive();
    }

    @Test
    void degenerateInputCollapsesToIntrinsic() {
        OptionValuation v = analytics.value(new OptionInput(110.0, 100.0, 0.0, 0.05, 0.0, 0.2, CallPut.CALL));

        // T=0 → no time value; intrinsic = S - K*exp(-r*T) = 110 - 100*exp(0) = 10.
        assertThat(v.price()).isCloseTo(110.0 - 100.0, within(1e-9));
        assertThat(v.gamma()).isZero();
        assertThat(v.delta()).isCloseTo(1.0, within(1e-9)); // ITM, expired → 1.0
    }
}