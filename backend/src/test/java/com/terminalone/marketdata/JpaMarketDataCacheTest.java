package com.terminalone.marketdata;

import com.terminalone.marketdata.MarketDataCache.CachedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence round-trip for the Postgres-backed cache adapter, against the H2
 * test datasource (schema from JPA). Verifies the {@link MarketDataCache}
 * contract the TTL decorator relies on: store, read-back, upsert, miss.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaMarketDataCache.class)
class JpaMarketDataCacheTest {

    @Autowired
    private MarketDataCache cache;

    @Test
    void persistsAndReadsBackACachedResponse() {
        Instant fetchedAt = Instant.parse("2026-06-26T20:00:00Z");
        cache.put("options/chain/AAPL/", 203, "{\"s\":\"ok\"}", fetchedAt);

        Optional<CachedResponse> hit = cache.find("options/chain/AAPL/");

        assertThat(hit).isPresent();
        assertThat(hit.get().status()).isEqualTo(203);
        assertThat(hit.get().body()).isEqualTo("{\"s\":\"ok\"}");
        assertThat(hit.get().fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    void putReplacesAnExistingEntryForTheSameKey() {
        cache.put("k", 200, "old", Instant.parse("2026-06-26T20:00:00Z"));
        cache.put("k", 200, "new", Instant.parse("2026-06-26T20:10:00Z"));

        assertThat(cache.find("k")).get().extracting(CachedResponse::body).isEqualTo("new");
    }

    @Test
    void missReturnsEmpty() {
        assertThat(cache.find("never-fetched")).isEmpty();
    }
}
