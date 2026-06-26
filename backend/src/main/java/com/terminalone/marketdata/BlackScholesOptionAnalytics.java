package com.terminalone.marketdata;

import org.springframework.stereotype.Component;

/**
 * Black-Scholes implementation of {@link OptionAnalytics} (PRD D22). European
 * exercise, continuous-yield generalisation (Black-Scholes-Merton). Pure and
 * side-effect-free; safe to call concurrently and to share as a singleton.
 *
 * <p>Implied vol is solved by bisection on the monotone BS price function —
 * robust, and the bisection loop is allocation-free (it re-prices via a
 * price-only helper rather than rebuilding inputs). It converges well within
 * the tolerance the engine needs (IV is a regime input, not a per-bp concern).
 * Returns {@code NaN} if the market price is outside the achievable BS range
 * (arbitrage bounds).
 */
@Component
public class BlackScholesOptionAnalytics implements OptionAnalytics {

    private static final double IV_LOWER = 0.0001;
    private static final double IV_UPPER = 5.0;
    private static final double IV_TOLERANCE = 1e-6;
    private static final int IV_MAX_ITER = 200;
    private static final double BOUND_EPS = 1e-9;

    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    @Override
    public OptionValuation value(OptionInput in) {
        if (in.isDegenerate()) {
            double intrinsic = intrinsicValue(in);
            double sign = in.callPut() == CallPut.CALL ? 1.0 : -1.0;
            return new OptionValuation(intrinsic, sign * (intrinsic > 0.0 ? 1.0 : 0.0),
                    0.0, 0.0, 0.0, 0.0);
        }
        double s = in.underlying();
        double k = in.strike();
        double t = in.timeToExpiry();
        double r = in.riskFreeRate();
        double q = in.dividendYield();
        double sigma = in.volatility();
        boolean isCall = in.callPut() == CallPut.CALL;

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        double discount = Math.exp(-r * t);
        double divDisc = Math.exp(-q * t);
        double pdfD1 = normPdf(d1);

        // Gamma and vega are identical for calls and puts.
        double gamma = (divDisc * pdfD1) / (s * sigma * sqrtT);
        double vega = s * divDisc * sqrtT * pdfD1;
        double thetaDecay = -(s * divDisc * pdfD1 * sigma) / (2.0 * sqrtT);

        if (isCall) {
            double nd1 = normCdf(d1);
            double nd2 = normCdf(d2);
            double price = s * divDisc * nd1 - k * discount * nd2;
            double delta = divDisc * nd1;
            double rho = k * t * discount * nd2;
            double theta = thetaDecay - r * k * discount * nd2 + q * s * divDisc * nd1;
            return new OptionValuation(price, delta, gamma, theta, vega, rho);
        }

        double nd1n = normCdf(-d1);
        double nd2n = normCdf(-d2);
        double price = k * discount * nd2n - s * divDisc * nd1n;
        double delta = -divDisc * nd1n;
        double rho = -k * t * discount * nd2n;
        double theta = thetaDecay + r * k * discount * nd2n - q * s * divDisc * nd1n;
        return new OptionValuation(price, delta, gamma, theta, vega, rho);
    }

    @Override
    public double impliedVolatility(OptionInput in, double marketPrice) {
        if (in.isDegenerate() || marketPrice < 0.0) {
            return Double.NaN;
        }
        // Arbitrage bounds (the volatility field of `in` is ignored here).
        if (marketPrice < bsBound(in, false) - BOUND_EPS
                || marketPrice > bsBound(in, true) + BOUND_EPS) {
            return Double.NaN;
        }
        double lo = IV_LOWER;
        double hi = IV_UPPER;
        if (marketPrice <= bsPrice(in, lo)) return lo;
        if (marketPrice >= bsPrice(in, hi)) return hi;
        for (int i = 0; i < IV_MAX_ITER && (hi - lo) > IV_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            if (bsPrice(in, mid) < marketPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Black-Scholes price for {@code in} at a supplied {@code sigma} (no allocation). */
    private static double bsPrice(OptionInput in, double sigma) {
        double s = in.underlying();
        double k = in.strike();
        double t = in.timeToExpiry();
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (in.riskFreeRate() - in.dividendYield() + 0.5 * sigma * sigma) * t)
                / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double sDisc = s * Math.exp(-in.dividendYield() * t);
        double kDisc = k * Math.exp(-in.riskFreeRate() * t);
        if (in.callPut() == CallPut.CALL) {
            return sDisc * normCdf(d1) - kDisc * normCdf(d2);
        }
        return kDisc * normCdf(-d2) - sDisc * normCdf(-d1);
    }

    /**
     * No-arbitrage price bounds (the BS price as {@code sigma} → 0 and → ∞).
     * For a call: [max(0, S·e^-qT − K·e^-rT), S·e^-qT]. For a put:
     * [max(0, K·e^-rT − S·e^-qT), K·e^-rT].
     */
    private static double bsBound(OptionInput in, boolean upper) {
        double sDisc = in.underlying() * Math.exp(-in.dividendYield() * in.timeToExpiry());
        double kDisc = in.strike() * Math.exp(-in.riskFreeRate() * in.timeToExpiry());
        if (in.callPut() == CallPut.CALL) {
            return upper ? sDisc : Math.max(0.0, sDisc - kDisc);
        }
        return upper ? kDisc : Math.max(0.0, kDisc - sDisc);
    }

    private static double intrinsicValue(OptionInput in) {
        double sDisc = in.underlying() * Math.exp(-in.dividendYield() * in.timeToExpiry());
        double kDisc = in.strike() * Math.exp(-in.riskFreeRate() * in.timeToExpiry());
        return in.callPut() == CallPut.CALL
                ? Math.max(0.0, sDisc - kDisc)
                : Math.max(0.0, kDisc - sDisc);
    }

    /** Standard-normal probability density φ(x). */
    private static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / SQRT_2PI;
    }

    /** Standard-normal cumulative distribution Φ(x), via Abramowitz & Stegun 26.2.17. */
    private static double normCdf(double x) {
        if (x < -8.0) return 0.0;
        if (x > 8.0) return 1.0;
        double ax = Math.abs(x);
        double k = 1.0 / (1.0 + 0.2316419 * ax);
        double poly = ((((1.330274429 * k - 1.821255978) * k + 1.781477937) * k - 0.356563782) * k + 0.319381530) * k;
        double pAx = 1.0 - normPdf(ax) * poly; // P(X <= ax)
        return x >= 0.0 ? pAx : 1.0 - pAx;      // symmetry for negatives
    }
}
