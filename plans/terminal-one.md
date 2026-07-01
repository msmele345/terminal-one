# Plan: Terminal One (V1)

> Source PRD: `docs/PRD.md` · Engine spec: `docs/strategy-matrix.md` · Config runbook: `docs/config-update-runbook.md`
> Strategy: tracer-bullet vertical slices. Each phase cuts through **every** layer (Postgres → Spring Boot → Electron/React → tests) and is independently demoable.

## Architectural decisions

Durable decisions referenced by every phase:

- **Repo**: monorepo — `/backend` (Spring Boot, Maven or Gradle), `/desktop` (Electron + React + TypeScript + Tailwind + Framer Motion).
- **Topology**: cloud-hosted Spring Boot + Postgres on **Railway** (always-on); Electron is a thin client. Dockerized + 12-factor for future Azure portability.
- **Auth**: single-account login → JWT (Spring Security). JWT stored in OS keychain. Secrets (market-data key, DB creds, JWT signing key) only in Railway env.
- **API routes** (all JSON; all JWT-protected except login/health):
  - `POST /api/auth/login`
  - `GET|POST /api/portfolio/positions`, `PUT|DELETE /api/portfolio/positions/{id}`, `POST /api/portfolio/import`
  - `GET /api/portfolio/summary` (positions + delayed P&L)
  - `GET /api/marketdata/quote/{symbol}`, `GET /api/marketdata/chain/{symbol}` (served from cache)
  - `POST /api/engine/run` (lever-pull, on-demand) → recommendations
  - `GET /api/recommendations`, `POST /api/recommendations/{id}/take`
  - `GET /api/ledger`
  - `POST /api/backtest/signal`
  - `GET /api/config/active` (read-only in V1), `GET /api/health`
- **Key seams (interfaces)**: `PositionSource` (V1 = manual/CSV), `MarketDataProvider` (V1 = MarketData.app adapter, returns chains+quotes only), `OptionAnalytics` (computes IV + greeks via Black-Scholes in-house, D22), `EngineConfigProvider` (V1 = DB-backed).
- **Schema shape (tables)**: `app_user`, `positions`, `option_positions`, `market_data_cache`, `iv_history`, `signal_snapshots`, `recommendations` (status ∈ {PAPER, TAKEN}, carries `config_version`), `paper_trades`, `taken_positions`, `engine_config` (version, config jsonb, is_active, note).
- **Key models**: `DirectionSignal{direction, conviction}`, `VolRegime{regime}`, `StrategyCandidate`, `Recommendation`, `EngineConfig`.
- **Engine principle**: deterministic, no LLM; every scoring/selection step a pure, unit-tested function; config read at the start of each run; every recommendation stamped with `config_version`.
- **CI/CD**: GitHub Actions runs backend tests + Electron build check on PRs (test gate); merge to `main` → Railway auto-deploys backend. Electron shipped as unsigned electron-builder artifact.
- **Data note (cross-cutting risk)**: Provider = **MarketData.app** (free tier for dev, $12 Starter live); only **chains+quotes** are required since greeks/IV are computed in-house (D22). IV Rank needs ~1yr IV history nobody has at launch — Phase 3 begins accumulating daily ATM IV; the engine bootstraps with a Bollinger-width-percentile proxy until ≥60 days exist.
- **MarketData.app adapter notes (captured from the OQ-1 probe)**: response is **columnar JSON** (parallel arrays keyed by field, not row objects) with a status field `s` (`ok`/`no_data`/`error`); `expiration` is **unix epoch seconds**; `optionSymbol` is **OCC format** (e.g. `AAPL260724C00290000`); HTTP **200 = live, 203 = cached/delayed (success), 204 = no_data** — handle distinctly. Delayed (203) responses consumed **0 credits**. The `dte` query param drives expiry-window selection; `strikeLimit` + `side` keep requests cheap. Vendor greeks/IV are returned even on free tier → capture a real chain response in Phase 3 as a golden fixture for both the adapter parser and the `OptionAnalytics` Black-Scholes cross-check (tolerance-based, since vendor uses binomial/American vs our BS/European).

---

## Phase 1: Walking skeleton

**User stories**: Foundation for all (FR-25, FR-26; decisions D10–D13, D15).

### What to build

The thinnest possible end-to-end path that proves the entire stack. Electron app boots into the neon dark shell, presents a single-account login, exchanges credentials for a JWT (stored in OS keychain), and makes one authenticated call to a Spring Boot endpoint deployed on Railway that reads from Postgres and returns a trivial payload (e.g., `whoami` + server time). No domain logic. CI is green and merging to `main` auto-deploys. Use Spring Boot 4.1.0 or the latest 4.x.x version available.

### Acceptance criteria

- [x] Monorepo scaffolded (`/backend`, `/desktop`) with a documented local dev startup. *(See `README.md`.)*
- [x] Spring Boot app deploys to Railway with managed Postgres attached; `GET /api/health` returns 200 publicly. *(Deploy-ready: multi-stage `backend/Dockerfile`, `backend/railway.toml` with `/api/health` healthcheck, and `docs/deploy-railway.md`. Verified locally against Dockerized Postgres + Flyway — health returns 200 publicly. Final step: create the Railway project per the runbook — a one-time dashboard action.)*
- [x] `POST /api/auth/login` issues a JWT for the single seeded account; all other routes reject missing/invalid JWT with 401. *(Verified end-to-end via curl + `AuthFlowIntegrationTest`.)*
- [x] Electron shell renders the dark/neon base theme, performs login, stores the JWT in the OS keychain, and displays the authenticated payload. *(JWT stored via `keytar` in the main process; renderer never touches the token/network. Auth round-trip verified at the API layer; `npm run lint`/`typecheck`/`build` green.)*
- [x] GitHub Actions runs backend tests + Electron build on PRs and blocks merge on failure; merge to `main` triggers Railway deploy. *(Workflow in `.github/workflows/ci.yml` runs `mvn test` + desktop lint/typecheck/build on PRs. "Blocks merge" needs branch protection enabled on GitHub; "deploy on merge" needs the Railway GitHub integration — both one-time repo/dashboard settings.)*
- [x] Secrets are sourced only from Railway env (nothing committed); local dev uses a `.env`/profile that is gitignored. *(`.gitignore` excludes `.env*`; `.env.example` templates committed for backend + desktop; `application.yml` reads everything from env.)*

---

## Phase 2: Portfolio positions (manual)

**User stories**: UC4 "Maintain portfolio" (FR-1–FR-5).

### What to build

A complete path to enter and see positions. The Portfolio Console lists positions; an entry modal adds/edits/deletes stock and option positions; CSV import ingests a defined template. All I/O flows through the `PositionSource` interface (`ManualPositionSource`). No pricing yet — display cost basis and static fields only.

### Acceptance criteria

- [x] Add, edit, and delete a **stock** position (symbol, qty, cost basis, opened date) end-to-end; persisted and reflected on the console. *(REST `positions` route + `PositionModal`; covered by `PortfolioControllerTest`.)*
- [x] Add, edit, and delete an **option** position (underlying, C/P, strike, expiry, qty, cost basis, long/short). *(`OptionPosition` entity + option fields in the modal; `addEditAndDeleteAnOptionPosition` test.)*
- [x] CSV import of positions against a documented template; malformed rows are reported, not silently dropped. *(`PositionCsvParser` returns per-line errors; template in `docs/positions-csv-template.md`; `Import CSV` button on the console.)*
- [x] All reads/writes go through `PositionSource`; the manual adapter is the only implementation wired. *(`PositionSource` interface, sole impl `ManualPositionSource`; controller depends only on the interface.)*
- [x] Backend tests cover CRUD + CSV parsing; a UI test covers add → list. *(`PositionCsvParserTest`, `ManualPositionSourceTest`, `PortfolioControllerTest`; renderer `PortfolioConsole.test.tsx`.)*
- [x] Empty-state on the console when no positions exist. *(`empty-state` panel; `shows the empty-state` UI test.)*

---

## Phase 3: Console comes alive (market data + P&L)

**User stories**: UC1 "Morning check-in" (FR-6–FR-8; decision D3).

### What to build

Positions gain delayed market value and P&L, and the console gets charts. A `MarketDataProvider` (MarketData.app adapter) fetches quotes and option chains (strikes/expiries/bid-ask) behind the interface, cached in `market_data_cache` with a TTL to respect rate limits/credits. An `OptionAnalytics` component computes IV (inverted from mid) and greeks via Black-Scholes (D22). `GET /api/portfolio/summary` returns positions with delayed market value, unrealized P&L ($/%), and portfolio totals. TradingView Lightweight Charts render per-symbol price history. This phase also **begins persisting daily ATM IV** to `iv_history` (computed via Black-Scholes) to de-risk the engine's IV-rank input.

### Acceptance criteria

- [ ] `MarketDataProvider` returns delayed quotes + option chains (strikes/expiries/bid-ask/OI) for portfolio symbols; results cached with TTL and served from cache on repeat calls.
- [ ] `OptionAnalytics` computes IV (from mid) and greeks (δ/γ/θ/ν) via Black-Scholes for chain contracts, independent of any vendor-supplied greeks (D22).
- [ ] Console shows per-position delayed market value and unrealized P&L ($ and %), plus portfolio totals.
- [x] Per-symbol price chart renders (dark-themed) on the console. *(TradingView Lightweight Charts v5 area series in `PriceChart`; a `PRICE HISTORY` panel on the console with symbol-selector chips. Daily bars come from MarketData.app's `stocks/candles` behind the same `MarketDataProvider`/TTL-cache seam — `getDailyBars` + `GET /api/marketdata/history/{symbol}` — resolving OQ-2 by reusing the OQ-1 vendor (no new dependency). Tests: `MarketDataAppProviderTest` (columnar candle parse + recorded-fixture gate), `MarketDataControllerTest`, `PriceChart.test.tsx`, `PortfolioConsole.test.tsx`.)*
- [ ] A daily job stores ATM IV per portfolio underlying into `iv_history`; backfill/accumulation is observable.
- [ ] Graceful handling + visible indicator when data is stale or a symbol can't be priced (no crash, no silent zeros).
- [ ] Tests: provider adapter (against recorded fixtures), cache TTL behavior, P&L math.

---

## Phase 4: Engine — one path end-to-end + config plumbing

**User stories**: UC2 "Pull the lever" (FR-9–FR-16, FR-27–FR-30; decisions D5, D21).

### What to build

The first vertical slice of the recommendation engine: exactly **one matrix cell** working end-to-end (Bullish + Normal IV → Bull Call Debit Spread). `POST /api/engine/run` computes the directional signal from indicators (RSI/MACD/EMA), confirms the NORMAL regime, selects strikes by target delta and an expiry in-window, computes POP/risk-reward, ranks (trivially, one candidate), persists a `Recommendation` stamped with `config_version`, and returns it. The UI renders recommendations as a plain list (no slot machine yet). Crucially, this phase builds the **`EngineConfigProvider`**: `engine_config` table seeded v1 from strategy-matrix §9, read at the start of each run, with version-stamping and bounds-validation, plus delivery of `docs/config-update-runbook.md`.

### Acceptance criteria

- [ ] `engine_config` seeded with v1 from the §9 defaults; engine reads the active config at run start; exactly one active row enforced.
- [ ] Config writes are bounds-validated (deltas ∈ [0,1], risk pct, IV-rank ordering, DTE windows); invalid configs rejected.
- [ ] `POST /api/engine/run` produces a Bull Call Debit Spread recommendation for a bullish+normal-IV underlying, with concrete strikes, expiry, POP, max profit/loss, and a structured rationale.
- [ ] Each persisted recommendation carries the `config_version` that produced it.
- [ ] Indicator computations and the single-cell selection are covered by unit tests (pure functions).
- [ ] UI lists returned recommendations with their rationale; abstain/empty case renders cleanly.
- [ ] `config-update-runbook.md` is present and its inspect/insert/activate/verify steps match the implemented table.

---

## Phase 5: Complete the directional matrix

**User stories**: UC2 "Pull the lever" (FR-9–FR-16; decisions D4, D6, D7).

### What to build

Extend the single-cell engine to every **directional** outcome across the regime grid: Bull Put / Bear Call **credit** spreads (HIGH IV), Bear Put **debit** spread (LOW/NORMAL IV), and **long single-leg** (high conviction + LOW IV). Add the full volatility-regime computation (IV rank with the Bollinger-width proxy fallback), conviction-band strike targeting, and the directional guardrails: liquidity, min-credit (⅓ width), POP floor, reward:risk floor, DTE window, and the per-trade risk cap that drives **contract sizing**. Implement the complete ranking objective (EV × regimeFit × convFactor with tie-breakers) so multiple competing candidates rank correctly.

### Acceptance criteria

- [ ] Each directional cell of the §2 matrix yields the correct structure for representative (direction × regime × conviction) inputs (table-driven unit tests).
- [ ] Volatility regime computed from IV rank, with documented Bollinger-width-percentile fallback when IV history < threshold.
- [ ] Conviction bands shift strike deltas per §4; long single-leg only unlocks at conviction ≥ 80 **and** LOW IV.
- [ ] Guardrails reject non-qualifying candidates with logged reasons (liquidity, min-credit, POP, R:R, DTE).
- [ ] Position sizing caps defined risk to `perTradeRiskPct` of portfolio; oversized single contracts → abstain (`RISK_TOO_LARGE`).
- [ ] Ranking orders multi-candidate runs per the §8 objective; top-N (default 3) surfaced across all underlyings.
- [ ] The four worked examples in strategy-matrix §10 are encoded as regression tests and pass.

---

## Phase 6: Income overlays, neutral/abstain & EOD batch

**User stories**: UC2 "Pull the lever" (FR-9–FR-17; decisions D6–D9, D14).

### What to build

Finish the matrix's non-directional behavior and automate it. Add the income overlays — **Covered Call** (requires ≥100 shares held, sourced from `PositionSource`) and **Cash-Secured Put** (entry suggestion, flagged for required capital) — and the NEUTRAL / abstain logic with all overrides (conviction floor, no-data abstain, earnings flag/avoidance). Then add the **scheduled EOD batch** that runs the full engine across every portfolio underlying after US market close, persisting fresh recommendations and `signal_snapshots`.

### Acceptance criteria

- [ ] Covered Call recommended only when ≥100 shares are held; sized to held shares; clearly labels capped upside.
- [ ] CSP offered as a flagged entry suggestion with required-capital note; never assumes tracked cash.
- [ ] NEUTRAL resolves to income (HIGH IV) or **Abstain** (LOW/NORMAL) per the matrix; all override reasons (`WEAK_SIGNAL`, `NO_VALID_EXPIRY`, `RISK_TOO_LARGE`, no-data) are returned explicitly.
- [ ] Earnings-spanning expiries are avoided or flagged (degrades to UI flag if no earnings calendar available).
- [ ] Scheduled EOD batch runs post-close across all underlyings, persisting recommendations + `signal_snapshots`; observable run record.
- [ ] On-demand lever-pull and the scheduled batch share the same engine path (no divergent logic).
- [ ] Tests cover income eligibility, every abstain path, and batch fan-out over a multi-symbol portfolio.

---

## Phase 7: The Slot Machine

**User stories**: UC2 "Pull the lever" (FR-20, FR-21; decisions D16, D17).

### What to build

Make the recommendation moment delight without touching engine logic. The Slot Machine screen drives `POST /api/engine/run` via a pull-the-lever interaction; Framer-Motion reels spin and resolve onto the ranked recommendations; jackpot/payout animations fire for high-conviction recs (and later, winning paper trades). Each result expands to its structured rationale. The rest of the app stays the readable neon terminal.

### Acceptance criteria

- [ ] Lever-pull triggers the engine run and the reels animate to the returned top-N recommendations.
- [ ] High-conviction recommendations trigger a distinct jackpot/payout animation; abstain/no-result resolves to a clear, non-jarring "no trade" state.
- [ ] Each reel result expands to the full rationale (signals, regime, strikes, POP, risk/reward, sizing).
- [ ] Animations are performant (no dropped frames on a typical run) and reduced-motion preferences are respected.
- [ ] Terminal panels (console, ledger) remain conventional and readable — metaphor confined to the rec moment.

---

## Phase 8: Validation — ledger + backtest

**User stories**: UC3 "Track outcomes" (FR-17–FR-19, FR-22; decisions D8, D14).

### What to build

Close the trust loop. Every recommendation logs to the paper-trade ledger; an EOD settlement job marks open paper trades against current marks and records realized/unrealized P&L. The Ledger screen shows the track record with status (paper/taken) and aggregate stats, filterable by `config_version` so tuning can be compared. "Mark as taken" promotes a recommendation to the real-positions tracker (manual fill price). A directional-signal backtest harness runs the technical signal over historical stock bars and reports hit-rate/return.

### Acceptance criteria

- [ ] Each recommendation creates a paper-trade entry; the EOD settlement job updates open paper trades and records realized/unrealized P&L.
- [ ] Ledger screen lists recommendations with status + outcomes and aggregate track-record stats; can group/filter by `config_version`.
- [ ] "Mark as taken" moves a rec to `taken_positions` with a manually entered fill price; reflected as a real position.
- [ ] `POST /api/backtest/signal` runs the directional signal over historical bars for a symbol and returns hit-rate/return summary.
- [ ] Tests cover settlement math, paper→taken promotion, and the backtest harness on a fixture series.

---

## Phase 9: Polish & ship

**User stories**: UC1 (FR-23, FR-24; decisions D13, D20).

### What to build

Production-ready finish. A single minimal native desktop notification after the EOD batch ("N new recommendations, top conviction: X") that deep-links into the Slot Machine. Unsigned electron-builder packaging (`.dmg`/`.app`) produced by CI. Error/empty states across screens, a "not financial advice / personal tool" disclaimer, and final docs.

### Acceptance criteria

- [ ] One native desktop notification fires after the EOD batch and opens the Slot Machine on click; no other alert noise.
- [ ] CI produces an installable unsigned macOS artifact; the documented first-run Gatekeeper step works.
- [ ] Consistent error and empty states across console, slot machine, and ledger.
- [ ] Disclaimer surfaced in the UI.
- [ ] README/docs updated for build, deploy, config-update, and first-run.

---

## Phase dependency notes

- **P1 → all**: nothing proceeds without the deployed, authenticated spine.
- **P3 feeds P4–P6**: the engine needs delayed chains + accumulating IV history; the IV-rank proxy in P5 exists precisely because P3's history won't be mature at first.
- **P4 establishes config**: every later engine phase reads the versioned config from P4.
- **P7 depends on P4–P6** (something real to spin to); **P8 depends on P4+** (recs to settle/track).
- **Cross-cutting risk to watch from P3 on**: OQ-1 resolved — provider is **MarketData.app** ($0 dev / $12 live), and greeks/IV are computed in-house (D22) so only **chains+quotes** are required. Confirm the chain probe (`spikes/probe-marketdata.mjs`) passes before P4; the `MarketDataProvider` interface keeps any vendor swap (Tradier, Yahoo) localized.
