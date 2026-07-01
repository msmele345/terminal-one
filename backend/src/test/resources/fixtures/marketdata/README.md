# MarketData.app golden fixtures

Real, recorded vendor responses used as deterministic, token-free test inputs:

- `chain-<SYMBOL>.json` — raw columnar option-chain response (`/v1/options/chain`).
  Drives the `MarketDataProvider` adapter-parser tests (Phase 3 AC #1) and the
  `OptionAnalytics` Black-Scholes cross-check vs vendor IV/greeks (Phase 3 AC #2).
- `quote-<SYMBOL>.json` — raw columnar stock-quote response (`/v1/stocks/quotes`).
- `candles-<SYMBOL>.json` — raw columnar daily OHLCV response (`/v1/stocks/candles/D`,
  parallel arrays `t/o/h/l/c/v`). Drives the `MarketDataProvider` daily-bars
  parser test (Phase 3 AC #4) — feeds the per-symbol console chart.

## Regenerate

```sh
MARKETDATA_TOKEN=xxx node spikes/capture-marketdata-fixture.mjs AAPL
```

Delayed (HTTP 203) responses cost 0 API credits. Capture off-hours so the vendor
returns IV + greeks (the cross-check oracle). The format is columnar JSON: parallel
arrays keyed by field, with a status field `s` (`ok`/`no_data`/`error`),
`expiration` in unix-epoch seconds, and `optionSymbol` in OCC format.

Tests that consume these fixtures skip cleanly (JUnit `assumeTrue`) when the files
are absent, so the build stays green until the fixtures are captured.
