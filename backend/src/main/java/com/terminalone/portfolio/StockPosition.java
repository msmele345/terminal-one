package com.terminalone.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A manually-entered stock position (FR-1). Pricing/P&L are deferred to Phase 3;
 * this carries only the static fields entered by the operator.
 */
@Entity
@Table(name = "positions")
public class StockPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Per-share cost basis. */
    @Column(name = "cost_basis", nullable = false, precision = 19, scale = 4)
    private BigDecimal costBasis;

    @Column(name = "opened_date", nullable = false)
    private LocalDate openedDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected StockPosition() {
        // JPA
    }

    public StockPosition(String symbol, BigDecimal quantity, BigDecimal costBasis, LocalDate openedDate) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.openedDate = openedDate;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
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
