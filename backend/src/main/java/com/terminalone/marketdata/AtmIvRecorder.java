package com.terminalone.marketdata;

import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.PositionSource;
import com.terminalone.portfolio.StockPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Daily accumulation of ATM implied volatility per portfolio underlying (Phase 3
 * AC5, D22/FR-8a). For each distinct underlying it fetches the delayed chain,
 * derives an ATM IV in-house via {@link OptionAnalytics} (never vendor-supplied),
 * and upserts one {@link IvHistory} row for the day.
 *
 * <p>Resilience (AC6): a symbol that can't be priced — no data, unusable quotes,
 * or a vendor error — is skipped (never written as a zero/NaN) and never aborts
 * the rest of the batch. The run summary + INFO log make accumulation observable.
 */
@Component
public class AtmIvRecorder {

    private static final Logger log = LoggerFactory.getLogger(AtmIvRecorder.class);

    /**
     * Annualised continuous risk-free rate for the in-house IV inversion. A fixed
     * default in Phase 3; the versioned engine config (Phase 4, D21) supplies it later.
     */
    private static final double DEFAULT_RISK_FREE_RATE = 0.04;

    private final PositionSource positions;
    private final MarketDataProvider marketData;
    private final OptionAnalytics analytics;
    private final IvHistoryRepository repository;
    private final Clock clock;

    public AtmIvRecorder(PositionSource positions, MarketDataProvider marketData,
                         OptionAnalytics analytics, IvHistoryRepository repository, Clock clock) {
        this.positions = positions;
        this.marketData = marketData;
        this.analytics = analytics;
        this.repository = repository;
        this.clock = clock;
    }

    /** Scheduled post-close (16:15 ET, weekdays); overridable via {@code app.iv-history.cron}. */
    @Scheduled(cron = "${app.iv-history.cron:0 15 16 * * MON-FRI}", zone = "America/New_York")
    void scheduledRun() {
        recordDailyAtmIv();
    }

    /**
     * Records one ATM IV reading per distinct portfolio underlying for today,
     * upserting so a same-day re-run is idempotent. Returns a summary of what was
     * recorded vs skipped.
     */
    public RecordResult recordDailyAtmIv() {
        LocalDate today = LocalDate.now(clock);
        List<String> symbols = distinctUnderlyings();
        List<String> skipped = new ArrayList<>();
        int recorded = 0;
        for (String symbol : symbols) {
            try {
                Optional<AtmIv> atm = AtmIvCalculator.compute(
                        marketData.getChain(symbol), today, analytics, DEFAULT_RISK_FREE_RATE);
                if (atm.isEmpty()) {
                    skipped.add(symbol);
                    continue;
                }
                upsert(symbol, today, atm.get());
                recorded++;
            } catch (RuntimeException e) {
                log.warn("ATM IV recording failed for {} — skipping", symbol, e);
                skipped.add(symbol);
            }
        }
        log.info("ATM IV accumulation {}: recorded {} of {} underlying(s); skipped {}",
                today, recorded, symbols.size(), skipped);
        return new RecordResult(today, recorded, skipped);
    }

    private void upsert(String symbol, LocalDate today, AtmIv atm) {
        Instant now = clock.instant();
        repository.findBySymbolAndAsOfDate(symbol, today).ifPresentOrElse(
                existing -> {
                    existing.updateReading(atm.impliedVol(), atm.underlyingPrice(),
                            atm.atmStrike(), atm.expiration(), now);
                    repository.save(existing);
                },
                () -> repository.save(new IvHistory(symbol, today, atm.impliedVol(),
                        atm.underlyingPrice(), atm.atmStrike(), atm.expiration(), now)));
    }

    /** Distinct underlyings across held stocks + option legs (upper-cased). */
    private List<String> distinctUnderlyings() {
        return Stream.concat(
                        positions.listStocks().stream().map(StockPosition::getSymbol),
                        positions.listOptions().stream().map(OptionPosition::getUnderlying))
                .filter(Objects::nonNull)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** Summary of one accumulation run — makes backfill/accumulation observable (AC5). */
    public record RecordResult(LocalDate asOfDate, int recorded, List<String> skipped) {
    }
}
