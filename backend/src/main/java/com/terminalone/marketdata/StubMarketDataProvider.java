package com.terminalone.marketdata;

import com.terminalone.portfolio.OptionPosition;
import com.terminalone.portfolio.OptionType;
import com.terminalone.portfolio.PositionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic, offline {@link MarketDataProvider} for local dev (Phase 3 AC 6.5).
 * Active only under the {@code stub} profile ({@code SPRING_PROFILES_ACTIVE=stub});
 * the real MarketData.app adapter is the default (see {@link MarketDataConfig}).
 *
 * <p>Serves reproducible quotes, option chains, and daily bars for <em>any</em>
 * symbol so the whole stack runs with no vendor token — the console prices
 * positions, renders charts + totals, and the ATM-IV job records real readings.
 * Prices derive from a stable hash of the symbol; option quotes are priced
 * in-house via {@link OptionAnalytics} at a symbol-stable IV, so the engine's own
 * IV inversion round-trips sensibly. The chain is augmented with any held option
 * legs for the underlying (priced the same way) so portfolio option P&amp;L is
 * populated too. <strong>Not for production.</strong>
 */
@Component
@Profile("stub")
public class StubMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(StubMarketDataProvider.class);

    private static final double RISK_FREE_RATE = 0.04;
    private static final int HISTORY_BARS = 120;
    private static final int GRID_STRIKES_EACH_SIDE = 10;
    private static final int GRID_EXPIRIES = 3;
    private static final double DAILY_VOL = 0.015;

    private final OptionAnalytics analytics;
    private final PositionSource positions;
    private final Clock clock;

    public StubMarketDataProvider(OptionAnalytics analytics, PositionSource positions, Clock clock) {
        this.analytics = analytics;
        this.positions = positions;
        this.clock = clock;
        log.info("StubMarketDataProvider ACTIVE (profile 'stub') — serving deterministic offline "
                + "market data; no MARKETDATA_TOKEN used. Do not enable in production.");
    }

    @Override
    public StockQuote getQuote(String symbol) {
        double last = basePrice(symbol);
        double spread = Math.max(0.01, last * 0.0005);
        return new StockQuote(symbol, round2(last - spread), round2(last + spread), round2(last),
                clock.instant(), true);
    }

    @Override
    public OptionChain getChain(String symbol) {
        LocalDate today = LocalDate.now(clock);
        double spot = basePrice(symbol);
        double iv = baseIv(symbol);
        double inc = strikeIncrement(spot);
        double atm = Math.max(inc, Math.round(spot / inc) * inc);

        // Keyed so the ATM grid and held-leg augmentation never emit a duplicate contract.
        Map<String, OptionContract> byKey = new LinkedHashMap<>();
        for (LocalDate expiry : nextExpiries(today, GRID_EXPIRIES)) {
            for (int k = -GRID_STRIKES_EACH_SIDE; k <= GRID_STRIKES_EACH_SIDE; k++) {
                double strike = atm + k * inc;
                if (strike <= 0) {
                    continue;
                }
                putContract(byKey, symbol, CallPut.CALL, strike, expiry, spot, iv, today);
                putContract(byKey, symbol, CallPut.PUT, strike, expiry, spot, iv, today);
            }
        }
        // Augment with the exact legs the operator holds so their option P&L is populated offline.
        for (OptionPosition leg : positions.listOptions()) {
            if (!symbol.equalsIgnoreCase(leg.getUnderlying())) {
                continue;
            }
            CallPut cp = leg.getOptionType() == OptionType.CALL ? CallPut.CALL : CallPut.PUT;
            putContract(byKey, symbol, cp, leg.getStrike().doubleValue(), leg.getExpiry(), spot, iv, today);
        }

        return new OptionChain(symbol, round2(spot), clock.instant(), true, new ArrayList<>(byKey.values()));
    }

    @Override
    public PriceHistory getDailyBars(String symbol) {
        LocalDate today = LocalDate.now(clock);
        double target = basePrice(symbol);
        Random rnd = new Random(seed(symbol));

        // Normalised geometric random walk, then rescaled so the last close == the quote price
        // (chart's last point lines up with the position's mark).
        double[] close = new double[HISTORY_BARS];
        close[0] = 1.0;
        for (int i = 1; i < HISTORY_BARS; i++) {
            close[i] = close[i - 1] * Math.exp(rnd.nextGaussian() * DAILY_VOL);
        }
        double scale = target / close[HISTORY_BARS - 1];

        List<LocalDate> dates = weekdaysEndingAt(today, HISTORY_BARS);
        List<PriceBar> bars = new ArrayList<>(HISTORY_BARS);
        for (int i = 0; i < HISTORY_BARS; i++) {
            double c = round2(close[i] * scale);
            double o = round2((i == 0 ? close[i] : close[i - 1]) * scale);
            double high = round2(Math.max(o, c) * (1.0 + Math.abs(rnd.nextGaussian()) * 0.004));
            double low = round2(Math.min(o, c) * (1.0 - Math.abs(rnd.nextGaussian()) * 0.004));
            long volume = 500_000L + (long) (Math.abs(rnd.nextGaussian()) * 250_000);
            bars.add(new PriceBar(dates.get(i), o, high, low, c, volume));
        }
        Instant asOf = today.atTime(20, 0).toInstant(ZoneOffset.UTC);
        return new PriceHistory(symbol, asOf, true, bars);
    }

    // ---- deterministic pricing helpers ----

    private void putContract(Map<String, OptionContract> byKey, String symbol, CallPut cp,
                             double strike, LocalDate expiry, double spot, double iv, LocalDate today) {
        double t = Math.max(1, ChronoUnit.DAYS.between(today, expiry)) / 365.0;
        double price = analytics.value(new OptionInput(spot, strike, t, RISK_FREE_RATE, 0.0, iv, cp)).price();
        double spread = Math.max(0.05, price * 0.02);
        double bid = round2(Math.max(0.01, price - spread / 2));
        double ask = round2(Math.max(bid + 0.02, price + spread / 2));
        int openInterest = Math.max(1, (int) (5000 - Math.abs(strike - spot) * 20));
        byKey.putIfAbsent(cp + "|" + strike + "|" + expiry,
                new OptionContract(occSymbol(symbol, cp, strike, expiry), cp, strike, expiry, bid, ask, openInterest));
    }

    /** Stable base spot for a symbol, in roughly [20, 480). */
    private static double basePrice(String symbol) {
        return 20.0 + (hash(symbol) % 4600) / 10.0;
    }

    /** Stable annualised IV for a symbol, in roughly [0.18, 0.62). */
    private static double baseIv(String symbol) {
        return 0.18 + (hash(symbol + "|iv") % 44) / 100.0;
    }

    private static double strikeIncrement(double spot) {
        if (spot < 25.0) return 1.0;
        if (spot < 100.0) return 2.5;
        if (spot < 250.0) return 5.0;
        return 10.0;
    }

    /** The next {@code count} monthly (third-Friday) expiries strictly after {@code today}. */
    private static List<LocalDate> nextExpiries(LocalDate today, int count) {
        List<LocalDate> out = new ArrayList<>(count);
        YearMonth ym = YearMonth.from(today);
        while (out.size() < count) {
            LocalDate thirdFriday = thirdFriday(ym);
            if (thirdFriday.isAfter(today)) {
                out.add(thirdFriday);
            }
            ym = ym.plusMonths(1);
        }
        return out;
    }

    private static LocalDate thirdFriday(YearMonth ym) {
        LocalDate first = ym.atDay(1);
        int toFriday = (DayOfWeek.FRIDAY.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        return first.plusDays(toFriday).plusWeeks(2);
    }

    /** {@code n} weekday dates ending at {@code end} (inclusive), oldest first. */
    private static List<LocalDate> weekdaysEndingAt(LocalDate end, int n) {
        List<LocalDate> out = new ArrayList<>(n);
        for (LocalDate d = end; out.size() < n; d = d.minusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        Collections.reverse(out);
        return out;
    }

    private static String occSymbol(String symbol, CallPut cp, double strike, LocalDate expiry) {
        String date = String.format("%02d%02d%02d",
                expiry.getYear() % 100, expiry.getMonthValue(), expiry.getDayOfMonth());
        long strikeMillis = Math.round(strike * 1000);
        return String.format("%s%s%s%08d",
                symbol.toUpperCase(Locale.ROOT), date, cp == CallPut.CALL ? "C" : "P", strikeMillis);
    }

    private static int hash(String s) {
        return s.hashCode() & 0x7fffffff;
    }

    private static long seed(String s) {
        return hash(s) * 2654435761L;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
