import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  OptionPosition,
  Position,
  PositionsPayload,
  StockPosition
} from '../../../preload'
import { PositionModal } from './PositionModal'

type Editing = { mode: 'create' } | { mode: 'edit'; position: Position } | null

export function PortfolioConsole(): JSX.Element {
  const [data, setData] = useState<PositionsPayload | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Editing>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    const res = await window.api.positions.list()
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

      {error && <p className="error">{error}</p>}
      {notice && <p className="muted notice">{notice}</p>}

      {data == null && <p className="muted">Loading positions…</p>}

      {isEmpty && (
        <div className="empty-state" data-testid="empty-state">
          <p className="empty-glyph">▦</p>
          <p>No positions yet.</p>
          <p className="muted">Add one manually or import a CSV to populate the console.</p>
        </div>
      )}

      {data != null && !isEmpty && (
        <div className="tables">
          {data.stocks.length > 0 && (
            <StockTable rows={data.stocks} onEdit={(p) => setEditing({ mode: 'edit', position: p })} onDelete={remove} />
          )}
          {data.options.length > 0 && (
            <OptionTable rows={data.options} onEdit={(p) => setEditing({ mode: 'edit', position: p })} onDelete={remove} />
          )}
        </div>
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

function StockTable({
  rows,
  onEdit,
  onDelete
}: {
  rows: StockPosition[]
  onEdit: (p: StockPosition) => void
  onDelete: (p: StockPosition) => void
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
            <th>Opened</th>
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={`s-${p.id}`} data-testid="stock-row">
              <td className="neon">{p.symbol}</td>
              <td className="num">{p.quantity}</td>
              <td className="num">${p.costBasis}</td>
              <td>{p.openedDate}</td>
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
  rows: OptionPosition[]
  onEdit: (p: OptionPosition) => void
  onDelete: (p: OptionPosition) => void
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
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={`o-${p.id}`} data-testid="option-row">
              <td className="neon">{p.underlying}</td>
              <td>{p.optionType}</td>
              <td>{p.side}</td>
              <td className="num">${p.strike}</td>
              <td>{p.expiry}</td>
              <td className="num">{p.quantity}</td>
              <td className="num">${p.costBasis}</td>
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
