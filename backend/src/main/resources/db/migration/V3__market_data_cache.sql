-- Phase 3 (console comes alive): raw vendor-response cache (FR-7, D3).
-- Caches exactly what MarketData.app returned — the raw (status, body) keyed by
-- request path — so repeat calls are served from Postgres and we respect vendor
-- rate-limits/credits. TTL freshness is enforced in the app layer
-- (CachingMarketDataClient); the row simply records when it was fetched.
CREATE TABLE market_data_cache (
    cache_key   VARCHAR(512) PRIMARY KEY, -- the vendor request path (incl. query)
    http_status INTEGER      NOT NULL,    -- 200 live | 203 delayed
    body        TEXT         NOT NULL,    -- raw columnar JSON response
    fetched_at  TIMESTAMPTZ  NOT NULL
);
