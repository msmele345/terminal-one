package com.terminalone.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One accumulated ATM implied-volatility reading (Phase 3 AC5, D22/FR-8a). The
 * engine's IV-rank input (Phase 4/5) needs ~1yr of daily ATM IV that nobody has
 * at launch, so a daily job appends one row per underlying per day. Exactly one
 * row per {@code (symbol, asOfDate)} — the job upserts, so a re-run is idempotent.
 */
@Entity
@Table(name = "iv_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_iv_history_symbol_date",
                columnNames = {"symbol", "as_of_date"}))
public class IvHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** Annualised ATM IV, computed in-house via Black-Scholes (never vendor-supplied). */
    @Column(name = "atm_iv", nullable = false)
    private double atmIv;

    @Column(name = "underlying_price", nullable = false)
    private double underlyingPrice;

    @Column(name = "atm_strike", nullable = false)
    private double atmStrike;

    /** The expiry the ATM IV was sampled from (kept for interpretability of the series). */
    @Column(nullable = false)
    private LocalDate expiration;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected IvHistory() {
        // JPA
    }

    public IvHistory(String symbol, LocalDate asOfDate, double atmIv, double underlyingPrice,
                     double atmStrike, LocalDate expiration, Instant recordedAt) {
        this.symbol = symbol;
        this.asOfDate = asOfDate;
        this.atmIv = atmIv;
        this.underlyingPrice = underlyingPrice;
        this.atmStrike = atmStrike;
        this.expiration = expiration;
        this.recordedAt = recordedAt;
    }

    /** Overwrite the day's reading — an idempotent re-run of the accumulation job. */
    public void updateReading(double atmIv, double underlyingPrice, double atmStrike,
                              LocalDate expiration, Instant recordedAt) {
        this.atmIv = atmIv;
        this.underlyingPrice = underlyingPrice;
        this.atmStrike = atmStrike;
        this.expiration = expiration;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public double getAtmIv() {
        return atmIv;
    }

    public double getUnderlyingPrice() {
        return underlyingPrice;
    }

    public double getAtmStrike() {
        return atmStrike;
    }

    public LocalDate getExpiration() {
        return expiration;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
