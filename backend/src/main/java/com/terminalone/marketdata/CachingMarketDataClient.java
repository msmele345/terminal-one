package com.terminalone.marketdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * TTL read-through cache over a {@link MarketDataClient} (FR-7). A repeat request
 * within the TTL is served from {@link MarketDataCache} without hitting the
 * delegate; once stale, it refetches and refreshes the cache.
 */
public class CachingMarketDataClient implements MarketDataClient {

    private final MarketDataClient delegate;
    private final MarketDataCache cache;
    private final Clock clock;
    private final Duration ttl;

    public CachingMarketDataClient(MarketDataClient delegate, MarketDataCache cache,
                                   Clock clock, Duration ttl) {
        this.delegate = delegate;
        this.cache = cache;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public Response get(String path) {
        Instant now = clock.instant();
        Optional<MarketDataCache.CachedResponse> hit = cache.find(path);
        if (hit.isPresent() && now.isBefore(hit.get().fetchedAt().plus(ttl))) {
            return new Response(hit.get().status(), hit.get().body());
        }
        Response fresh = delegate.get(path);
        if (isCacheable(fresh.status())) {
            cache.put(path, fresh.status(), fresh.body(), now);
        }
        return fresh;
    }

    /** Only successful payloads are cached: 200 (live) and 203 (delayed). */
    private static boolean isCacheable(int status) {
        return status == 200 || status == 203;
    }
}
