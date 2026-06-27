import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PortfolioConsole } from './PortfolioConsole'
import type { PortfolioSummary, PositionRequest, StockSummary } from '../../../preload'

function emptyTotals(): PortfolioSummary['totals'] {
  return { costValue: 0, marketValue: 0, unrealizedPnl: 0, unrealizedPnlPct: null }
}

// A stateful in-memory fake of the main-process position API. The console talks
// only to window.api, so this fully exercises the add -> summary path without IPC.
function installFakeApi(): void {
  const stocks: StockSummary[] = []
  let nextId = 1

  const positions = {
    list: vi.fn(),
    summary: vi.fn(async () => ({
      ok: true as const,
      data: {
        stocks,
        options: [],
        totals: emptyTotals(),
        delayed: false,
        asOf: null,
        unpriced: stocks.filter((s) => !s.priced).length
      } satisfies PortfolioSummary
    })),
    create: vi.fn(async (req: PositionRequest) => {
      const saved: StockSummary = {
        id: nextId++,
        kind: 'STOCK',
        symbol: req.symbol.toUpperCase(),
        quantity: req.quantity,
        costBasis: req.costBasis,
        openedDate: req.openedDate ?? '',
        markPrice: null,
        marketValue: null,
        unrealizedPnl: null,
        unrealizedPnlPct: null,
        priced: false
      }
      stocks.push(saved)
      return { ok: true as const, data: saved }
    }),
    update: vi.fn(),
    remove: vi.fn(),
    import: vi.fn()
  }
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { positions }
}

describe('PortfolioConsole', () => {
  beforeEach(() => {
    installFakeApi()
  })

  it('shows the empty-state when there are no positions', async () => {
    render(<PortfolioConsole />)
    expect(await screen.findByTestId('empty-state')).toBeInTheDocument()
  })

  it('adds a stock position and shows it in the list', async () => {
    const user = userEvent.setup()
    render(<PortfolioConsole />)

    await screen.findByTestId('empty-state')

    await user.click(screen.getByRole('button', { name: /add position/i }))
    await user.type(screen.getByLabelText(/symbol/i), 'aapl')
    await user.type(screen.getByLabelText(/quantity/i), '100')
    await user.type(screen.getByLabelText(/cost basis/i), '150.25')
    await user.type(screen.getByLabelText(/opened date/i), '2026-01-15')
    await user.click(screen.getByRole('button', { name: /^add$/i }))

    await waitFor(() => expect(screen.getByTestId('stock-row')).toBeInTheDocument())
    const row = screen.getByTestId('stock-row')
    expect(within(row).getByText('AAPL')).toBeInTheDocument()
    expect(within(row).getByText('100')).toBeInTheDocument()
    expect(screen.queryByTestId('empty-state')).not.toBeInTheDocument()
  })

  it('renders delayed market value, unrealized P&L ($ and %), and portfolio totals', async () => {
    const data: PortfolioSummary = {
      stocks: [
        {
          id: 1,
          kind: 'STOCK',
          symbol: 'AAPL',
          quantity: 100,
          costBasis: 150,
          openedDate: '2026-01-15',
          markPrice: 165,
          marketValue: 16500,
          unrealizedPnl: 1500,
          unrealizedPnlPct: 10,
          priced: true
        }
      ],
      options: [],
      totals: { costValue: 15000, marketValue: 16500, unrealizedPnl: 1500, unrealizedPnlPct: 10 },
      delayed: true,
      asOf: '2026-06-26T20:00:00Z',
      unpriced: 0
    }
    window.api.positions.summary = vi.fn(async () => ({ ok: true as const, data }))

    render(<PortfolioConsole />)

    const row = await screen.findByTestId('stock-row')
    expect(within(row).getByText('$165.00')).toBeInTheDocument()
    expect(within(row).getByText('$16,500.00')).toBeInTheDocument()
    expect(within(row).getByText('+$1,500.00')).toBeInTheDocument()
    expect(within(row).getByText('+10.00%')).toBeInTheDocument()

    const totals = screen.getByTestId('totals-bar')
    expect(within(totals).getByText('+$1,500.00')).toBeInTheDocument()
    expect(within(totals).getByText('$16,500.00')).toBeInTheDocument()
  })

  it('marks unpriceable positions instead of showing a misleading zero', async () => {
    const data: PortfolioSummary = {
      stocks: [
        {
          id: 7,
          kind: 'STOCK',
          symbol: 'ZZZZ',
          quantity: 10,
          costBasis: 20,
          openedDate: '2026-01-15',
          markPrice: null,
          marketValue: null,
          unrealizedPnl: null,
          unrealizedPnlPct: null,
          priced: false
        }
      ],
      options: [],
      totals: { costValue: 0, marketValue: 0, unrealizedPnl: 0, unrealizedPnlPct: null },
      delayed: false,
      asOf: null,
      unpriced: 1
    }
    window.api.positions.summary = vi.fn(async () => ({ ok: true as const, data }))

    render(<PortfolioConsole />)

    const row = await screen.findByTestId('stock-row')
    // No silent zeros: unpriced money cells render an em dash.
    expect(within(row).getAllByText('—').length).toBeGreaterThan(0)
    expect(within(row).queryByText('$0.00')).not.toBeInTheDocument()
  })
})
