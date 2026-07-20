import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LedgerScreen } from './LedgerScreen'
import type { LedgerEntry, LedgerPayload, LedgerStats } from '../../../preload'

function stats(overrides: Partial<LedgerStats> = {}): LedgerStats {
  return {
    totalTrades: 0,
    openTrades: 0,
    settledTrades: 0,
    wins: 0,
    losses: 0,
    hitRate: null,
    realizedPnl: 0,
    unrealizedPnl: 0,
    totalPnl: 0,
    ...overrides
  }
}

function entry(overrides: Partial<LedgerEntry> = {}): LedgerEntry {
  return {
    id: 1,
    recommendationId: 1,
    symbol: 'AAPL',
    strategy: 'BULL_CALL_DEBIT_SPREAD',
    direction: 'BULLISH',
    conviction: 72,
    recommendationStatus: 'PAPER',
    tradeStatus: 'OPEN',
    configVersion: 1,
    expiry: '2026-08-21',
    contracts: 2,
    entryDebit: 3.5,
    markDebit: 4.1,
    unrealizedPnl: 120,
    realizedPnl: 0,
    openedAt: '2026-07-01T20:00:00Z',
    lastMarkedAt: '2026-07-08T20:00:00Z',
    closedAt: null,
    ...overrides
  }
}

function emptyPayload(): LedgerPayload {
  return { entries: [], stats: stats(), configVersion: null, configVersions: [] }
}

let listSpy: ReturnType<typeof vi.fn>
let takeSpy: ReturnType<typeof vi.fn>

function installFakeApi(payload: LedgerPayload): void {
  listSpy = vi.fn(async () => ({ ok: true as const, data: payload }))
  takeSpy = vi.fn(async () => ({ ok: true as const, data: {} }))
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { ledger: { list: listSpy }, recommendations: { take: takeSpy } }
}

describe('LedgerScreen', () => {
  beforeEach(() => {
    installFakeApi(emptyPayload())
  })

  it('shows the empty-state when there are no paper trades', async () => {
    render(<LedgerScreen />)
    const state = await screen.findByTestId('empty-state')
    expect(state).toHaveClass('screen-state', 'screen-state-empty')
    expect(state).toHaveAttribute('role', 'status')
    expect(screen.getByText(/no paper trades yet/i)).toBeInTheDocument()
  })

  it('renders entries with status + outcome fields', async () => {
    const payload: LedgerPayload = {
      entries: [entry()],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    }
    installFakeApi(payload)

    render(<LedgerScreen />)

    const row = await screen.findByTestId('ledger-row')
    expect(within(row).getByText('AAPL')).toBeInTheDocument()
    expect(within(row).getByText('BULL_CALL_DEBIT_SPREAD')).toBeInTheDocument()
    expect(within(row).getByText('PAPER')).toBeInTheDocument()
    expect(within(row).getByText('OPEN')).toBeInTheDocument()
    expect(within(row).getByText('v1')).toBeInTheDocument()
    expect(within(row).getByText('2026-08-21')).toBeInTheDocument()
    expect(within(row).getByText('+$120.00')).toBeInTheDocument()
  })

  it('renders aggregate stats including hit rate, and shows — (not 0%) when hitRate is null', async () => {
    const payload: LedgerPayload = {
      entries: [entry()],
      stats: stats({
        totalTrades: 2,
        openTrades: 1,
        settledTrades: 1,
        wins: 1,
        losses: 0,
        hitRate: null,
        realizedPnl: 950,
        unrealizedPnl: 100,
        totalPnl: 1050
      }),
      configVersion: null,
      configVersions: [1]
    }
    installFakeApi(payload)

    render(<LedgerScreen />)

    const bar = await screen.findByTestId('ledger-stats')
    expect(within(bar).getByText('2')).toBeInTheDocument()
    expect(within(bar).getByText('1 / 0')).toBeInTheDocument()
    expect(within(bar).getByText('—')).toBeInTheDocument()
    expect(within(bar).queryByText('0%')).not.toBeInTheDocument()
    expect(within(bar).queryByText('0.0%')).not.toBeInTheDocument()
    expect(within(bar).getByText('+$950.00')).toBeInTheDocument()
    expect(within(bar).getByText('+$100.00')).toBeInTheDocument()
    expect(within(bar).getByText('+$1,050.00')).toBeInTheDocument()
  })

  it('renders a numeric hit rate as a percentage', async () => {
    const payload: LedgerPayload = {
      entries: [entry()],
      stats: stats({ totalTrades: 2, settledTrades: 2, wins: 1, losses: 1, hitRate: 0.5 }),
      configVersion: null,
      configVersions: [1]
    }
    installFakeApi(payload)

    render(<LedgerScreen />)

    const bar = await screen.findByTestId('ledger-stats')
    expect(within(bar).getByText('50.0%')).toBeInTheDocument()
  })

  it('renders an em-dash when markDebit is null', async () => {
    const payload: LedgerPayload = {
      entries: [entry({ markDebit: null })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    }
    installFakeApi(payload)

    render(<LedgerScreen />)

    const row = await screen.findByTestId('ledger-row')
    expect(within(row).getByText('—')).toBeInTheDocument()
  })

  it('renders an error state without crashing', async () => {
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { ledger: { list: vi.fn(async () => ({ ok: false as const, error: 'Request failed (500)' })) } }

    render(<LedgerScreen />)

    const state = await screen.findByRole('alert')
    expect(state).toHaveClass('screen-state', 'screen-state-error')
    expect(within(state).getByText('Ledger unavailable')).toBeInTheDocument()
    expect(within(state).getByText('Request failed (500)')).toBeInTheDocument()
    expect(within(state).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('re-fetches with the selected config version and re-renders the filtered payload', async () => {
    const user = userEvent.setup()
    const initial: LedgerPayload = {
      entries: [entry({ id: 1, configVersion: 1 })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1, 2]
    }
    const filtered: LedgerPayload = {
      entries: [entry({ id: 2, configVersion: 2, symbol: 'TSLA' })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: 2,
      configVersions: [1, 2]
    }

    let call = 0
    listSpy = vi.fn(async () => {
      call += 1
      return { ok: true as const, data: call === 1 ? initial : filtered }
    })
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { ledger: { list: listSpy } }

    render(<LedgerScreen />)

    await screen.findByTestId('ledger-row')

    const select = screen.getByLabelText('Config version')
    await user.selectOptions(select, 'v2')

    await waitFor(() => expect(listSpy).toHaveBeenCalledWith(2))
    await waitFor(() => expect(screen.getByText('TSLA')).toBeInTheDocument())
  })

  it('marks a paper recommendation as taken with a manual fill price, then re-loads', async () => {
    const user = userEvent.setup()
    const paper: LedgerPayload = {
      entries: [entry({ id: 1, recommendationId: 42, recommendationStatus: 'PAPER', entryDebit: 3.5 })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    }
    const afterTake: LedgerPayload = {
      entries: [entry({ id: 1, recommendationId: 42, recommendationStatus: 'TAKEN', entryDebit: 3.5 })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    }

    let call = 0
    listSpy = vi.fn(async () => {
      call += 1
      return { ok: true as const, data: call === 1 ? paper : afterTake }
    })
    takeSpy = vi.fn(async () => ({ ok: true as const, data: {} }))
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { ledger: { list: listSpy }, recommendations: { take: takeSpy } }

    render(<LedgerScreen />)

    await screen.findByTestId('ledger-row')
    await user.click(screen.getByRole('button', { name: /mark taken/i }))

    const input = screen.getByLabelText('Fill price for AAPL')
    await user.clear(input)
    await user.type(input, '3.65')
    await user.click(screen.getByRole('button', { name: /confirm/i }))

    await waitFor(() => expect(takeSpy).toHaveBeenCalledWith(42, 3.65))
    // Reloaded ledger now shows TAKEN and the take control is gone.
    await waitFor(() => expect(screen.getByText('TAKEN')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /mark taken/i })).toBeNull()
  })

  it('surfaces an error when the take fails and keeps the row as PAPER', async () => {
    const user = userEvent.setup()
    installFakeApi({
      entries: [entry({ recommendationId: 7, recommendationStatus: 'PAPER' })],
      stats: stats({ totalTrades: 1, openTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    })
    takeSpy = vi.fn(async () => ({ ok: false as const, error: 'Recommendation 7 has already been taken' }))
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { ledger: { list: listSpy }, recommendations: { take: takeSpy } }

    render(<LedgerScreen />)

    await screen.findByTestId('ledger-row')
    await user.click(screen.getByRole('button', { name: /mark taken/i }))
    await user.click(screen.getByRole('button', { name: /confirm/i }))

    expect(await screen.findByText(/already been taken/i)).toBeInTheDocument()
  })

  it('does not offer a take action on an already-taken recommendation', async () => {
    installFakeApi({
      entries: [entry({ recommendationStatus: 'TAKEN' })],
      stats: stats({ totalTrades: 1, settledTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    })

    render(<LedgerScreen />)

    await screen.findByTestId('ledger-row')
    expect(screen.queryByRole('button', { name: /mark taken/i })).toBeNull()
    expect(screen.getByText('Taken')).toBeInTheDocument()
  })

  it('hides the config-version filter when 0-1 versions are present', async () => {
    installFakeApi({
      entries: [entry()],
      stats: stats({ totalTrades: 1 }),
      configVersion: null,
      configVersions: [1]
    })

    render(<LedgerScreen />)

    await screen.findByTestId('ledger-row')
    expect(screen.queryByLabelText('Config version')).toBeNull()
  })
})
