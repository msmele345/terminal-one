package com.terminalone.marketdata;

import java.time.Instant;
import java.util.Optional;

/**
 * Store for cached raw vendor responses (FR-7). Caching at the transport seam —
 * the raw {@code (status, body)} keyed by request path — is what actually saves
 * vendor rate-limit/credits, and keeps the payload a plain string (no domain
 * serialization). The V1 impl is Postgres-backed; tests use an in-memory fake.
 */
public interface MarketDataCache {

    /** The most recent cached response for {@code cacheKey}, if any. */
    Optional<CachedResponse> find(String cacheKey);

    /** Store (or replace) the cached response for {@code cacheKey}. */
    void put(String cacheKey, int status, String body, Instant fetchedAt);

    /**
     * A cached raw vendor response.
     *
     * @param status    the HTTP status (200 live / 203 delayed)
     * @param body      the raw response body
     * @param fetchedAt when it was fetched (drives TTL freshness)
     */
    record CachedResponse(int status, String body, Instant fetchedAt) {
    }
}
