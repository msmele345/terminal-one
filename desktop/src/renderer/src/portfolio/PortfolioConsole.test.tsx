import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PortfolioConsole } from './PortfolioConsole'
import type { PositionRequest, StockPosition } from '../../../preload'

// A stateful in-memory fake of the main-process position API. The component talks
// only to window.api, so this fully exercises the add -> list path without IPC.
function installFakeApi(): void {
  const stocks: StockPosition[] = []
  let nextId = 1

  const positions = {
    list: vi.fn(async () => ({ ok: true as const, data: { stocks, options: [] } })),
    create: vi.fn(async (req: PositionRequest) => {
      const saved: StockPosition = {
        id: nextId++,
        kind: 'STOCK',
        symbol: req.symbol.toUpperCase(),
        quantity: req.quantity,
        costBasis: req.costBasis,
        openedDate: req.openedDate ?? ''
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

    // Empty to start
    await screen.findByTestId('empty-state')

    // Open the entry modal and fill the stock form
    await user.click(screen.getByRole('button', { name: /add position/i }))
    await user.type(screen.getByLabelText(/symbol/i), 'aapl')
    await user.type(screen.getByLabelText(/quantity/i), '100')
    await user.type(screen.getByLabelText(/cost basis/i), '150.25')
    await user.type(screen.getByLabelText(/opened date/i), '2026-01-15')
    await user.click(screen.getByRole('button', { name: /^add$/i }))

    // The new position appears in the stocks table (symbol upper-cased)
    await waitFor(() => expect(screen.getByTestId('stock-row')).toBeInTheDocument())
    const row = screen.getByTestId('stock-row')
    expect(within(row).getByText('AAPL')).toBeInTheDocument()
    expect(within(row).getByText('100')).toBeInTheDocument()
    expect(screen.queryByTestId('empty-state')).not.toBeInTheDocument()
  })
})
