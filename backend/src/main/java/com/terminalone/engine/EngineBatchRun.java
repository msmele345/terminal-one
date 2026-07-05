package com.terminalone.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "engine_batch_runs")
public class EngineBatchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EngineBatchKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EngineBatchStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "recommendation_count", nullable = false)
    private int recommendationCount;

    @Column(name = "abstention_count", nullable = false)
    private int abstentionCount;

    @Column(name = "signal_snapshot_count", nullable = false)
    private int signalSnapshotCount;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    protected EngineBatchRun() {
        // JPA
    }

    private EngineBatchRun(EngineBatchKind kind, Instant startedAt) {
        this.kind = kind;
        this.status = EngineBatchStatus.RUNNING;
        this.startedAt = startedAt;
        this.recommendationCount = 0;
        this.abstentionCount = 0;
        this.signalSnapshotCount = 0;
    }

    static EngineBatchRun startEod(Instant startedAt) {
        return new EngineBatchRun(EngineBatchKind.SCHEDULED_EOD, startedAt);
    }

    void complete(EngineRunResponse response, int signalSnapshotCount, Instant completedAt) {
        this.status = EngineBatchStatus.COMPLETED;
        this.completedAt = completedAt;
        this.recommendationCount = response.recommendations().size();
        this.abstentionCount = response.abstentions().size();
        this.signalSnapshotCount = signalSnapshotCount;
        this.errorMessage = null;
    }

    void fail(String errorMessage, Instant completedAt) {
        this.status = EngineBatchStatus.FAILED;
        this.completedAt = completedAt;
        this.errorMessage = errorMessage;
    }

    public Long getId() {
        return id;
    }

    public EngineBatchKind getKind() {
        return kind;
    }

    public EngineBatchStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getRecommendationCount() {
        return recommendationCount;
    }

    public int getAbstentionCount() {
        return abstentionCount;
    }

    public int getSignalSnapshotCount() {
        return signalSnapshotCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
