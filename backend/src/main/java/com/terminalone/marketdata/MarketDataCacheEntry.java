package com.terminalone.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A cached raw vendor response (FR-7), keyed by request path. Persists exactly
 * what the vendor returned; TTL freshness lives in {@link CachingMarketDataClient}.
 */
@Entity
@Table(name = "market_data_cache")
public class MarketDataCacheEntry {

    @Id
    @Column(name = "cache_key", length = 512)
    private String cacheKey;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected MarketDataCacheEntry() {
        // JPA
    }

    public MarketDataCacheEntry(String cacheKey, int httpStatus, String body, Instant fetchedAt) {
        this.cacheKey = cacheKey;
        this.httpStatus = httpStatus;
        this.body = body;
        this.fetchedAt = fetchedAt;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getBody() {
        return body;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
