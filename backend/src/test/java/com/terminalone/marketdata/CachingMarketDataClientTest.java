package com.terminalone.marketdata;

import com.terminalone.marketdata.MarketDataClient.Response;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL read-through cache behaviour (Phase 3 AC #1: "cached with TTL and served
 * from cache on repeat calls"). Driven with a counting fake delegate, an
 * in-memory cache, and a mutable clock — no network, no DB.
 */
class CachingMarketDataClientTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-25T20:00:00Z"));
    private final InMemoryCache cache = new InMemoryCache();

    private CachingMarketDataClient caching(MarketDataClient delegate) {
        return new CachingMarketDataClient(delegate, cache, clock, TTL);
    }

    @Test
    void fetchesFromDelegateOnCacheMiss() {
        CountingClient delegate = new CountingClient(new Response(203, "CHAIN"));

        Response res = caching(delegate).get("options/chain/AAPL/");

        assertThat(res.status()).isEqualTo(203);
        assertThat(res.body()).isEqualTo("CHAIN");
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void servesFromCacheOnRepeatCallWithinTtl() {
        CountingClient delegate = new CountingClient(new Response(200, "CHAIN"));
        CachingMarketDataClient client = caching(delegate);

        client.get("options/chain/AAPL/");
        clock.advance(Duration.ofMinutes(14)); // still inside the 15-min TTL
        Response res = client.get("options/chain/AAPL/");

        assertThat(res.body()).isEqualTo("CHAIN");
        assertThat(delegate.calls).isEqualTo(1); // second call served from cache
    }

    @Test
    void refetchesAndRefreshesAfterTtlExpiry() {
        CountingClient delegate = new CountingClient(new Response(200, "OLD"), new Response(200, "NEW"));
        CachingMarketDataClient client = caching(delegate);

        client.get("options/chain/AAPL/");
        clock.advance(Duration.ofMinutes(16)); // past the 15-min TTL
        Response res = client.get("options/chain/AAPL/");

        assertThat(res.body()).isEqualTo("NEW");
        assertThat(delegate.calls).isEqualTo(2);
        // the refreshed entry is now cached at the new time → next call serves "NEW"
        assertThat(client.get("options/chain/AAPL/").body()).isEqualTo("NEW");
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void doesNotCacheUnsuccessfulResponses() {
        // 500 (and 204 no_data) must not poison the cache — they should be retried.
        CountingClient delegate = new CountingClient(new Response(500, "err"), new Response(200, "ok"));
        CachingMarketDataClient client = caching(delegate);

        Response first = client.get("options/chain/AAPL/");
        Response second = client.get("options/chain/AAPL/"); // same instant, but error wasn't cached

        assertThat(first.status()).isEqualTo(500);
        assertThat(second.body()).isEqualTo("ok");
        assertThat(delegate.calls).isEqualTo(2);
    }

    // --- test doubles ---

    /** Returns queued responses in order; counts invocations. */
    private static final class CountingClient implements MarketDataClient {
        private final Deque<Response> responses = new ArrayDeque<>();
        private int calls;

        CountingClient(Response... canned) {
            for (Response r : canned) {
                responses.add(r);
            }
        }

        @Override
        public Response get(String path) {
            calls++;
            return responses.size() == 1 ? responses.peek() : responses.removeFirst();
        }
    }

    private static final class InMemoryCache implements MarketDataCache {
        private final Map<String, CachedResponse> store = new HashMap<>();

        @Override
        public Optional<CachedResponse> find(String cacheKey) {
            return Optional.ofNullable(store.get(cacheKey));
        }

        @Override
        public void put(String cacheKey, int status, String body, Instant fetchedAt) {
            store.put(cacheKey, new CachedResponse(status, body, fetchedAt));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
