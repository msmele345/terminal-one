package com.terminalone.engine.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One immutable version of the engine config (D21/FR-28, runbook §2). A config
 * change inserts a new row and flips {@code is_active} — rows are never edited,
 * so every recommendation's {@code config_version} stays resolvable forever.
 * Exactly one row is active (partial unique index in V5, plus the transactional
 * flip in {@link DbEngineConfigProvider}).
 */
@Entity
@Table(name = "engine_config")
public class EngineConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer version;

    /** The entire strategy-matrix §9 object, serialized from {@link EngineConfig}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** Why this version exists — the runbook requires a reason on every insert. */
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EngineConfigVersion() {
        // JPA
    }

    public EngineConfigVersion(String config, boolean active, String note, Instant createdAt) {
        this.config = config;
        this.active = active;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Integer getVersion() {
        return version;
    }

    public String getConfig() {
        return config;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
