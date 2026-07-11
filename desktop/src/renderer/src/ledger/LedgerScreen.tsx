import { useCallback, useEffect, useState } from 'react'
import type { LedgerEntry, LedgerPayload, LedgerStats } from '../../../preload'

// Phase 8 AC2: the paper-trade track record. A conventional terminal panel —
// no slot-machine/casino styling, no framer-motion (Phase 7 AC5 confines the
// metaphor to the recommendation moment). Purely presentational + fetch; the
// backend (`GET /api/ledger`) owns all settlement/stats math.
export function LedgerScreen(): JSX.Element {
  const [data, setData] = useState<LedgerPayload | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [configVersion, setConfigVersion] = useState<number | undefined>(undefined)

  const load = useCallback(async (version?: number) => {
    const res = await window.api.ledger.list(version)
    if (res.ok) {
      setData(res.data)
      setError(null)
    } else {
      setError(res.error)
    }
  }, [])

  useEffect(() => {
    void load(configVersion)
  }, [load, configVersion])

  const onFilterChange = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    const value = e.target.value
    setConfigVersion(value === '' ? undefined : Number(value))
  }

  const isEmpty = data != null && data.entries.length === 0

  return (
    <section className="console">
      <div className="console-bar">
        <h1 className="console-title">LEDGER</h1>
        {data != null && data.configVersions.length > 1 && (
          <label className="field ledger-filter">
            <span>Config version</span>
            <select value={configVersion ?? ''} onChange={onFilterChange}>
              <option value="">All versions</option>
              {data.configVersions.map((v) => (
                <option key={v} value={v}>
                  v{v}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      {data == null && !error && <p className="muted">Loading ledger…</p>}

      {isEmpty && (
        <div className="empty-state" data-testid="empty-state">
          <p className="empty-glyph">▦</p>
          <p>No paper trades yet — pull the lever to build a track record.</p>
        </div>
      )}

      {data != null && !isEmpty && (
        <>
          <StatsBar stats={data.stats} />
          <EntriesTable rows={data.entries} />
        </>
      )}
    </section>
  )
}

function StatsBar({ stats }: { stats: LedgerStats }): JSX.Element {
  return (
    <div className="totals-bar" data-testid="ledger-stats">
      <Stat label="Total trades" value={String(stats.totalTrades)} />
      <Stat label="Open" value={String(stats.openTrades)} />
      <Stat label="Settled" value={String(stats.settledTrades)} />
      <Stat label="Wins / Losses" value={`${stats.wins} / ${stats.losses}`} />
      {/* Neutral color: any positive hit rate isn't a "gain" — 10% would read green. */}
      <Stat
        label="Hit rate"
        value={percent(stats.hitRate)}
        className={stats.hitRate == null ? 'muted' : ''}
      />
      <Stat
        label="Realized P&L"
        value={signedMoney(stats.realizedPnl)}
        className={pnlClass(stats.realizedPnl)}
      />
      <Stat
        label="Unrealized P&L"
        value={signedMoney(stats.unrealizedPnl)}
        className={pnlClass(stats.unrealizedPnl)}
      />
      <Stat
        label="Total P&L"
        value={signedMoney(stats.totalPnl)}
        className={pnlClass(stats.totalPnl)}
      />
    </div>
  )
}

function Stat({
  label,
  value,
  className
}: {
  label: string
  value: string
  className?: string
}): JSX.Element {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className={`stat-value ${className ?? ''}`}>{value}</span>
    </div>
  )
}

function EntriesTable({ rows }: { rows: LedgerEntry[] }): JSX.Element {
  return (
    <div className="panel">
      <h2 className="panel-title">PAPER TRADES</h2>
      <table className="grid">
        <thead>
          <tr>
            <th>Symbol</th>
            <th>Strategy</th>
            <th>Status</th>
            <th>Trade</th>
            <th>Config</th>
            <th>Expiry</th>
            <th className="num">Contracts</th>
            <th className="num">Entry</th>
            <th className="num">Mark</th>
            <th className="num">Unrealized P&L</th>
            <th className="num">Realized P&L</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((e) => (
            <tr key={e.id} data-testid="ledger-row">
              <td className="neon">{e.symbol}</td>
              <td>{e.strategy}</td>
              <td>{e.recommendationStatus}</td>
              <td>{e.tradeStatus}</td>
              <td>v{e.configVersion}</td>
              <td>{e.expiry}</td>
              <td className="num">{e.contracts}</td>
              <td className="num">{signedMoney(e.entryDebit)}</td>
              <td className="num">{signedMoney(e.markDebit)}</td>
              <td className={`num ${pnlClass(e.unrealizedPnl)}`}>{signedMoney(e.unrealizedPnl)}</td>
              <td className={`num ${pnlClass(e.realizedPnl)}`}>{signedMoney(e.realizedPnl)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ---- formatting ----

const SIGNED_MONEY = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  signDisplay: 'exceptZero'
})

const DASH = '—'

function signedMoney(v: number | null): string {
  return v == null ? DASH : SIGNED_MONEY.format(v)
}

// hitRate is a 0..1 fraction from the backend; render as a percentage, never
// 0% when it's actually null (no trade has settled yet).
function percent(v: number | null): string {
  if (v == null) return DASH
  return `${(v * 100).toFixed(1)}%`
}

// Sign-driven neon class: green gains, red losses, muted/zero otherwise.
function pnlClass(v: number | null): string {
  if (v == null) return 'muted'
  if (v > 0) return 'gain'
  if (v < 0) return 'loss'
  return ''
}
