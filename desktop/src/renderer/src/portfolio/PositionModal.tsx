import { useState } from 'react'
import type { Position, PositionRequest } from '../../../preload'

type Kind = 'STOCK' | 'OPTION'

interface PositionModalProps {
  initial?: Position
  onClose: () => void
  onSaved: () => void | Promise<void>
  onError: (message: string) => void
}

// Add/edit a stock or option position. In edit mode the kind is fixed; in create
// mode the operator toggles between the two and the option-only fields appear.
export function PositionModal({ initial, onClose, onSaved, onError }: PositionModalProps): JSX.Element {
  const editing = initial != null
  const [kind, setKind] = useState<Kind>(initial?.kind ?? 'STOCK')
  const [busy, setBusy] = useState(false)

  const [symbol, setSymbol] = useState(
    initial == null ? '' : initial.kind === 'STOCK' ? initial.symbol : initial.underlying
  )
  const [quantity, setQuantity] = useState(initial ? String(initial.quantity) : '')
  const [costBasis, setCostBasis] = useState(initial ? String(initial.costBasis) : '')
  const [openedDate, setOpenedDate] = useState(
    initial?.kind === 'STOCK' ? initial.openedDate : (initial?.openedDate ?? '')
  )
  const [optionType, setOptionType] = useState<'CALL' | 'PUT'>(
    initial?.kind === 'OPTION' ? initial.optionType : 'CALL'
  )
  const [strike, setStrike] = useState(initial?.kind === 'OPTION' ? String(initial.strike) : '')
  const [expiry, setExpiry] = useState(initial?.kind === 'OPTION' ? initial.expiry : '')
  const [side, setSide] = useState<'LONG' | 'SHORT'>(
    initial?.kind === 'OPTION' ? initial.side : 'LONG'
  )

  const submit = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault()
    setBusy(true)
    const request: PositionRequest =
      kind === 'STOCK'
        ? {
            kind,
            symbol,
            quantity: Number(quantity),
            costBasis: Number(costBasis),
            openedDate
          }
        : {
            kind,
            symbol,
            quantity: Number(quantity),
            costBasis: Number(costBasis),
            openedDate: openedDate || null,
            optionType,
            strike: Number(strike),
            expiry,
            side
          }

    const res =
      editing && initial != null
        ? await window.api.positions.update(initial.id, request)
        : await window.api.positions.create(request)
    setBusy(false)
    if (res.ok) {
      await onSaved()
    } else {
      onError(res.error)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="card modal" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h1 className="card-title">{editing ? 'EDIT POSITION' : 'NEW POSITION'}</h1>

        {!editing && (
          <div className="kind-toggle" role="tablist" aria-label="Position kind">
            <button
              type="button"
              role="tab"
              aria-selected={kind === 'STOCK'}
              className={kind === 'STOCK' ? 'toggle on' : 'toggle'}
              onClick={() => setKind('STOCK')}
            >
              Stock
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={kind === 'OPTION'}
              className={kind === 'OPTION' ? 'toggle on' : 'toggle'}
              onClick={() => setKind('OPTION')}
            >
              Option
            </button>
          </div>
        )}

        <label className="field">
          <span>{kind === 'STOCK' ? 'Symbol' : 'Underlying'}</span>
          <input autoFocus value={symbol} onChange={(e) => setSymbol(e.target.value)} required />
        </label>

        {kind === 'OPTION' && (
          <>
            <div className="field-row">
              <label className="field">
                <span>Type</span>
                <select value={optionType} onChange={(e) => setOptionType(e.target.value as 'CALL' | 'PUT')}>
                  <option value="CALL">Call</option>
                  <option value="PUT">Put</option>
                </select>
              </label>
              <label className="field">
                <span>Side</span>
                <select value={side} onChange={(e) => setSide(e.target.value as 'LONG' | 'SHORT')}>
                  <option value="LONG">Long</option>
                  <option value="SHORT">Short</option>
                </select>
              </label>
            </div>
            <div className="field-row">
              <label className="field">
                <span>Strike</span>
                <input
                  type="number"
                  step="any"
                  value={strike}
                  onChange={(e) => setStrike(e.target.value)}
                  required
                />
              </label>
              <label className="field">
                <span>Expiry</span>
                <input type="date" value={expiry} onChange={(e) => setExpiry(e.target.value)} required />
              </label>
            </div>
          </>
        )}

        <div className="field-row">
          <label className="field">
            <span>{kind === 'STOCK' ? 'Quantity (shares)' : 'Quantity (contracts)'}</span>
            <input
              type="number"
              step="any"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{kind === 'STOCK' ? 'Cost basis / share' : 'Premium / contract'}</span>
            <input
              type="number"
              step="any"
              value={costBasis}
              onChange={(e) => setCostBasis(e.target.value)}
              required
            />
          </label>
        </div>

        <label className="field">
          <span>Opened date{kind === 'OPTION' ? ' (optional)' : ''}</span>
          <input
            type="date"
            value={openedDate ?? ''}
            onChange={(e) => setOpenedDate(e.target.value)}
            required={kind === 'STOCK'}
          />
        </label>

        <div className="modal-actions">
          <button type="button" className="ghost-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary-btn inline" disabled={busy}>
            {busy ? 'Saving…' : editing ? 'Save' : 'Add'}
          </button>
        </div>
      </form>
    </div>
  )
}
