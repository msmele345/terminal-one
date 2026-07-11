package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.BlackScholesOptionAnalytics;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.IvHistory;
import com.terminalone.marketdata.IvHistoryRepository;
import com.terminalone.marketdata.OptionAnalytics;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.OptionInput;
import com.terminalone.marketdata.PriceBar;
import com.terminalone.marketdata.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5 AC2: volatility regime uses true IV rank once enough ATM-IV history
 * exists; before then it bootstraps from the documented 20-day Bollinger-width
 * percentile over trailing daily bars.
 */
@ExtendWith(MockitoExtension.class)
class VolatilityRegimeCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant AS_OF = TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDate EXPIRY = TODAY.plusDays(45);
    private static final double SPOT = 100.0;
    private static final double RISK_FREE_RATE = 0.04;

    private final OptionAnalytics analytics = new BlackScholesOptionAnalytics();
    private final EngineConfig config = EngineConfigDefaults.load();

    @Mock
    private IvHistoryRepository ivHistoryRepository;

    @ParameterizedTest(name = "current IV {0} -> {1}")
    @CsvSource({
            "0.24, LOW",
            "0.30, NORMAL",
            "0.36, HIGH"
    })
    void matureIvHistoryMapsCurrentIvRankToTheConfiguredRegime(double currentIv,
            VolatilityRegime expectedRegime) {
        VolatilityRegimeCalculator calculator = calculator();
        when(ivHistoryRepository.findBySymbolOrderByAsOfDateAsc("AAPL"))
                .thenReturn(ivHistoryRange(0.20, 0.40, config.regime().minIvHistoryDays()));

        VolatilityRegimeResult result = calculator.calculate(
                "AAPL", chainPricedAt(currentIv), quietHistory(90), config.regime());

        assertThat(result.regime()).isEqualTo(expectedRegime);
        assertThat(result.currentIv()).isCloseTo(currentIv, within(1e-3));
        assertThat(result.reason()).contains("IV_RANK");
        assertThat(result.reason()).doesNotContain("BOLLINGER_WIDTH_PCTL");
    }

    @Test
    void immatureIvHistoryFallsBackToBollingerWidthPercentile() {
        VolatilityRegimeCalculator calculator = calculator();
        when(ivHistoryRepository.findBySymbolOrderByAsOfDateAsc("AAPL"))
                .thenReturn(ivHistoryRange(0.20, 0.24, config.regime().minIvHistoryDays() - 1));

        VolatilityRegimeResult result = calculator.calculate(
                "AAPL", chainPricedAt(0.26), wideningHistory(), config.regime());

        assertThat(result.regime()).isEqualTo(VolatilityRegime.HIGH);
        assertThat(result.currentIv()).isCloseTo(0.26, within(1e-3));
        assertThat(result.reason()).contains("BOLLINGER_WIDTH_PCTL");
        assertThat(result.reason()).contains("IV history 59/60");
    }

    @Test
    void unavailableIvRankAndBollingerFallbackDefaultsToNormalWithDiagnostic() {
        VolatilityRegimeCalculator calculator = calculator();
        when(ivHistoryRepository.findBySymbolOrderByAsOfDateAsc("AAPL")).thenReturn(List.of());

        VolatilityRegimeResult result = calculator.calculate(
                "AAPL", chainPricedAt(0.26), quietHistory(10), config.regime());

        assertThat(result.regime()).isEqualTo(VolatilityRegime.NORMAL);
        assertThat(result.currentIv()).isCloseTo(0.26, within(1e-3));
        assertThat(result.reason()).contains("VOL_REGIME_UNAVAILABLE");
        assertThat(result.reason()).contains("defaulting NORMAL");
        assertThat(result.reason()).contains("Bollinger fallback needs at least 21 usable bars");
    }

    private VolatilityRegimeCalculator calculator() {
        return new VolatilityRegimeCalculator(
                ivHistoryRepository, analytics, Clock.fixed(AS_OF, ZoneOffset.UTC));
    }

    private List<IvHistory> ivHistoryRange(double min, double max, int count) {
        List<IvHistory> rows = new ArrayList<>();
        double step = count == 1 ? 0.0 : (max - min) / (count - 1);
        for (int i = 0; i < count; i++) {
            LocalDate date = TODAY.minusDays(count - i);
            rows.add(new IvHistory("AAPL", date, min + (step * i), SPOT, SPOT, EXPIRY, AS_OF));
        }
        return rows;
    }

    private OptionChain chainPricedAt(double sigma) {
        return new OptionChain("AAPL", SPOT, AS_OF, true, List.of(
                priced(CallPut.CALL, sigma),
                priced(CallPut.PUT, sigma)));
    }

    private OptionContract priced(CallPut callPut, double sigma) {
        double t = ChronoUnit.DAYS.between(TODAY, EXPIRY) / 365.0;
        double price = analytics.value(new OptionInput(SPOT, SPOT, t, RISK_FREE_RATE, 0.0, sigma, callPut)).price();
        return new OptionContract(callPut + "-100", callPut, SPOT, EXPIRY, price - 0.01, price + 0.01, 1_000);
    }

    private PriceHistory quietHistory(int bars) {
        List<PriceBar> rows = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            rows.add(bar(i, 100.0 + Math.sin(i) * 0.20));
        }
        return new PriceHistory("AAPL", AS_OF, true, rows);
    }

    private PriceHistory wideningHistory() {
        List<PriceBar> rows = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            double close = i < 70
                    ? 100.0 + Math.sin(i) * 0.30
                    : 100.0 + (i % 2 == 0 ? 15.0 : -15.0);
            rows.add(bar(i, close));
        }
        return new PriceHistory("AAPL", AS_OF, true, rows);
    }

    private PriceBar bar(int index, double close) {
        return new PriceBar(TODAY.minusDays(90 - index), close, close, close, close, 1_000_000);
    }
}
