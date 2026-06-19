# Terminal One — Product Requirements Document (V1)

> **Working title:** Terminal One
> **Author:** mitchmele@gmail.com
> **Status:** Draft — vetted via design grill, 2026-06-18
> **One-liner:** A single-user desktop trading cockpit — an NYSE-floor terminal crossed with a late-night casino slot machine — that monitors your stock/option positions and runs a deterministic options-trade recommendation engine you can audit.

---

## 1. Summary

Terminal One is a personal (single-user) full-stack application for monitoring an equities/options portfolio and generating **defined-risk options trade recommendations**. The recommendation engine is a **deterministic, fully auditable rules engine** that combines technical/volatility signals with options-greeks/probability analysis. The experience is delivered through a **vibrant, dark, neon "casino terminal" desktop UI** where the recommendation engine literally manifests as a slot machine: pull the lever, the reels spin, and they land on recommended trades.

V1 is explicitly **advisory + tracking only** — it never routes a live order. You execute manually in your broker and optionally mark recommendations as "taken" so the app keeps a real performance record alongside a paper-trade ledger.

---

## 2. Goals & Non-Goals

### 2.1 Goals (V1)
- Monitor a manually-entered portfolio of stocks and option positions with near-real-time (delayed) pricing and P&L.
- Produce ranked, **explainable** options recommendations (long calls/puts, covered calls, cash-secured puts, debit/credit verticals) on the stocks in the portfolio.
- Validate recommendation quality over time via a **paper-trade ledger** plus a **directional-signal backtest** on free historical stock data.
- Deliver a distinctive, delightful "slot machine" recommendation moment without sacrificing the readability of a data-dense terminal.
- Run cheaply and always-on, with a real CI/CD test gate.

### 2.2 Non-Goals (explicitly deferred)
- ❌ Live order execution / brokerage write access (V2+).
- ❌ Multi-user / multi-tenant / public signup (V2+).
- ❌ Real-time tick data and intraday streaming (V2+).
- ❌ Full multi-leg strategies (iron condors, straddles, calendars) (V2/V3).
- ❌ Full historical-options-chain backtesting (V2).
- ❌ Configurable price/P&L alert-rules engine (V1.5/V2).
- ❌ In-app engine-config **Settings screen** (V1.5 — in V1 the config is editable via the DB row + `docs/config-update-runbook.md`).
- ❌ Brokerage auto-sync of positions (V2 — abstraction in place now).

---

## 3. Decision Log

Every decision below was made deliberately during design review. Each lists the **decision**, the **rationale**, and the **branch not taken**.

| # | Decision Point | **Decision** | Rationale | Rejected alternative |
|---|----------------|--------------|-----------|----------------------|
| D1 | Audience / tenancy | **Single-user (just me)** | No compliance/"investment adviser" exposure, no multi-tenancy, cheapest + fastest. Engine can be as opinionated as desired. | Private beta; public SaaS |
| D2 | Portfolio ingestion | **Manual / CSV entry now, behind a `PositionSource` interface; brokerage adapter in V2** | Decouples hard OAuth integration from the core feature; ships fast; works with any broker. | Brokerage API sync in V1 |
| D3 | Market data | **15-min delayed data behind a `MarketDataProvider` interface (polling, not websockets). Concrete pick: MarketData.app — free tier for dev, Starter $12/mo for live.** | Cheapest source returning option chains with bid/ask (~$12/mo live, $0 in dev); delayed is correct for a swing/options horizon; chains+quotes is all the engine needs since greeks/IV are computed in-house (D22). | Polygon Starter $29 (free tier blocks the greeks/IV snapshot entirely); Tradier $10 (needs broker account); real-time tiers |
| D4 | Strategy universe | **Long calls/puts, covered calls, cash-secured puts, and debit/credit vertical spreads** | The "interesting but shippable" tier — introduces defined-risk payoff modeling and 2-strike selection without combinatorial multi-leg complexity. | Single-leg only; full multi-leg |
| D5 | Engine implementation | **Deterministic rules engine in Java, no LLM** | Auditable, reproducible, free to run, backtestable, line-by-line explainable. Correct for anything selecting real-money trades. LLM narration is a clean *additive* layer later. | Hybrid (rules+LLM narration); LLM-driven |
| D6 | Technical/directional signal | **Trend+momentum + volatility regime** (RSI, MACD, MA crossover for direction; ATR / Bollinger width / IV rank for regime) | The vol-regime read is load-bearing: it decides debit vs credit structure. | Classic trend+momentum only; user's own bespoke system |
| D7 | Options pricing analysis | **Greeks + Probability-of-Profit + risk/reward (relative value)** — IV rank → debit/credit; delta → strike targeting; Black-Scholes-derived POP and risk/reward → ranking | Industry-standard, robust under delayed data, backtestable. Avoids fragile "fair-value mispricing" that just rediscovers your IV assumption. | Theoretical mispricing; both/with filter |
| D8 | Validation | **Forward paper-tracking + directional-signal backtest** | Backtests the technical signal on free historical *stock* prices; logs every options rec to a paper ledger that settles real outcomes over time. No expensive historical chains needed. | Full options backtest; no validation |
| D9 | Engine cadence | **Scheduled EOD batch + on-demand "pull the lever"** | Fits delayed data + swing horizon; the lever-pull *is* the slot-machine moment; EOD batch settles the paper ledger automatically. | On-demand only; continuous intraday |
| D10 | Backend topology | **Cloud-hosted Spring Boot + Postgres (always-on); Electron is a thin client** | EOD batch + ledger settlement must run even when the laptop is off; remote access; "real product" shape. | Fully local; local + tiny cloud cron |
| D11 | Host platform | **Railway** (managed Postgres, native cron, git-push deploy, ~$5–15/mo). Dockerized for Azure portability. | Best DX + cheapest at this scale; stays portable to Azure (stated long-term preference) via container + 12-factor config. | Azure App Service+Postgres (~$25–30/mo); Render/Fly |
| D12 | CI/CD | **GitHub Actions (test gate) + Railway auto-deploy on merge to main** | One ecosystem, free at this volume, automated test gate protects a money-touching engine. | Azure DevOps; Railway-only (no CI) |
| D13 | Electron packaging | **Unsigned local build via electron-builder** (one-time macOS Gatekeeper allow) | $0, no Apple Developer account, fine for single-user. Signing/notarization is a clean upgrade if it ever ships to others. | Signed+notarized+auto-update; dev-mode only |
| D14 | Execution boundary | **Advisory + paper only; plus manual "mark as taken" tracking** | No order routing = no real-money routing bugs, no trading write-scope. "Mark as taken" promotes a rec from paper ledger to a real-positions tracker for a true performance record. | One-click broker execution |
| D15 | Backend auth | **Single-account login → JWT (Spring Security)** | Standard, clean, extensible to multi-user. Market-data key + DB creds stay server-side only. | Static API key; full OAuth/OIDC |
| D16 | UI render tech | **React + CSS (Tailwind) + Framer Motion** | ~90% of the spectacle (reels, flips, payout pulses via CSS transforms) at a fraction of the effort; terminal panels stay readable. | React+PixiJS/WebGL showpiece; full game-engine |
| D17 | Slot-machine metaphor | **Functional metaphor on the recommendation moment only** | The engine *is* the slot machine (lever → reels → trades, jackpot for high-conviction/winning paper trades); the rest is a sleek neon NYSE terminal. Delight where earned, legibility everywhere else. | Aesthetic skin only; full casino floor |
| D18 | V1 screens | **Lean 3-screen set** (Portfolio Console, Slot Machine, Paper/Taken Ledger) + positions-entry/CSV modal | Tight, shippable, covers every scoped feature. | 2-screen minimum; +Analysis deep-dive (now V1.5) |
| D19 | Charting | **TradingView Lightweight Charts** | Free, finance-grade, dark-themeable, performant. | Recharts/visx; custom |
| D20 | Alerts | **Minimal EOD desktop notification** ("N new recs, top conviction: X") | One native Electron notification post-batch — pulls you in without becoming noise. | No alerts; configurable alert-rules engine |
| D21 | Engine config storage | **DB-backed config row behind an `EngineConfigProvider` interface, seeded from the strategy-matrix §9 YAML defaults; versioned and stamped on every recommendation. In-app Settings screen deferred to V1.5; V1 edits via a documented manual runbook.** | Tuning is data, not code; changes take effect on the *next* engine run with no redeploy; version-stamping keeps the paper-trade track record interpretable across config changes (you can compare profile v1 vs v2 with evidence). | Static `application.yml` (needs redeploy); env-var config (clumsy for ~30 params); Settings UI in V1 (premature) |
| D22 | Greeks & IV computation | **Engine computes implied volatility (inverted from the bid/ask mid) and greeks (delta/gamma/theta/vega) in-house via Black-Scholes in an `OptionAnalytics` component; `MarketDataProvider` only needs to return chains + quotes.** | Decouples the engine from vendor greeks tiering (the $12 tier need not include greeks), makes the data vendor swappable (D3), and reuses the BS pricing the engine already does (D7). Tradeoff: BS-European approximation vs vendors' binomial — minor for liquid equity options at our target deltas. | Depend on vendor-supplied greeks/IV (forces a pricier tier + vendor lock-in) |

---

## 4. Personas & Use Cases

**Persona:** The owner (single user) — a self-directed retail options trader comfortable with technical analysis and defined-risk spreads, who wants a fast, opinionated, *trustworthy* second opinion and a record of how its calls play out.

**Primary use cases**
1. **Morning check-in:** open the console, see overnight P&L on positions, read the EOD recommendations that landed.
2. **Pull the lever:** manually re-run the engine after entering/updating positions, watch the reels resolve to ranked trades, inspect the "why."
3. **Track outcomes:** review the paper ledger; mark a rec as "taken"; watch the realized track record build.
4. **Maintain portfolio:** add/edit/CSV-import positions (stocks + option legs) with cost basis.

---

## 5. Functional Requirements

### 5.1 Portfolio & Positions
- FR-1: Manually add/edit/delete stock positions (ticker, qty, cost basis, open date).
- FR-2: Manually add/edit/delete option positions (underlying, type C/P, strike, expiry, qty, cost basis, long/short).
- FR-3: CSV import of positions (defined template).
- FR-4: All position I/O flows through a `PositionSource` interface; V1 implements `ManualPositionSource`.
- FR-5: Compute live-ish (delayed) market value, P&L ($/%) per position and for the portfolio.

### 5.2 Market Data
- FR-6: Fetch 15-min delayed stock quotes and option chains (strikes, expiries, bid/ask, OI) via `MarketDataProvider` (MarketData.app adapter). Vendor greeks/IV are *not* required — see FR-8a.
- FR-7: Cache market data in Postgres with TTL to respect rate limits/credits; serve UI from cache.
- FR-8: Fetch free historical daily stock bars for indicator computation and signal backtesting.
- FR-8a: An `OptionAnalytics` component computes implied volatility (inverted from the bid/ask mid via Black-Scholes) and greeks (delta/gamma/theta/vega) from raw chains, and persists daily ATM IV to `iv_history` for IV-rank. The data vendor is never relied on for greeks/IV (D22).

### 5.3 Recommendation Engine (core)
- FR-9: Compute per-underlying technical signal → `{direction: BULLISH|BEARISH|NEUTRAL, conviction: 0–100}` from RSI, MACD, MA crossover.
- FR-10: Compute volatility regime → `{regime: LOW|NORMAL|HIGH}` from ATR / Bollinger width / IV rank.
- FR-11: Map (direction × regime) → candidate strategy types (e.g., bullish+high-IV → bull put credit spread; bullish+low-IV → bull call debit spread; income overlays via covered call / CSP where positions allow).
- FR-12: Select strikes/expiries via delta targeting; compute Black-Scholes-derived POP, max profit/loss, risk/reward, expected value for each candidate.
- FR-13: Rank candidates and emit top-N recommendations with a structured, human-readable **rationale** (the exact signals/values that drove it).
- FR-14: Persist every recommendation (snapshot of inputs, contract(s), entry mid, scores, timestamp, **and the active `configVersion` that produced it**).
- FR-15: Engine runs (a) as a scheduled EOD batch over all portfolio underlyings, and (b) on-demand via the lever.
- FR-16: Every scoring/selection step is a pure, unit-tested function.

### 5.4 Validation & Tracking
- FR-17: Paper-trade ledger logs each recommendation; an EOD job settles open paper trades against current marks and records realized/unrealized P&L.
- FR-18: "Mark as taken" promotes a recommendation to a real-positions tracker (separate from paper).
- FR-19: Directional-signal backtest harness runs the technical signal over historical stock bars and reports hit-rate/return stats.

### 5.5 UX / Frontend
- FR-20: **Portfolio Console** — positions, P&L, LED-style tickers, TradingView charts, neon dark terminal aesthetic.
- FR-21: **Slot Machine** — pull-the-lever to run the engine; Framer-Motion reels spin and land on recommended trades; jackpot/payout animation for high-conviction recs and winning paper trades; expandable rationale per result.
- FR-22: **Paper/Taken Ledger** — list of recommendations with status (paper/taken), realized/unrealized outcomes, aggregate track record.
- FR-23: Positions-entry/CSV-import modal.
- FR-24: Minimal native desktop notification after EOD batch.

### 5.6 Platform / Auth
- FR-25: Single-account login (username/password) → JWT stored in OS keychain; all API calls authenticated.
- FR-26: Secrets (market-data API key, DB creds, JWT signing key) live only in Railway env, never in the Electron bundle.

### 5.7 Engine Configuration
- FR-27: The active engine config (all strategy-matrix §9 parameters) is persisted as a **versioned row in Postgres** and read at the **start of each engine run** via an `EngineConfigProvider` interface. The §9 YAML defaults are the immutable **factory-reset baseline** used to seed v1.
- FR-28: Each config change creates a **new immutable version** (no in-place overwrite); past recommendations are never mutated and remain stamped with the `configVersion` that produced them (per FR-14), so the paper-trade ledger stays interpretable across changes.
- FR-29: Config values are **bounds-validated** on write (e.g., deltas ∈ [0,1], `perTradeRiskPct` ∈ (0, 0.25], IV-rank cutoffs ordered and ∈ [0,100]); invalid configs are rejected, never activated.
- FR-30: Ship a **markdown runbook** (`docs/config-update-runbook.md`) documenting exactly how to manually update the seeded config row in V1 — connect to Railway Postgres, insert a new version, activate it, verify it took effect, and roll back — to be used until the V1.5 Settings screen exists.

---

## 6. Architecture

```
┌──────────────────────────────┐         HTTPS + JWT          ┌─────────────────────────────────────┐
│  Electron + React (thin UI)  │  ─────────────────────────▶  │      Spring Boot (Railway)          │
│  - Portfolio Console         │                              │  - REST API (Spring Security/JWT)   │
│  - Slot Machine (Framer)     │  ◀─────────────────────────  │  - RecommendationEngine (deterministic) │
│  - Ledger                    │         JSON                 │  - MarketDataProvider (MarketData.app)  │
│  - OptionAnalytics (BS: IV + greeks)    │
│  - OS keychain (JWT)         │                              │  - PositionSource (Manual/CSV adapter)  │
│  - Native notifications      │                              │  - Scheduler (EOD batch + ledger settle)│
└──────────────────────────────┘                              │  - Paper-trade + backtest services      │
                                                              └───────────────┬─────────────────────┘
                                                                              │
                                                              ┌───────────────┴───────────────┐
                                                              │   Postgres (Railway)          │
                                                              │  positions, recommendations,  │
                                                              │  paper_trades, taken_positions,│
                                                              │  market_data_cache, signals    │
                                                              └───────────────────────────────┘
                                                                              ▲
                                                              ┌───────────────┴───────────────┐
                                                              │  MarketData.app (15m delayed    │
                                                              │  chains+quotes) + free hist bars│
                                                              └─────────────────────────────────┘
```

**Key interfaces (seams for deferred work):**
- `PositionSource` → `ManualPositionSource` (V1), `BrokeragePositionSource` (V2).
- `MarketDataProvider` → `MarketDataAppProvider` (V1, returns chains+quotes only), real-time/broker providers (V2). Greeks/IV derived in-house by `OptionAnalytics` (D22), independent of the vendor.

**Indicative data model (Postgres):** `positions`, `option_positions`, `recommendations`, `recommendation_legs`, `paper_trades`, `taken_positions`, `market_data_cache`, `signal_snapshots`, `app_user`.

---

## 7. CI/CD & Environments

- **Repo:** single GitHub repo (monorepo: `/backend` Spring Boot, `/desktop` Electron+React).
- **PR pipeline (GitHub Actions):** backend `mvn/gradle test` (engine unit tests are the gate); frontend lint + `electron-builder` build check.
- **Deploy:** merge to `main` → Railway auto-deploys backend; Postgres + cron managed by Railway.
- **Electron artifact:** Actions builds the unsigned `.dmg`/`.app`; downloaded/installed manually.
- **Envs:** Railway `production` (always-on) + local dev. Secrets in Railway env. Designed 12-factor + Dockerized for a mechanical future lift to Azure (App Service + Postgres Flexible).
- **Cost target:** **~$17–27/mo live** (Railway ~$5–15 + MarketData.app Starter $12) and **~$0 during development** (Railway free + MarketData.app free tier).

---

## 8. Milestones (suggested)

- **M0 — Skeleton:** monorepo, Spring Boot + Postgres on Railway, Electron shell, JWT login, GitHub Actions gate.
- **M1 — Portfolio Console:** manual/CSV positions, `MarketDataProvider` (Polygon delayed) + caching, P&L, charts, neon terminal.
- **M2 — Recommendation Engine:** indicators, vol regime, strategy mapping, greeks/POP/risk-reward, ranked recs + rationale, EOD batch + lever endpoint. **DB-backed `EngineConfigProvider` seeded from §9 defaults, version-stamping on every rec, and the `config-update-runbook.md`.** *(Heavily unit-tested.)*
- **M3 — Slot Machine UI:** Framer-Motion reels, jackpot animations, rationale drill-down.
- **M4 — Validation:** paper-trade ledger + settlement job, "mark as taken," directional-signal backtest, ledger screen.
- **M5 — Polish:** EOD desktop notification, error handling, packaging, docs.
- **V1.5:** per-ticker **Analysis deep-dive** (indicator panels, IV rank, full chain, payoff diagrams); **engine-config Settings screen** (visual editor over the versioned config, replacing the manual runbook).

---

## 9. Open Questions / Defaulted Assumptions

These were defaulted during review (sensible picks) but are **yours to override**:

- **OQ-1 (market data vendor):** ✅ **RESOLVED** — **MarketData.app** (free tier for dev, **Starter $12/mo** for live) returns 15-min delayed option chains with bid/ask; **greeks/IV are computed in-house** (D22), so vendor greeks tiering is irrelevant. Polygon's free tier was ruled out (no snapshot/greeks access; cheapest greeks tier $29). Fallbacks behind the same interface: Tradier ($10 + broker account), Yahoo ($0, unofficial). **✓ Probe-confirmed** (free tier, AAPL via `spikes/probe-marketdata.mjs`): all required fields present (chains + bid/ask + underlying); bonus — vendor returns IV + full greeks *even on the free tier* (so they double as a validation oracle for the in-house `OptionAnalytics`, D22), and the delayed response (HTTP 203, cached) consumed **0 API credits**, suggesting delayed usage is effectively unmetered (confirm at batch scale). Free tier is ~24h delayed → keep $12 Starter (15-min) for live.
- **OQ-2 (historical stock bars source):** Assumed a free source (Polygon delayed history / Stooq). Confirm.
- **OQ-3 (portfolio scale):** Assumed ~10–30 underlyings — sizes API quota and batch runtime. Confirm your realistic ceiling.
- **OQ-4 (EOD batch timing/timezone):** Assumed it runs after US market close (ET). Confirm exact time.
- **OQ-5 (strategy mapping matrix):** ✅ **RESOLVED** — see [`docs/strategy-matrix.md`](./strategy-matrix.md). The full (direction × regime) → strategy matrix, conviction bands, delta/DTE targets, guardrails, sizing, and ranking objective are now specified as a tunable default config, ready for your red-line and for M2 build.
- **OQ-6 (top-N):** How many recommendations per underlying / per run should the reels surface? (Default: top 3 overall.)
- **OQ-7 (disclaimer):** Even single-user, include a "not financial advice / personal tool" disclaimer in the UI. (Default: yes.)
- **OQ-8 (option position cost-basis tracking):** For "mark as taken," do you want fill-price entry at mark time or manual fill entry? (Default: manual fill entry for accuracy.)

---

## 10. Risks

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Cheap data tier lacks vendor greeks/IV | — | **Mitigated by design (D22):** engine computes IV + greeks in-house from chains+quotes; vendor only supplies chains+quotes. Free probe (`spikes/probe-marketdata.mjs`) confirms quotes are present before building. |
| Engine recommends bad trades | Loss of trust / real money | Deterministic + unit-tested + paper-ledger validation before trusting; advisory-only boundary (D14) |
| Slot-machine novelty undermines data legibility | Daily-use friction | Metaphor confined to the rec moment (D17); terminal panels stay conventional |
| Railway cost creep / lock-in | Budget | Dockerized + 12-factor for Azure portability (D11) |
| Scheduler/data quota exhaustion | Stale data | Cache with TTL (FR-7); EOD batch not intraday polling (D9) |

---

*End of PRD. Next step options: (a) answer the OQ-5 strategy-matrix question so the engine spec is build-ready; (b) turn this into a phased implementation plan; (c) start the M0 skeleton.*
