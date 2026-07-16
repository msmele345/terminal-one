import { describe, it, beforeEach, vi, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PositionModal } from "./PositionModal";
import { PortfolioSummary, PositionRequest, PriceHistory, StockSummary } from "../../../preload";

vi.mock('lightweight-charts', () => {
  const series = { setData: vi.fn(), applyOptions: vi.fn() }
  const chart = {
    addSeries: vi.fn(() => series),
    timeScale: vi.fn(() => ({ fitContent: vi.fn() })),
    applyOptions: vi.fn(),
    remove: vi.fn()
  }
  return { createChart: vi.fn(() => chart), AreaSeries: 'AreaSeries', ColorType: { Solid: 'solid' } }
})

function emptyTotals(): PortfolioSummary['totals'] {
  return { costValue: 0, marketValue: 0, unrealizedPnl: 0, unrealizedPnlPct: null }
}

function emptyHistory(symbol: string): PriceHistory {
  return { symbol, asOf: null, delayed: false, bars: [] }
}

let historySpy: ReturnType<typeof vi.fn>;

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
  historySpy = vi.fn(async (symbol: string) => ({ ok: true as const, data: emptyHistory(symbol) }))
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { positions, marketData: { history: historySpy } }
}

describe('PositionModal', () => {
  beforeEach(() => {
    installFakeApi();
  })

  it('shows the empty-state when there are no positions', async () => {
    render(<PositionModal onClose={vi.fn()} onSaved={vi.fn()} onError={vi.fn()} />)

    expect(screen.getByText('NEW POSITION')).toBeInTheDocument()
  });
});



