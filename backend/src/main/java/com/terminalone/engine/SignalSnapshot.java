package com.terminalone.engine;

import com.terminalone.marketdata.OptionChain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "signal_snapshots")
public class SignalSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_run_id", nullable = false)
    private Long batchRunId;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "chain_as_of", nullable = false)
    private Instant chainAsOf;

    @Column(name = "underlying_price", nullable = false)
    private double underlyingPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Column(nullable = false)
    private int conviction;

    @Column(name = "direction_score", nullable = false)
    private double directionScore;

    @Column(name = "trend_vote", nullable = false)
    private double trendVote;

    @Column(name = "macd_vote", nullable = false)
    private double macdVote;

    @Column(name = "rsi_vote", nullable = false)
    private double rsiVote;

    @Column(name = "ema_fast", nullable = false)
    private double emaFast;

    @Column(name = "ema_slow", nullable = false)
    private double emaSlow;

    @Column(name = "macd_histogram", nullable = false)
    private double macdHistogram;

    @Column(nullable = false)
    private double rsi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VolatilityRegime regime;

    @Column(name = "regime_reason", nullable = false, length = 512)
    private String regimeReason;

    @Column(name = "current_iv")
    private Double currentIv;

    protected SignalSnapshot() {
        // JPA
    }

    private SignalSnapshot(Long batchRunId,
            String symbol,
            int configVersion,
            LocalDate asOfDate,
            Instant capturedAt,
            Instant chainAsOf,
            double underlyingPrice,
            DirectionSignal signal,
            VolatilityRegimeResult regime) {
        this.batchRunId = batchRunId;
        this.symbol = symbol;
        this.configVersion = configVersion;
        this.asOfDate = asOfDate;
        this.capturedAt = capturedAt;
        this.chainAsOf = chainAsOf;
        this.underlyingPrice = underlyingPrice;
        this.direction = signal.direction();
        this.conviction = signal.conviction();
        this.directionScore = signal.directionScore();
        this.trendVote = signal.trendVote();
        this.macdVote = signal.macdVote();
        this.rsiVote = signal.rsiVote();
        this.emaFast = signal.emaFast();
        this.emaSlow = signal.emaSlow();
        this.macdHistogram = signal.macdHistogram();
        this.rsi = signal.rsi();
        this.regime = regime.regime();
        this.regimeReason = regime.reason();
        this.currentIv = regime.currentIv();
    }

    static SignalSnapshot fromBatchRun(Long batchRunId,
            int configVersion,
            String symbol,
            DirectionSignal signal,
            VolatilityRegimeResult regime,
            OptionChain chain,
            LocalDate asOfDate,
            Instant capturedAt) {
        return new SignalSnapshot(batchRunId, symbol, configVersion, asOfDate, capturedAt,
                chain.asOf(), chain.underlyingPrice(), signal, regime);
    }

    public Long getId() {
        return id;
    }

    public Long getBatchRunId() {
        return batchRunId;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getChainAsOf() {
        return chainAsOf;
    }

    public double getUnderlyingPrice() {
        return underlyingPrice;
    }

    public Direction getDirection() {
        return direction;
    }

    public int getConviction() {
        return conviction;
    }

    public double getDirectionScore() {
        return directionScore;
    }

    public VolatilityRegime getRegime() {
        return regime;
    }

    public String getRegimeReason() {
        return regimeReason;
    }

    public Double getCurrentIv() {
        return currentIv;
    }
}
