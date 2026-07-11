#!/usr/bin/env node
/**
 * Golden-fixture capture — MarketData.app option chain + stock quote.
 *
 * Records a REAL vendor response to disk so two things can be tested offline,
 * deterministically, with no token in CI:
 *   1. the `MarketDataProvider` columnar-JSON adapter parser (Phase 3 AC #1), and
 *   2. the in-house `OptionAnalytics` Black-Scholes IV/greeks cross-check vs the
 *      vendor's own IV/greeks (Phase 3 AC #2 — "independent of vendor greeks").
 *
 * The captured chain deliberately includes calls AND puts across ITM/ATM/OTM
 * strikes so the cross-check exercises both option types and a spread of moneyness.
 * Delayed (HTTP 203) responses cost 0 credits (confirmed by the OQ-1 probe), so
 * running this off-hours is effectively free.
 *
 * Usage:
 *   MARKETDATA_TOKEN=xxx node spikes/capture-marketdata-fixture.mjs [SYMBOL]
 *   node spikes/capture-marketdata-fixture.mjs <API_TOKEN> [SYMBOL]
 *
 * Output (raw, pretty-printed JSON, committed as test fixtures):
 *   backend/src/test/resources/fixtures/marketdata/chain-<SYMBOL>.json
 *   backend/src/test/resources/fixtures/marketdata/quote-<SYMBOL>.json
 *   backend/src/test/resources/fixtures/marketdata/candles-<SYMBOL>.json (daily OHLCV)
 *
 * Get a free token: https://www.marketdata.app  (Free Forever plan, no card).
 */

import { writeFile, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Accept token as arg1 only if it doesn't look like a ticker; else it's the symbol.
const looksLikeToken = (s) => s && !/^[A-Z.]{1,6}$/.test(s);
const arg1 = process.argv[2];
const token = (looksLikeToken(arg1) ? arg1 : null) ?? process.env.MARKETDATA_TOKEN;
const symbol = (
  (looksLikeToken(arg1) ? process.argv[3] : arg1) || "AAPL"
).toUpperCase();

if (!token) {
  console.error(
    "✗ No API token.\n" +
      "  Usage: MARKETDATA_TOKEN=xxx node spikes/capture-marketdata-fixture.mjs [SYMBOL]",
  );
  process.exit(2);
}

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "..", "backend", "src", "test", "resources", "fixtures", "marketdata");

// Both sides, ~35 DTE, a band of strikes around the money → ITM/ATM/OTM for calls & puts.
const chainUrl =
  `https://api.marketdata.app/v1/options/chain/${symbol}/` +
  `?dte=35&strikeLimit=12&token=${encodeURIComponent(token)}`;
const quoteUrl =
  `https://api.marketdata.app/v1/stocks/quotes/${symbol}/` +
  `?token=${encodeURIComponent(token)}`;
// Last ~120 daily bars (the console chart window, Phase 3 AC #4) — columnar t/o/h/l/c/v.
const candlesUrl =
  `https://api.marketdata.app/v1/stocks/candles/D/${symbol}/` +
  `?countback=120&token=${encodeURIComponent(token)}`;

async function get(label, url) {
  let res;
  try {
    res = await fetch(url, { headers: { Accept: "application/json" } });
  } catch (e) {
    throw new Error(`${label}: network error: ${e.message}`);
  }
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`${label}: non-JSON response (HTTP ${res.status}): ${text.slice(0, 200)}`);
  }
  // 200 = live, 203 = cached/delayed (success), 204 = no_data.
  const consumed = res.headers.get("x-api-ratelimit-consumed") ?? "n/a";
  console.log(`${label}: HTTP ${res.status} · s="${body.s}" · credits consumed: ${consumed}`);
  if (body.s === "error") throw new Error(`${label}: API error: ${body.errmsg ?? text.slice(0, 200)}`);
  if (body.s === "no_data") throw new Error(`${label}: no_data — try another symbol or widen dte.`);
  return { httpStatus: res.status, body };
}

(async () => {
  console.log(`\n→ Capturing golden fixtures for ${symbol} (chain ≈35 DTE both sides, + stock quote)\n`);
  await mkdir(outDir, { recursive: true });

  const chain = await get("chain", chainUrl);
  const quote = await get("quote", quoteUrl);
  const candles = await get("candles", candlesUrl);

  const n = chain.body.optionSymbol?.length ?? 0;
  const ivOk = Array.isArray(chain.body.iv) && chain.body.iv[0] != null;
  const greeksOk = ["delta", "gamma", "theta", "vega"].every(
    (g) => Array.isArray(chain.body[g]) && chain.body[g][0] != null,
  );
  console.log(
    `\nchain: ${n} contracts · vendor IV ${ivOk ? "present" : "ABSENT"} · ` +
      `vendor greeks ${greeksOk ? "present" : "ABSENT"}`,
  );
  if (!ivOk || !greeksOk) {
    console.warn(
      "⚠ Vendor IV/greeks missing on this response — the fixture still serves the\n" +
        "  adapter parser test, but the OptionAnalytics cross-check has no oracle to\n" +
        "  compare against. (Free tier normally returns both; retry off-hours.)",
    );
  }

  const candleCount = candles.body.t?.length ?? 0;
  console.log(`candles: ${candleCount} daily bars`);

  const chainPath = join(outDir, `chain-${symbol}.json`);
  const quotePath = join(outDir, `quote-${symbol}.json`);
  const candlesPath = join(outDir, `candles-${symbol}.json`);
  await writeFile(chainPath, JSON.stringify(chain.body, null, 2) + "\n");
  await writeFile(quotePath, JSON.stringify(quote.body, null, 2) + "\n");
  await writeFile(candlesPath, JSON.stringify(candles.body, null, 2) + "\n");

  console.log(`\n✓ wrote ${chainPath}`);
  console.log(`✓ wrote ${quotePath}`);
  console.log(`✓ wrote ${candlesPath}`);
  console.log(
    "\nNext: re-run the backend tests — the assumeTrue-gated cross-check\n" +
      "(BlackScholesOptionAnalyticsTest) will activate now that the fixture exists.",
  );
})().catch((e) => {
  console.error(`✗ ${e.message}`);
  process.exit(1);
});
