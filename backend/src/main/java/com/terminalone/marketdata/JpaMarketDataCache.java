package com.terminalone.marketdata;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** Postgres-backed {@link MarketDataCache}; {@code put} upserts on the path key. */
@Component
public class JpaMarketDataCache implements MarketDataCache {

    private final MarketDataCacheRepository repository;

    public JpaMarketDataCache(MarketDataCacheRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CachedResponse> find(String cacheKey) {
        return repository.findById(cacheKey)
                .map(e -> new CachedResponse(e.getHttpStatus(), e.getBody(), e.getFetchedAt()));
    }

    @Override
    public void put(String cacheKey, int status, String body, Instant fetchedAt) {
        repository.save(new MarketDataCacheEntry(cacheKey, status, body, fetchedAt));
    }
}
