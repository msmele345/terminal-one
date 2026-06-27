package com.terminalone.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository over {@link MarketDataCacheEntry} (keyed by request path). */
public interface MarketDataCacheRepository extends JpaRepository<MarketDataCacheEntry, String> {
}
