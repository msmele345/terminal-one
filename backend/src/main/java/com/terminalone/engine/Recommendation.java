package com.terminalone.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "recommendations")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private StrategyType strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VolatilityRegime regime;

    @Column(nullable = false)
    private int conviction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecommendationStatus status;

    @Column(name = "config_version", nullable = false)
    private Integer configVersion;

    @Column(nullable = false)
    private LocalDate expiry;

    /** Null for covered-call overlays, which sell a call against already-held shares. */
    @Column(name = "long_option_symbol", length = 64)
    private String longOptionSymbol;

    @Column(name = "long_strike", precision = 19, scale = 4)
    private BigDecimal longStrike;

    /** Null for long single-leg structures (LONG_CALL / LONG_PUT), which have no sold leg. */
    @Column(name = "short_option_symbol", length = 64)
    private String shortOptionSymbol;

    @Column(name = "short_strike", precision = 19, scale = 4)
    private BigDecimal shortStrike;

    @Column(name = "entry_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal entryDebit;

    @Column(name = "probability_of_profit", nullable = false, precision = 19, scale = 6)
    private BigDecimal probabilityOfProfit;

    @Column(name = "max_profit", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxProfit;

    @Column(name = "max_loss", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxLoss;

    @Column(name = "risk_reward", nullable = false, precision = 19, scale = 6)
    private BigDecimal riskReward;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal score;

    /** Phase 5 AC5: contracts the engine sized the structure to (§7). */
    @Column(nullable = false)
    private int contracts;

    @Column(name = "batch_run_id")
    private Long batchRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Recommendation() {
        // JPA
    }

    Recommendation(String symbol,
                   StrategyType strategy,
                   Direction direction,
                   VolatilityRegime regime,
                   int conviction,
                   RecommendationStatus status,
                   int configVersion,
                   LocalDate expiry,
                   String longOptionSymbol,
                   Double longStrike,
                   String shortOptionSymbol,
                   Double shortStrike,
                   double entryDebit,
                   double probabilityOfProfit,
                   double maxProfit,
                   double maxLoss,
                   double riskReward,
                   double score,
                   int contracts,
                   String rationale,
                   Instant createdAt) {
        this(symbol, strategy, direction, regime, conviction, status, configVersion, expiry,
                longOptionSymbol, longStrike, shortOptionSymbol, shortStrike, entryDebit,
                probabilityOfProfit, maxProfit, maxLoss, riskReward, score, contracts,
                rationale, createdAt, null);
    }

    Recommendation(String symbol,
                   StrategyType strategy,
                   Direction direction,
                   VolatilityRegime regime,
                   int conviction,
                   RecommendationStatus status,
                   int configVersion,
                   LocalDate expiry,
                   String longOptionSymbol,
                   Double longStrike,
                   String shortOptionSymbol,
                   Double shortStrike,
                   double entryDebit,
                   double probabilityOfProfit,
                   double maxProfit,
                   double maxLoss,
                   double riskReward,
                   double score,
                   int contracts,
                   String rationale,
                   Instant createdAt,
                   Long batchRunId) {
        this.symbol = symbol;
        this.strategy = strategy;
        this.direction = direction;
        this.regime = regime;
        this.conviction = conviction;
        this.status = status;
        this.configVersion = configVersion;
        this.expiry = expiry;
        this.longOptionSymbol = longOptionSymbol;
        this.longStrike = longStrike == null ? null : decimal(longStrike);
        this.shortOptionSymbol = shortOptionSymbol;
        this.shortStrike = shortStrike == null ? null : decimal(shortStrike);
        this.entryDebit = decimal(entryDebit);
        this.probabilityOfProfit = decimal(probabilityOfProfit);
        this.maxProfit = decimal(maxProfit);
        this.maxLoss = decimal(maxLoss);
        this.riskReward = decimal(riskReward);
        this.score = decimal(score);
        this.contracts = contracts;
        this.batchRunId = batchRunId;
        this.rationale = rationale;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
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

    public VolatilityRegime getRegime() {
        return regime;
    }

    public int getConviction() {
        return conviction;
    }

    public RecommendationStatus getStatus() {
        return status;
    }

    public Integer getConfigVersion() {
        return configVersion;
    }

    public LocalDate getExpiry() {
        return expiry;
    }

    public double getEntryDebit() {
        return entryDebit.doubleValue();
    }

    public double getProbabilityOfProfit() {
        return probabilityOfProfit.doubleValue();
    }

    public double getMaxProfit() {
        return maxProfit.doubleValue();
    }

    public double getMaxLoss() {
        return maxLoss.doubleValue();
    }

    public double getRiskReward() {
        return riskReward.doubleValue();
    }

    public double getScore() {
        return score.doubleValue();
    }

    public int getContracts() {
        return contracts;
    }

    public Long getBatchRunId() {
        return batchRunId;
    }

    public String getRationale() {
        return rationale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
