package com.terminalone.marketdata;

import com.terminalone.marketdata.AtmIvRecorder.RecordResult;
import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.OptionType;
import com.terminalone.portfolio.PositionSide;
import com.terminalone.portfolio.PositionSource;
import com.terminalone.portfolio.StockPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the ATM IV accumulation job (Phase 3 AC5) against the real
 * repository (H2). The two seams — positions + market data — are mocked; the
 * analytics are real. Covers dedup, the skip/degrade paths (AC6), and same-day
 * idempotency (upsert, not duplicate).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AtmIvRecorderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final double R = 0.04;

    @Autowired
    private IvHistoryRepository repository;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();
    private final Clock clock = Clock.fixed(TODAY.atTime(21, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private PositionSource positions;
    private MarketDataProvider marketData;
    private AtmIvRecorder recorder;

    @BeforeEach
    void setUp() {
        positions = mock(PositionSource.class);
        marketData = mock(MarketDataProvider.class);
        when(positions.listStocks()).thenReturn(List.of());
        when(positions.listOptions()).thenReturn(List.of());
        recorder = new AtmIvRecorder(positions, marketData, analytics, repository, clock);
    }

    @Test
    void recordsOneReadingPerDistinctUnderlying() {
        // AAPL appears as both a stock and an option leg -> deduped to one reading.
        when(positions.listStocks()).thenReturn(List.of(stock("AAPL")));
        when(positions.listOptions()).thenReturn(List.of(option("AAPL"), option("TSLA")));
        when(marketData.getChain("AAPL")).thenReturn(atmChain("AAPL", 100.0, 0.30));
        when(marketData.getChain("TSLA")).thenReturn(atmChain("TSLA", 250.0, 0.55));

        RecordResult result = recorder.recordDailyAtmIv();

        assertThat(result.recorded()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
        assertThat(repository.count()).isEqualTo(2);
        assertThat(reading("AAPL").getAtmIv()).isCloseTo(0.30, offset(1e-3));
        assertThat(reading("TSLA").getAtmIv()).isCloseTo(0.55, offset(1e-3));
    }

    @Test
    void skipsSymbolsThatCannotBePricedWithoutWritingAZero() {
        when(positions.listStocks()).thenReturn(List.of(stock("AAPL"), stock("ZZZZ")));
        when(marketData.getChain("AAPL")).thenReturn(atmChain("AAPL", 100.0, 0.30));
        // Crossed/empty quotes -> no invertible IV.
        when(marketData.getChain("ZZZZ")).thenReturn(new OptionChain("ZZZZ", 50.0, Instant.EPOCH, true,
                List.of(new OptionContract("ZZZZ-C", CallPut.CALL, 50.0, TODAY.plusDays(45), 0.0, 0.0, 0))));

        RecordResult result = recorder.recordDailyAtmIv();

        assertThat(result.recorded()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly("ZZZZ");
        assertThat(repository.findBySymbolAndAsOfDate("ZZZZ", TODAY)).isEmpty();
    }

    @Test
    void oneSymbolsVendorErrorDoesNotAbortTheBatch() {
        when(positions.listStocks()).thenReturn(List.of(stock("BOOM"), stock("AAPL")));
        when(marketData.getChain("BOOM")).thenThrow(new RuntimeException("vendor down"));
        when(marketData.getChain("AAPL")).thenReturn(atmChain("AAPL", 100.0, 0.30));

        RecordResult result = recorder.recordDailyAtmIv();

        assertThat(result.recorded()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly("BOOM");
        assertThat(repository.findBySymbolAndAsOfDate("AAPL", TODAY)).isPresent();
    }

    @Test
    void isIdempotentPerDayUpsertingRatherThanDuplicating() {
        when(positions.listStocks()).thenReturn(List.of(stock("AAPL")));
        when(marketData.getChain("AAPL")).thenReturn(atmChain("AAPL", 100.0, 0.30));
        recorder.recordDailyAtmIv();

        // A later run the same day (IV moved) overwrites, never inserts a second row.
        when(marketData.getChain("AAPL")).thenReturn(atmChain("AAPL", 100.0, 0.42));
        recorder.recordDailyAtmIv();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(reading("AAPL").getAtmIv()).isCloseTo(0.42, offset(1e-3));
    }

    // ---- helpers ----

    private IvHistory reading(String symbol) {
        return repository.findBySymbolAndAsOfDate(symbol, TODAY).orElseThrow();
    }

    private static StockPosition stock(String symbol) {
        return new StockPosition(symbol, new BigDecimal("100"), new BigDecimal("10"), LocalDate.of(2026, 1, 1));
    }

    private static OptionPosition option(String underlying) {
        return new OptionPosition(underlying, OptionType.CALL, new BigDecimal("100"),
                LocalDate.of(2026, 9, 18), new BigDecimal("1"), new BigDecimal("5"), PositionSide.LONG, null);
    }

    /** A chain with an ATM call+put priced at {@code sigma}, so the reading inverts back to it. */
    private OptionChain atmChain(String symbol, double spot, double sigma) {
        LocalDate expiry = TODAY.plusDays(45);
        return new OptionChain(symbol, spot, Instant.EPOCH, true, List.of(
                priced(CallPut.CALL, spot, expiry, spot, sigma),
                priced(CallPut.PUT, spot, expiry, spot, sigma)));
    }

    private OptionContract priced(CallPut cp, double strike, LocalDate expiry, double spot, double sigma) {
        double t = ChronoUnit.DAYS.between(TODAY, expiry) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, R, 0.0, sigma, cp)).price();
        return new OptionContract(cp + "-" + strike, cp, strike, expiry, price - 0.01, price + 0.01, 100);
    }
}
