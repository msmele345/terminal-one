package com.terminalone.marketdata;

/**
 * The result of pricing an option: price + IV-independent greeks. All values
 * are per-share (multiply by 100 and the contract multiplier for per-contract).
 *
 * @param price option premium (per-share)
 * @param delta  dPrice/dSpot
 * @param gamma  d2Price/dSpot2
 * @param theta  dPrice/dTime (per year; divide by 365 for per-day)
 * @param vega   dPrice/dVol (per 1.0 absolute vol; divide by 100 for per 1%)
 * @param rho    dPrice/dRate
 */
public record OptionValuation(
        double price,
        double delta,
        double gamma,
        double theta,
        double vega,
        double rho) {
}