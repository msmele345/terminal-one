# Terminal One — Default Strategy Matrix & Engine Decision Spec

> Resolves **PRD OQ-5**. This is the build-ready specification for the deterministic recommendation engine (PRD §5.3, FR-9–FR-16).
> **Status:** Default draft for red-line. Every number here is a **tunable parameter** (see §9 config block) — none of it should be hardcoded.
> **Owner:** mitchmele@gmail.com · 2026-06-18

---

## 0. Design philosophy

Three principles drive every rule below:

1. **Trade the regime, not just the direction.** Direction (technicals) decides *which way*; volatility regime (IV rank) decides *whether to buy or sell premium*. A bullish view is a **debit** structure when options are cheap and a **credit** structure when they're rich. This is the single most important idea in the matrix.
2. **Abstaining is a valid, first-class output.** A trustworthy engine says "no compelling trade" when the signal is weak or there's no premium edge. ~Half the cells can resolve to *Abstain*. This is a feature.
3. **Defined risk, sized to the account.** Every recommended structure has a known max loss, and the engine sizes contracts so a single trade risks ≤ a fixed % of the portfolio.

---

## 1. Engine inputs (pinned definitions)

### 1.1 Directional signal → `{direction, conviction}`

Computed on **daily bars** (swing/options horizon). Each indicator emits a vote in `[-1, +1]`; the weighted blend is the **direction score**.

| Indicator | Default params | Vote in `[-1, +1]` |
|-----------|----------------|--------------------|
| **Trend** (EMA crossover) | EMA20 vs EMA50 | `clamp((EMA20 − EMA50) / (0.02 × price), −1, +1)` — sign = trend, magnitude = separation |
| **Momentum** (MACD) | 12 / 26 / 9 | `clamp(histogram / (0.01 × price), −1, +1)` |
| **Oscillator** (RSI) | RSI(14) | `clamp((RSI − 50) / 20, −1, +1)`; if RSI > 70 or < 30, multiply that vote by 0.5 (overbought/oversold caution) |

```
directionScore = 0.40·trendVote + 0.35·macdVote + 0.25·rsiVote      // ∈ [−1, +1]

direction  = BULLISH  if directionScore >  +0.20
             BEARISH  if directionScore <  −0.20
             NEUTRAL  otherwise

conviction = clamp(round(|directionScore| / 0.80 × 100), 0, 100)     // 0–100
```

So `|score| = 0.20` → conviction 25 (below the trade floor); `|score| ≥ 0.80` → conviction 100.

### 1.2 Volatility regime → `{regime}`

**Primary metric: IV Rank** = where current 30-day ATM implied vol sits within its trailing 1-year [min, max].

| IV Rank | Regime | Premium posture |
|---------|--------|-----------------|
| `< 30` | **LOW** | Options cheap → **buy** premium (debit / long) |
| `30 – 60` | **NORMAL** | Default directional → **debit** spreads |
| `> 60` | **HIGH** | Options rich → **sell** premium (credit / income) |

> ⚠️ **Data dependency (ties to PRD OQ-1).** IV Rank needs ~1 year of historical IV, which the delayed/free tier won't have at launch. Mitigation, in priority order:
> 1. From M1, **store ATM IV daily** (computed via Black-Scholes from the chain) so true IV Rank becomes available as history accumulates.
> 2. **Bootstrap proxy** until ≥ 60 trading days of IV history exist: use **Bollinger Band width percentile** (20-day, trailing 1y) as the regime metric, OR an absolute IV cutoff per underlying.
> 3. Corroborate with **ATR(14) percentile** when available.
>
> The regime input depends only on **chains + quotes** from `MarketDataProvider` (MarketData.app, free tier for dev / $12 Starter live), since ATM IV is computed in-house via Black-Scholes (**PRD D22**). The only genuine unknown is accumulating enough IV *history* for a true rank — hence the bootstrap proxy above. Confirm the chain probe (`spikes/probe-marketdata.mjs`) passes before building §3.

---

## 2. Master decision matrix (`direction × regime → strategy`)

| | **LOW IV** (<30) | **NORMAL** (30–60) | **HIGH IV** (>60) |
|---|---|---|---|
| **BULLISH** | Bull Call **Debit** Spread *(Long Call if conviction ≥ 80)* | Bull Call **Debit** Spread | Bull Put **Credit** Spread |
| **BEARISH** | Bear Put **Debit** Spread *(Long Put if conviction ≥ 80)* | Bear Put **Debit** Spread | Bear Call **Credit** Spread |
| **NEUTRAL** | **Abstain** | **Abstain** | **Covered Call** *(if ≥100 shares held)* · else **Cash-Secured Put** *(entry, flagged)* |

**Overrides (applied before the matrix):**
- **Conviction floor:** if `direction ≠ NEUTRAL` **and** `conviction < 40` → **Abstain** on directional structures. (An income overlay on *already-held* shares at HIGH IV is still allowed — it's not a directional bet.)
- **Covered Call eligibility:** only if the portfolio holds ≥ 100 shares of the underlying (PositionSource).
- **CSP eligibility:** always presentable as an *entry* suggestion, but flagged "requires `strike × 100 × contracts` capital" since V1 doesn't track cash balance.
- **No-data abstain:** if liquidity/greeks/IV for the underlying can't be sourced, **Abstain** with a diagnostic reason (never guess).

---

## 3. Conviction bands

Conviction modulates **whether to trade**, **how aggressive the strikes are**, and (indirectly, via max-loss) **size**.

| Band | Conviction | Behavior |
|------|-----------|----------|
| **Abstain** | 0 – 39 | No directional trade. |
| **Standard** | 40 – 59 | Conservative strikes (safer deltas, higher POP). |
| **Confident** | 60 – 79 | Standard strikes. |
| **High** | 80 – 100 | Aggressive strikes; **long single-leg permitted** (only at LOW IV). |

---

## 4. Strike selection (delta targets)

Strikes are chosen by **target delta** off the live chain (nearest listed strike to the target Δ). POP for ranking is computed from Black-Scholes **N(d₂) at the structure's breakeven** (delta is the intuition; N(d₂) is the number used).

### 4.1 Credit spreads (HIGH IV) — sell the short leg by delta
| Conviction band | Short-leg target Δ | Approx POP | Logic |
|---|---|---|---|
| Standard | **0.20Δ** | ~80% | Far OTM, safe, smaller credit |
| Confident | **0.30Δ** | ~70% | The canonical "30-delta" credit spread |
| High | **0.38Δ** | ~62% | Closer to money, more credit, banking on the directional move |

- **Long (protection) leg:** next listed strike that makes the spread **`creditSpreadWidth`** wide (default target **$5**, fallback to nearest available 1–2 strikes for the underlying's increment).

### 4.2 Debit spreads (LOW / NORMAL IV) — buy the long leg by delta
| Conviction band | Long-leg target Δ | Short (cap) leg Δ |
|---|---|---|
| Standard | **0.50Δ** (ATM) | ~0.25Δ |
| Confident | **0.55Δ** | ~0.28Δ |
| High | **0.62Δ** (slightly ITM) | ~0.30Δ |

### 4.3 Long single-leg (High conviction **and** LOW IV only)
- Target **0.65Δ** (slightly ITM → more delta, less extrinsic/theta bleed).

### 4.4 Income overlays (HIGH IV)
- **Covered Call:** sell **0.30Δ** call (default). More bullish lean → 0.20Δ (preserve upside); pure income → 0.35Δ.
- **Cash-Secured Put:** sell **0.30Δ** put (assignment ≈ buying the stock ~30% likelihood, at a discount; else keep premium).

---

## 5. Expiry / DTE selection

Pick the nearest **standard (monthly) expiration** inside the target window.

| Structure | Target DTE | Acceptable window |
|-----------|-----------|-------------------|
| Credit spreads | **30–45** | [25, 50] |
| Income (Covered Call / CSP) | **30–45** | [25, 50] |
| Debit spreads | **45–60** | [35, 70] |
| Long single-leg | **45–60** | [35, 70] |

If no expiry exists in the acceptable window → **Abstain** (reason: `NO_VALID_EXPIRY`).

---

## 6. Guardrails / candidate rejection filters

A candidate must pass **all** of these or it's discarded (with a logged reason):

| Filter | Default threshold | Applies to |
|--------|------------------|-----------|
| **Liquidity — bid/ask** | per-leg spread ≤ **10%** of mid (or ≤ $0.10 absolute) | all |
| **Liquidity — open interest** | each leg OI ≥ **100** | all |
| **Min credit** | credit ≥ **⅓ × width** (e.g., $5 wide → ≥ $1.67) | credit spreads |
| **POP floor** | POP ≥ **65%** | credit spreads / income |
| **Reward:risk floor** | maxProfit / maxLoss ≥ **1.0** | debit spreads / long |
| **Earnings avoidance** | reject/flag if expiry spans the next earnings date | all *(soft — see note)* |
| **Per-trade risk cap** | defined max loss ≤ **3%** of portfolio value | all (drives sizing, §7) |
| **DTE window** | expiry within §5 window | all |

> **Earnings filter note:** requires an earnings calendar. If unavailable from the V1 data tier, downgrade from *reject* to a **UI warning flag** on the recommendation and revisit in V1.5.

---

## 7. Position sizing

Defined-risk structures are sized so one trade risks ≤ the cap:

```
maxLossPerContract = {
  credit spread: (width − credit) × 100
  debit spread / long: premium paid × 100
  CSP: (strike × 100) − premium          // cash-secured
  covered call: n/a (income on held shares; size = held shares / 100)
}

contracts = max(1, floor( (portfolioValue × perTradeRiskPct) / maxLossPerContract ))
```

Default `perTradeRiskPct = 0.03`. If even 1 contract exceeds the cap → **Abstain** (reason: `RISK_TOO_LARGE`) rather than recommend an oversized position.

---

## 8. Ranking / objective function

Each surviving candidate gets a score; the engine surfaces the global **top-N** across all portfolio underlyings (OQ-6 default **N = 3**).

```
rawEV     = POP × maxProfit − (1 − POP) × maxLoss          // first-order expected value ($/contract)

regimeFit = 1.15  if structure matches regime (credit↔HIGH, debit/long↔LOW)
            1.00  if regime is NORMAL
            0.85  if structure is counter-regime

convFactor = 0.5 + 0.5 × (conviction / 100)                // weak signals rank lower

score = rawEV × regimeFit × convFactor
```

- **Primary sort:** `score` descending.
- **Tie-breakers:** higher POP → tighter liquidity (smaller relative bid/ask) → better reward:risk.
- Only candidates with `rawEV > 0` are surfaced as recommendations; positive-but-low-EV ones may still appear if nothing better exists, clearly labeled with their EV.

> `regimeFit` and `convFactor` intentionally bias toward "right structure for the environment, backed by a strong signal," not just raw lottery-ticket EV.

---

## 9. Tunable config (defaults)

Everything above as one config object (becomes `application.yml` / a `EngineConfig` bean — supports unit testing & user red-lining):

```yaml
engine:
  signal:
    ema:   { fast: 20, slow: 50 }
    macd:  { fast: 12, slow: 26, signal: 9 }
    rsi:   { period: 14, overboughtZone: 70, oversoldZone: 30, cautionFactor: 0.5 }
    weights: { trend: 0.40, macd: 0.35, rsi: 0.25 }
    directionThreshold: 0.20
    convictionScale: 0.80
  regime:
    metric: IV_RANK            # IV_RANK | BB_WIDTH_PCTL (bootstrap) | ATR_PCTL
    ivRankLow: 30
    ivRankHigh: 60
    minIvHistoryDays: 60       # below this, fall back to bootstrap metric
  conviction:
    tradeFloor: 40
    bands: { standard: [40,59], confident: [60,79], high: [80,100] }
    longSingleLegMinConviction: 80
  strikes:
    creditShortDelta:  { standard: 0.20, confident: 0.30, high: 0.38 }
    debitLongDelta:    { standard: 0.50, confident: 0.55, high: 0.62 }
    debitShortDelta:   { standard: 0.25, confident: 0.28, high: 0.30 }
    longSingleLegDelta: 0.65
    coveredCallDelta:  0.30
    cspDelta:          0.30
    creditSpreadWidth: 5.00
  expiry:
    creditDteTarget: [30,45]; creditDteWindow: [25,50]
    incomeDteTarget: [30,45]; incomeDteWindow: [25,50]
    debitDteTarget:  [45,60]; debitDteWindow:  [35,70]
  filters:
    maxBidAskPctOfMid: 0.10
    maxBidAskAbsolute: 0.10
    minOpenInterest: 100
    minCreditToWidthRatio: 0.333
    minPopCredit: 0.65
    minRewardRiskDebit: 1.0
    avoidEarnings: true        # degrades to UI flag if no earnings calendar
  sizing:
    perTradeRiskPct: 0.03
  ranking:
    topN: 3
    regimeFitMatch: 1.15
    regimeFitNeutral: 1.00
    regimeFitCounter: 0.85
    requirePositiveEV: true
```

---

## 9.1 Config lifecycle — storage, versioning, and updates (resolves PRD D21)

The §9 object is **not** compiled into the engine. It lives as a **versioned row in Postgres** and is read at the **start of every engine run** via an `EngineConfigProvider`, so changes take effect on the next EOD batch or lever-pull **with no redeploy**.

**Storage model (planned — DDL finalized in M2):**
```
engine_config(
  version      serial primary key,
  config       jsonb        not null,   -- the entire §9 object
  is_active    boolean      not null default false,   -- exactly one true (partial unique index)
  note         text,                     -- why this version exists
  created_at   timestamptz  not null default now()
)
```

**Rules:**
- **Seed:** version 1 is inserted from the §9 YAML defaults at first boot; that YAML remains the immutable **factory-reset baseline**.
- **Immutability:** a config change inserts a **new version** and flips `is_active` — it never overwrites an existing row.
- **Stamping:** every recommendation records the `configVersion` that produced it (PRD FR-14), so the paper-trade ledger can compare *profile v1 vs v2* with evidence. Past recommendations are never retroactively changed.
- **Validation:** values are bounds-checked before activation (deltas ∈ [0,1]; `perTradeRiskPct` ∈ (0, 0.25]; IV-rank cutoffs ordered, ∈ [0,100]; DTE windows positive/ordered). Invalid → rejected, not activated (PRD FR-29).
- **Rollback:** reactivating a prior version is a one-line flip; the next run reverts.

**How you change it in V1:** manually, per the **[`config-update-runbook.md`](./config-update-runbook.md)** (PRD FR-30). **V1.5** replaces the runbook with an in-app **Settings screen** that reads/writes the same versioned table.

---

## 10. Worked examples

**Example A — AAPL · classic high-IV bullish credit spread**
Signal: EMA20>EMA50, MACD hist +, RSI 58 → `directionScore ≈ +0.45` → **BULLISH, conviction 56 (Standard)**. IV Rank 72 → **HIGH**.
→ Matrix: **Bull Put Credit Spread**. Standard band → short put **0.20Δ**. Price $230; 0.20Δ put ≈ $218 strike; $5 width → long $213 put; 38 DTE. Credit $1.80 → maxProfit $180, maxLoss $320, R:R 0.56, POP ~78%. Passes (credit $1.80 ≥ $1.67 = ⅓×$5). EV = 0.78·180 − 0.22·320 = +$70. score = 70 × 1.15 (credit↔HIGH) × (0.5+0.5·0.56=0.78) = **62.8**. Size on a $50k book: $1,500 / $320 → **4 contracts**.

**Example B — TSLA · weak signal → abstain**
Signal: `directionScore ≈ −0.18` → **NEUTRAL** (below ±0.20), conviction 22. No shares held. → **Abstain** (`WEAK_SIGNAL`). The engine recommends nothing and says why.

**Example C — MSFT · neutral high-IV income**
Signal **NEUTRAL**, conviction 30. IV Rank 65 → **HIGH**. Holds 200 shares. → **Covered Call**, sell **0.30Δ** call, 35 DTE, 2 contracts (200 shares / 100). Premium = pure income; flagged "caps upside above strike."

**Example D — NVDA · low-IV high-conviction long**
Signal `+0.86` → **BULLISH, conviction 100 (High)**. IV Rank 21 → **LOW**. → High band + LOW IV unlocks **Long Call** (else would be a debit spread): buy **0.65Δ** call, 52 DTE. maxLoss = premium × 100; sized to the 3% cap.

---

## 11. What this resolves & what remains

✅ **Resolves OQ-5** — direction/regime definitions, the full strategy matrix, conviction bands, delta/DTE targets, guardrails, sizing, and ranking are now concrete and build-ready for M2.

**Still to confirm (your red-line):**
- Any threshold you trade differently (deltas, DTE, the 3% risk cap, IV-rank cutoffs, indicator weights).
- **OQ-1 follow-through:** confirm we can source/compute ATM IV from the delayed chain so IV Rank can accumulate (the regime input depends on it).
- Whether to surface **one structure per underlying** then global top-3 (current default), or multiple alternative structures per underlying.
- Earnings-calendar data source (or accept the UI-flag downgrade for V1).
