import { useState } from 'react'
import type { Recommendation, RecommendationRationale } from '../../../preload'

type RunState = 'idle' | 'running' | 'done' | 'error'

// Phase 4: the lever-pull as a plain list (the slot machine arrives in Phase 7).
// Runs the deterministic engine over the portfolio and renders each returned
// recommendation with its structured rationale; abstain/empty renders cleanly.
export function RecommendationsPanel(): JSX.Element {
  const [state, setState] = useState<RunState>('idle')
  const [recs, setRecs] = useState<Recommendation[]>([])
  const [error, setError] = useState<string | null>(null)

  const run = async (): Promise<void> => {
    setState('running')
    setError(null)
    const res = await window.api.engine.run()
    if (res.ok) {
      setRecs(res.data.recommendations)
      setState('done')
    } else {
      setError(res.error)
      setState('error')
    }
  }

  return (
    <section className="console engine-console" data-testid="recommendations">
      <div className="console-bar">
        <h1 className="console-title">RECOMMENDATIONS</h1>
        <div className="console-actions">
          <button className="primary-btn inline" onClick={run} disabled={state === 'running'}>
            {state === 'running' ? 'Running…' : 'Run engine'}
          </button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      {state === 'idle' && (
        <p className="muted">
          Pull the lever to run the deterministic engine over your portfolio and surface
          defined-risk options recommendations.
        </p>
      )}

      {state === 'running' && <p className="muted">Running the engine…</p>}

      {state === 'done' && recs.length === 0 && (
        <div className="empty-state" data-testid="engine-empty">
          <p className="empty-glyph">✧</p>
          <p>No trade.</p>
          <p className="muted">
            The engine abstained — no qualifying setup across your portfolio right now.
          </p>
        </div>
      )}

      {state === 'done' && recs.length > 0 && (
        <div className="rec-list">
          {recs.map((r) => (
            <RecommendationCard key={r.id} rec={r} />
          ))}
        </div>
      )}
    </section>
  )
}

function RecommendationCard({ rec }: { rec: Recommendation }): JSX.Element {
  const [open, setOpen] = useState(false)
  return (
    <article className="panel rec-card" data-testid="recommendation">
      <div className="rec-head">
        <div className="rec-title">
          <span className="neon rec-symbol">{rec.symbol}</span>
          <span className="rec-strategy">{strategyLabel(rec.strategy)}</span>
          <span className="tag">
            {rec.direction} · {rec.regime} IV
          </span>
        </div>
        <div className="rec-conviction">
          <span className="stat-label">Conviction</span>
          <span className="stat-value neon">{rec.conviction}</span>
        </div>
      </div>

      <div className="rec-stats">
        <Metric label="Expiry" value={rec.expiry} />
        <Metric label="Entry debit" value={money(rec.entryDebit)} />
        <Metric label="POP" value={pct(rec.probabilityOfProfit)} />
        <Metric label="Max profit" value={money(rec.maxProfit)} className="gain" />
        <Metric label="Max loss" value={money(rec.maxLoss)} className="loss" />
        <Metric label="Reward : risk" value={`${rec.riskReward.toFixed(2)} : 1`} />
      </div>

      <ul className="rec-legs">
        {rec.legs.map((leg) => (
          <li key={leg.optionSymbol} className="rec-leg">
            <span className={`leg-action ${leg.action === 'BUY' ? 'buy' : 'sell'}`}>{leg.action}</span>
            <span className="leg-contract">
              {leg.strike.toFixed(2)} {leg.callPut}
            </span>
            <span className="muted">Δ {leg.delta.toFixed(2)}</span>
            <span className="muted">@ {money(leg.mid)}</span>
          </li>
        ))}
      </ul>

      <button
        className="link-btn rec-why"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {open ? 'Hide rationale' : 'Why this trade?'}
      </button>

      {open && <Rationale rationale={rec.rationale} />}
    </article>
  )
}

function Rationale({ rationale }: { rationale: RecommendationRationale }): JSX.Element {
  const { signals, regime, selection, pricing } = rationale
  return (
    <div className="rationale" data-testid="rationale">
      <RationaleGroup title="Signal">
        <Metric label="Direction score" value={signals.directionScore.toFixed(2)} />
        <Metric label="Conviction" value={String(signals.conviction)} />
        <Metric label="RSI" value={signals.rsi.toFixed(1)} />
        <Metric label="MACD hist" value={signals.macdHistogram.toFixed(2)} />
        <Metric
          label="EMA fast / slow"
          value={`${signals.emaFast.toFixed(2)} / ${signals.emaSlow.toFixed(2)}`}
        />
      </RationaleGroup>

      <RationaleGroup title="Regime">
        <Metric label="Volatility" value={`${regime.value} IV`} />
        {regime.currentIv != null && <Metric label="Current IV" value={pct(regime.currentIv)} />}
        <p className="muted rationale-reason">{regime.reason}</p>
      </RationaleGroup>

      <RationaleGroup title="Selection">
        <Metric label="Conviction band" value={selection.convictionBand} />
        <Metric label="DTE" value={String(selection.dte)} />
        <Metric
          label="Long Δ target / actual"
          value={`${selection.longDeltaTarget.toFixed(2)} / ${selection.selectedLongDelta.toFixed(2)}`}
        />
        <Metric
          label="Short Δ target / actual"
          value={`${selection.shortDeltaTarget.toFixed(2)} / ${selection.selectedShortDelta.toFixed(2)}`}
        />
      </RationaleGroup>

      <RationaleGroup title="Pricing">
        <Metric label="Spread width" value={money(pricing.width)} />
        <Metric label="Breakeven" value={money(pricing.breakeven)} />
        <Metric label="Expected value" value={money(pricing.rawExpectedValue)} />
      </RationaleGroup>
    </div>
  )
}

function RationaleGroup({
  title,
  children
}: {
  title: string
  children: React.ReactNode
}): JSX.Element {
  return (
    <div className="rationale-group">
      <h3 className="rationale-title">{title}</h3>
      <div className="rationale-metrics">{children}</div>
    </div>
  )
}

function Metric({
  label,
  value,
  className
}: {
  label: string
  value: string
  className?: string
}): JSX.Element {
  return (
    <div className="metric">
      <span className="metric-label">{label}</span>
      <span className={`metric-value ${className ?? ''}`}>{value}</span>
    </div>
  )
}

// ---- formatting ----

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })

function money(v: number): string {
  return MONEY.format(v)
}

// Probabilities/IVs arrive as fractions (0.58 → "58%").
function pct(fraction: number): string {
  return `${(fraction * 100).toFixed(0)}%`
}

function strategyLabel(strategy: string): string {
  return strategy
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
