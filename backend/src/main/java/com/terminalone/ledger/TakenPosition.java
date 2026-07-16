package com.terminalone.ledger;

import com.terminalone.engine.Direction;
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

/**
 * A recommendation the owner actually took (Phase 8 AC3, FR-18, D14): the
 * real-positions tracker, distinct from the paper-trade ledger. Carries the
 * manually entered fill price (OQ-8) so realized performance reflects the true
 * entry, not the engine's modeled mid.
 */
@Entity
@Table(name = "taken_positions")
public class TakenPosition {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(nullable = false)
    private int contracts;

    /** Manually entered net fill price; sign follows entryDebit (>0 debit, <0 credit). */
    @Column(name = "fill_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal fillPrice;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    protected TakenPosition() {
        // JPA
    }

    private TakenPosition(Recommendation recommendation, double fillPrice, Instant takenAt) {
        this.recommendation = recommendation;
        this.symbol = recommendation.getSymbol();
        this.strategy = recommendation.getStrategy();
        this.direction = recommendation.getDirection();
        this.configVersion = recommendation.getConfigVersion();
        this.expiry = recommendation.getExpiry();
        this.contracts = recommendation.getContracts();
        this.fillPrice = decimal(fillPrice);
        this.takenAt = takenAt;
    }

    public static TakenPosition fromRecommendation(Recommendation recommendation, double fillPrice, Instant takenAt) {
        return new TakenPosition(recommendation, fillPrice, takenAt);
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

    public Direction getDirection() {
        return direction;
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

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
