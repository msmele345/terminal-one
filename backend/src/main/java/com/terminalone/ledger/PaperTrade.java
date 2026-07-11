package com.terminalone.ledger;

import com.terminalone.engine.Recommendation;
import com.terminalone.engine.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "paper_trades")
public class PaperTrade {

    public enum Status {
        OPEN,
        SETTLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false, unique = true)
    private Recommendation recommendation;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private StrategyType strategy;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(nullable = false)
    private int contracts;

    @Column(name = "entry_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal entryDebit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "last_marked_at")
    private Instant lastMarkedAt;

    @Column(name = "mark_debit", precision = 19, scale = 4)
    private BigDecimal markDebit;

    @Column(name = "unrealized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal unrealizedPnl;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected PaperTrade() {
        // JPA
    }

    private PaperTrade(Recommendation recommendation, Instant openedAt) {
        this.recommendation = recommendation;
        this.symbol = recommendation.getSymbol();
        this.strategy = recommendation.getStrategy();
        this.configVersion = recommendation.getConfigVersion();
        this.expiry = recommendation.getExpiry();
        this.contracts = recommendation.getContracts();
        this.entryDebit = decimal(recommendation.getEntryDebit());
        this.status = Status.OPEN;
        this.openedAt = openedAt;
        this.unrealizedPnl = decimal(0.0);
        this.realizedPnl = decimal(0.0);
    }

    public static PaperTrade fromRecommendation(Recommendation recommendation, Instant openedAt) {
        return new PaperTrade(recommendation, openedAt);
    }

    void markOpen(double markDebit, double unrealizedPnl, Instant markedAt) {
        this.status = Status.OPEN;
        this.markDebit = decimal(markDebit);
        this.unrealizedPnl = decimal(unrealizedPnl);
        this.realizedPnl = decimal(0.0);
        this.lastMarkedAt = markedAt;
    }

    void settle(double markDebit, double realizedPnl, Instant settledAt) {
        this.status = Status.SETTLED;
        this.markDebit = decimal(markDebit);
        this.unrealizedPnl = decimal(0.0);
        this.realizedPnl = decimal(realizedPnl);
        this.lastMarkedAt = settledAt;
        this.closedAt = settledAt;
    }

    public Long getId() {
        return id;
    }

    public Recommendation getRecommendation() {
        return recommendation;
    }

    public String getSymbol() {
        return symbol;
    }

    public StrategyType getStrategy() {
        return strategy;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public LocalDate getExpiry() {
        return expiry;
    }

    public int getContracts() {
        return contracts;
    }

    public BigDecimal getEntryDebit() {
        return entryDebit;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getLastMarkedAt() {
        return lastMarkedAt;
    }

    public BigDecimal getMarkDebit() {
        return markDebit;
    }

    public BigDecimal getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
