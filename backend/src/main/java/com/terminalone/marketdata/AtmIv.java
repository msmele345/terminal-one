package com.terminalone.marketdata;

import java.time.LocalDate;

/**
 * One at-the-money implied-volatility reading (Phase 3 AC5), inverted in-house
 * from the chain via {@link OptionAnalytics} (D22) — never vendor-supplied.
 *
 * @param impliedVol      annualised ATM IV (mean of the ATM call/put inversions)
 * @param atmStrike       the strike nearest the underlying used for the reading
 * @param expiration      the expiry the reading was sampled from
 * @param underlyingPrice the underlying's price at sample time
 */
record AtmIv(double impliedVol, double atmStrike, LocalDate expiration, double underlyingPrice) {
}
