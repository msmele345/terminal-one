package com.terminalone.marketdata;

import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.OptionType;
import com.terminalone.portfolio.PositionSide;
import com.terminalone.portfolio.PositionSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The offline dev stub (Phase 3 AC 6.5): deterministic, priced in-house, and good
 * for any symbol. Verifies reproducibility, that the pieces the console/engine
 * consume are well-formed, and that held option legs are augmented into the chain.
 */
class StubMarketDataProviderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private final Clock clock = Clock.fixed(TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();

    private StubMarketDataProvider stub(PositionSource positions) {
        return new StubMarketDataProvider(analytics, positions, clock);
    }

    private StubMarketDataProvider stub() {
        return stub(mock(PositionSource.class)); // Mockito returns an empty list for listOptions()
    }

    @Test
    void quotesAreDeterministicAndWellFormed() {
        StubMarketDataProvider a = stub();
        StockQuote q1 = a.getQuote("AAPL");
        StockQuote q2 = stub().getQuote("AAPL");

        assertThat(q1.last()).isEqualTo(q2.last());               // stable across instances
        assertThat(q1.bid()).isLessThan(q1.last());
        assertThat(q1.ask()).isGreaterThan(q1.last());
        assertThat(q1.last()).isGreaterThan(0.0);
        assertThat(q1.delayed()).isTrue();
        assertThat(a.getQuote("TSLA").last()).isNotEqualTo(q1.last()); // different symbols differ
    }

    @Test
    void chainIsPricedSoItsAtmIvInvertsToASaneValue() {
        OptionChain chain = stub().getChain("AAPL");

        assertThat(chain.underlyingPrice()).isGreaterThan(0.0);
        assertThat(chain.contracts()).isNotEmpty();
        assertThat(chain.contracts()).allSatisfy(c -> {
            assertThat(c.bid()).isGreaterThan(0.0);
            assertThat(c.ask()).isGreaterThan(c.bid());
        });

        // The engine's own inversion must round-trip the stub's priced ATM contracts.
        Optional<AtmIv> atm = AtmIvCalculator.compute(chain, TODAY, analytics, 0.04);
        assertThat(atm).isPresent();
        assertThat(atm.get().impliedVol()).isBetween(0.10, 0.80);
        // deterministic
        assertThat(AtmIvCalculator.compute(stub().getChain("AAPL"), TODAY, analytics, 0.04).orElseThrow().impliedVol())
                .isEqualTo(atm.get().impliedVol());
    }

    @Test
    void dailyBarsAreOldestFirstWeekdaysEndingTodayAndAlignWithTheQuote() {
        StubMarketDataProvider s = stub();
        PriceHistory history = s.getDailyBars("AAPL");

        assertThat(history.bars()).hasSize(120);
        assertThat(history.bars().get(history.bars().size() - 1).date()).isEqualTo(TODAY);
        assertThat(history.bars()).allSatisfy(b -> {
            assertThat(b.date().getDayOfWeek().getValue()).isLessThanOrEqualTo(5); // Mon..Fri
            assertThat(b.low()).isLessThanOrEqualTo(b.high());
            assertThat(b.close()).isGreaterThan(0.0);
        });
        // strictly increasing dates
        for (int i = 1; i < history.bars().size(); i++) {
            assertThat(history.bars().get(i).date()).isAfter(history.bars().get(i - 1).date());
        }
        // last close lines up with the quote mark
        assertThat(history.bars().get(history.bars().size() - 1).close()).isEqualTo(s.getQuote("AAPL").last());
    }

    @Test
    void chainIsAugmentedWithHeldOptionLegsSoTheyCanBePriced() {
        LocalDate expiry = LocalDate.of(2026, 12, 18);
        BigDecimal strike = new BigDecimal("123.5"); // an off-grid strike the operator entered
        OptionPosition leg = new OptionPosition("TSLA", OptionType.PUT, strike, expiry,
                new BigDecimal("1"), new BigDecimal("4.00"), PositionSide.LONG, null);
        PositionSource positions = mock(PositionSource.class);
        when(positions.listOptions()).thenReturn(List.of(leg));

        OptionChain chain = stub(positions).getChain("TSLA");

        assertThat(chain.contracts()).anySatisfy(c -> {
            assertThat(c.callPut()).isEqualTo(CallPut.PUT);
            assertThat(c.strike()).isEqualTo(123.5);
            assertThat(c.expiration()).isEqualTo(expiry);
            assertThat(c.mid()).isGreaterThan(0.0);
        });
    }

    @Test
    void worksForAnyArbitrarySymbol() {
        assertThat(stub().getQuote("ZZZZ").last()).isGreaterThan(0.0);
        assertThat(stub().getChain("ZZZZ").contracts()).isNotEmpty();
        assertThat(stub().getDailyBars("ZZZZ").bars()).isNotEmpty();
    }
}
