package com.terminalone.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A manually-entered option leg (FR-2). Quantity is in contracts; cost basis is
 * the per-contract premium paid (LONG) or received (SHORT). Pricing/greeks are
 * deferred to Phase 3.
 */
@Entity
@Table(name = "option_positions")
public class OptionPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String underlying;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false)
    private OptionType optionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal strike;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Per-contract premium (debit if LONG, credit if SHORT). */
    @Column(name = "cost_basis", nullable = false, precision = 19, scale = 4)
    private BigDecimal costBasis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionSide side;

    @Column(name = "opened_date")
    private LocalDate openedDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OptionPosition() {
        // JPA
    }

    public OptionPosition(String underlying, OptionType optionType, BigDecimal strike, LocalDate expiry,
                          BigDecimal quantity, BigDecimal costBasis, PositionSide side, LocalDate openedDate) {
        this.underlying = underlying;
        this.optionType = optionType;
        this.strike = strike;
        this.expiry = expiry;
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.side = side;
        this.openedDate = openedDate;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUnderlying() {
        return underlying;
    }

    public void setUnderlying(String underlying) {
        this.underlying = underlying;
    }

    public OptionType getOptionType() {
        return optionType;
    }

    public void setOptionType(OptionType optionType) {
        this.optionType = optionType;
    }

    public BigDecimal getStrike() {
        return strike;
    }

    public void setStrike(BigDecimal strike) {
        this.strike = strike;
    }

    public LocalDate getExpiry() {
        return expiry;
    }

    public void setExpiry(LocalDate expiry) {
        this.expiry = expiry;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public void setCostBasis(BigDecimal costBasis) {
        this.costBasis = costBasis;
    }

    public PositionSide getSide() {
        return side;
    }

    public void setSide(PositionSide side) {
        this.side = side;
    }

    public LocalDate getOpenedDate() {
        return openedDate;
    }

    public void setOpenedDate(LocalDate openedDate) {
        this.openedDate = openedDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
