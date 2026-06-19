#!/usr/bin/env node
/**
 * OQ-1 de-risk probe — MarketData.app option chain.
 *
 * Confirms the cheapest viable data tier returns what Terminal One's engine needs:
 *   REQUIRED : option chains with bid/ask quotes  (engine computes IV + greeks itself, per D7)
 *   BONUS    : vendor-provided IV + greeks (delta/gamma/theta/vega)
 *
 * Credit-frugal: requests ONE near-term expiration, calls only, a few strikes
 * around the money — costs only a handful of API credits (free tier = 100/day).
 *
 * Usage:
 *   node spikes/probe-marketdata.mjs <API_TOKEN> [SYMBOL]
 *   MARKETDATA_TOKEN=xxx node spikes/probe-marketdata.mjs
 *
 * Get a free token: https://www.marketdata.app  (Free Forever plan, no card)
 */

const token = process.argv[2] || process.env.MARKETDATA_TOKEN;
const symbol = (process.argv[3] || "AAPL").toUpperCase();

if (!token) {
  console.error("✗ No API token. Usage: node spikes/probe-marketdata.mjs <API_TOKEN> [SYMBOL]");
  process.exit(2);
}

// Frugal query: ~30-45 DTE, calls only, 4 strikes around the money.
const url =
  `https://api.marketdata.app/v1/options/chain/${symbol}/` +
  `?dte=35&side=call&strikeLimit=4&token=${encodeURIComponent(token)}`;

const REQUIRED = ["optionSymbol", "strike", "expiration", "bid", "ask", "underlyingPrice"];
const BONUS = ["iv", "delta", "gamma", "theta", "vega", "openInterest", "volume", "mid"];

const present = (arr) => Array.isArray(arr) && arr.length > 0 && arr[0] !== null && arr[0] !== undefined;

(async () => {
  console.log(`\n→ Probing MarketData.app option chain for ${symbol} (≈35 DTE, calls, 4 strikes)\n`);
  let res, body;
  try {
    res = await fetch(url, { headers: { Accept: "application/json" } });
  } catch (e) {
    console.error(`✗ Network error: ${e.message}`);
    process.exit(1);
  }

  const text = await res.text();
  try { body = JSON.parse(text); } catch { body = null; }

  // MarketData.app returns 200 (ok), 203 (cached/delayed), 204 (no data).
  console.log(`HTTP ${res.status} · x-Api-RateLimit-Consumed: ${res.headers.get("x-api-ratelimit-consumed") ?? "n/a"}`);

  if (!body || body.s === "error") {
    console.error(`✗ API error: ${body?.errmsg || text.slice(0, 200)}`);
    console.error("  (Common causes: bad token, plan lacks options, symbol/expiry invalid.)");
    process.exit(1);
  }
  if (body.s === "no_data") {
    console.error("✗ no_data for that query — try another symbol or widen dte.");
    process.exit(1);
  }

  const n = body.optionSymbol?.length ?? 0;
  console.log(`Status s="${body.s}"  ·  contracts returned: ${n}`);
  if (body.updated?.[0]) {
    const ageMin = Math.round((Date.now() / 1000 - body.updated[0]) / 60);
    console.log(`Freshness: data timestamp ~${ageMin} min old (confirms delayed vs real-time)\n`);
  }

  console.log("REQUIRED fields (engine cannot work without these):");
  let pass = true;
  for (const f of REQUIRED) {
    const ok = present(body[f]);
    pass = pass && ok;
    console.log(`  ${ok ? "✓" : "✗"} ${f.padEnd(16)} ${ok ? `e.g. ${JSON.stringify(body[f][0])}` : "MISSING/null"}`);
  }

  console.log("\nBONUS fields (nice — else engine derives them via Black-Scholes):");
  for (const f of BONUS) {
    const ok = present(body[f]);
    console.log(`  ${ok ? "✓" : "·"} ${f.padEnd(16)} ${ok ? `e.g. ${JSON.stringify(body[f][0])}` : "absent on this tier — will compute"}`);
  }

  const ivOk = present(body.iv);
  const greeksOk = ["delta", "gamma", "theta", "vega"].every((g) => present(body[g]));

  console.log("\n──────── VERDICT ────────");
  if (pass) {
    console.log("✓ REQUIRED data present → this tier WORKS for Terminal One.");
    console.log(`  • IV provided by vendor:     ${ivOk ? "yes" : "NO → engine computes from bid/ask mid"}`);
    console.log(`  • Greeks provided by vendor: ${greeksOk ? "yes" : "NO → engine computes via Black-Scholes"}`);
    console.log("  Either way, the engine's inputs are satisfied. OQ-1 cleared on this vendor/tier.");
    process.exit(0);
  } else {
    console.log("✗ A REQUIRED field is missing — chains/quotes incomplete on this tier.");
    console.log("  Re-run on a higher tier or try the Tradier probe before committing the adapter.");
    process.exit(1);
  }
})();
