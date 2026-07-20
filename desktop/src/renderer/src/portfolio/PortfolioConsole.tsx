import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type {
  OptionSummary,
  PortfolioSummary,
  PortfolioTotals,
  Position,
  StockSummary
} from '../../../preload'
import { PositionModal } from './PositionModal'
import { PriceChart } from './PriceChart'
import { ScreenState } from '../ui/ScreenState'

type Editing = { mode: 'create' } | { mode: 'edit'; position: Position } | null

export function PortfolioConsole(): JSX.Element {
  const [data, setData] = useState<PortfolioSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Editing>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  // Distinct chartable symbols: stock tickers + option underlyings, in order.
  const symbols = useMemo(() => {
    if (data == null) return []
    return [...new Set([...data.stocks.map((s) => s.symbol), ...data.options.map((o) => o.underlying)])]
  }, [data])

  // Default the chart to the first symbol; keep the selection valid as positions change.
  useEffect(() => {
    if (symbols.length === 0) {
      if (selectedSymbol !== null) setSelectedSymbol(null)
    } else if (selectedSymbol == null || !symbols.includes(selectedSymbol)) {
      setSelectedSymbol(symbols[0])
    }
  }, [symbols, selectedSymbol])

  const load = useCallback(async () => {
    const res = await window.api.positions.summary()
    if (res.ok) {
      setData(res.data)
      setError(null)
    } else {
      setError(res.error)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const remove = async (p: Position): Promise<void> => {
    const res = await window.api.positions.remove(p.id, p.kind)
    if (res.ok) {
      await load()
    } else {
      setError(res.error)
    }
  }

  const onImport = async (e: React.ChangeEvent<HTMLInputElement>): Promise<void> => {
    const file = e.target.files?.[0]
    if (!file) return
    const csv = await file.text()
    e.target.value = '' // allow re-importing the same file
    const res = await window.api.positions.import(csv)
    if (res.ok) {
      const { importedStocks, importedOptions, errors } = res.data
      setNotice(
        `Imported ${importedStocks} stock + ${importedOptions} option row(s)` +
          (errors.length ? ` · ${errors.length} row(s) rejected: ${describeErrors(errors)}` : '')
      )
      await load()
    } else {
      setError(res.error)
    }
  }

  const isEmpty = data != null && data.stocks.length === 0 && data.options.length === 0

  return (
    <section className="console">
      <div className="console-bar">
        <h1 className="console-title">PORTFOLIO&nbsp;CONSOLE</h1>
        <div className="console-actions">
          <button className="ghost-btn" onClick={() => fileInput.current?.click()}>
            Import CSV
          </button>
          <input
            ref={fileInput}
            type="file"
            accept=".csv,text/csv"
            data-testid="csv-input"
            hidden
            onChange={onImport}
          />
          <button className="primary-btn inline" onClick={() => setEditing({ mode: 'create' })}>
            Add position
          </button>
        </div>
      </div>

      {error && (
        <ScreenState kind="error" title="Portfolio unavailable" detail={error}>
          <button type="button" className="ghost-btn" onClick={() => void load()}>
            Try again
          </button>
        </ScreenState>
      )}
      {notice && <p className="muted notice">{notice}</p>}

      {data == null && !error && <p className="muted">Loading positions…</p>}

      {isEmpty && !error && (
        <ScreenState
          kind="empty"
          title="No positions yet."
          detail="Add one manually or import a CSV to populate the console."
          testId="empty-state"
        />
      )}

      {data != null && !isEmpty && (
        <>
          <TotalsBar totals={data.totals} delayed={data.delayed} asOf={data.asOf} unpriced={data.unpriced} />
          <div className="tables">
            {data.stocks.length > 0 && (
              <StockTable
                rows={data.stocks}
                onEdit={(p) => setEditing({ mode: 'edit', position: p })}
                onDelete={remove}
              />
            )}
            {data.options.length > 0 && (
              <OptionTable
                rows={data.options}
                onEdit={(p) => setEditing({ mode: 'edit', position: p })}
                onDelete={remove}
              />
            )}
          </div>
          {symbols.length > 0 && selectedSymbol && (
            <ChartPanel
              symbols={symbols}
              selected={selectedSymbol}
              onSelect={setSelectedSymbol}
            />
          )}
        </>
      )}

      {editing && (
        <PositionModal
          initial={editing.mode === 'edit' ? editing.position : undefined}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await load()
          }}
          onError={setError}
        />
      )}
    </section>
  )
}

function describeErrors(errors: { line: number; message: string }[]): string {
  return errors.map((e) => `line ${e.line} (${e.message})`).join('; ')
}

function ChartPanel({
  symbols,
  selected,
  onSelect
}: {
  symbols: string[]
  selected: string
  onSelect: (symbol: string) => void
}): JSX.Element {
  return (
    <div className="panel chart-panel">
      <div className="panel-head">
        <h2 className="panel-title">PRICE&nbsp;HISTORY</h2>
        <div className="symbol-chips" role="tablist" aria-label="Chart symbol">
          {symbols.map((s) => (
            <button
              key={s}
              role="tab"
              aria-selected={s === selected}
              className={`chip ${s === selected ? 'chip-active' : ''}`}
              onClick={() => onSelect(s)}
            >
              {s}
            </button>
          ))}
        </div>
      </div>
      <PriceChart symbol={selected} />
    </div>
  )
}

// ---- formatting ----

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const SIGNED_MONEY = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  signDisplay: 'exceptZero'
})

const DASH = '—'

function money(v: number | null): string {
  return v == null ? DASH : MONEY.format(v)
}

function signedMoney(v: number | null): string {
  return v == null ? DASH : SIGNED_MONEY.format(v)
}

function percent(v: number | null): string {
  if (v == null) return DASH
  const sign = v > 0 ? '+' : ''
  return `${sign}${v.toFixed(2)}%`
}

// Sign-driven neon class: green gains, red losses, muted/zero otherwise.
function pnlClass(v: number | null): string {
  if (v == null) return 'muted'
  if (v > 0) return 'gain'
  if (v < 0) return 'loss'
  return ''
}

function TotalsBar({
  totals,
  delayed,
  asOf,
  unpriced
}: {
  totals: PortfolioTotals
  delayed: boolean
  asOf: string | null
  unpriced: number
}): JSX.Element {
  return (
    <div className="totals-bar" data-testid="totals-bar">
      <Stat label="Cost basis" value={money(totals.costValue)} />
      <Stat label="Market value" value={money(totals.marketValue)} />
      <Stat
        label="Unrealized P&L"
        value={signedMoney(totals.unrealizedPnl)}
        className={pnlClass(totals.unrealizedPnl)}
      />
      <Stat
        label="Return"
        value={percent(totals.unrealizedPnlPct)}
        className={pnlClass(totals.unrealizedPnlPct)}
      />
      <div className="totals-meta">
        {delayed && <span className="tag delayed">DELAYED</span>}
        {asOf && <span className="muted">as of {new Date(asOf).toLocaleString()}</span>}
        {unpriced > 0 && <span className="tag warn">{unpriced} unpriced</span>}
      </div>
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

function StockTable({
  rows,
  onEdit,
  onDelete
}: {
  rows: StockSummary[]
  onEdit: (p: StockSummary) => void
  onDelete: (p: StockSummary) => void
}): JSX.Element {
  return (
    <div className="panel">
      <h2 className="panel-title">STOCKS</h2>
      <table className="grid">
        <thead>
          <tr>
            <th>Symbol</th>
            <th className="num">Qty</th>
            <th className="num">Cost basis</th>
            <th className="num">Last</th>
            <th className="num">Mkt value</th>
            <th className="num">P&L</th>
            <th className="num">P&L %</th>
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={`s-${p.id}`} data-testid="stock-row" className={p.priced ? '' : 'unpriced'}>
              <td className="neon">{p.symbol}</td>
              <td className="num">{p.quantity}</td>
              <td className="num">{money(p.costBasis)}</td>
              <td className="num">{money(p.markPrice)}</td>
              <td className="num">{money(p.marketValue)}</td>
              <td className={`num ${pnlClass(p.unrealizedPnl)}`}>{signedMoney(p.unrealizedPnl)}</td>
              <td className={`num ${pnlClass(p.unrealizedPnlPct)}`}>{percent(p.unrealizedPnlPct)}</td>
              <td className="row-actions">
                <button className="link-btn" onClick={() => onEdit(p)}>
                  Edit
                </button>
                <button className="link-btn danger" onClick={() => onDelete(p)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function OptionTable({
  rows,
  onEdit,
  onDelete
}: {
  rows: OptionSummary[]
  onEdit: (p: OptionSummary) => void
  onDelete: (p: OptionSummary) => void
}): JSX.Element {
  return (
    <div className="panel">
      <h2 className="panel-title">OPTIONS</h2>
      <table className="grid">
        <thead>
          <tr>
            <th>Underlying</th>
            <th>Type</th>
            <th>Side</th>
            <th className="num">Strike</th>
            <th>Expiry</th>
            <th className="num">Qty</th>
            <th className="num">Premium</th>
            <th className="num">Mark</th>
            <th className="num">Mkt value</th>
            <th className="num">P&L</th>
            <th className="num">P&L %</th>
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={`o-${p.id}`} data-testid="option-row" className={p.priced ? '' : 'unpriced'}>
              <td className="neon">{p.underlying}</td>
              <td>{p.optionType}</td>
              <td>{p.side}</td>
              <td className="num">{money(p.strike)}</td>
              <td>{p.expiry}</td>
              <td className="num">{p.quantity}</td>
              <td className="num">{money(p.costBasis)}</td>
              <td className="num">{money(p.markPrice)}</td>
              <td className="num">{money(p.marketValue)}</td>
              <td className={`num ${pnlClass(p.unrealizedPnl)}`}>{signedMoney(p.unrealizedPnl)}</td>
              <td className={`num ${pnlClass(p.unrealizedPnlPct)}`}>{percent(p.unrealizedPnlPct)}</td>
              <td className="row-actions">
                <button className="link-btn" onClick={() => onEdit(p)}>
                  Edit
                </button>
                <button className="link-btn danger" onClick={() => onDelete(p)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
