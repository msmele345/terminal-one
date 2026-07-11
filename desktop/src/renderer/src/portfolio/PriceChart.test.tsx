import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import * as LWC from 'lightweight-charts'
import { PriceChart } from './PriceChart'
import type { PriceHistory } from '../../../preload'

// lightweight-charts draws to a <canvas>, which jsdom can't run. Mock the whole
// module so the test exercises our data plumbing (fetch -> map -> setData), not
// the canvas renderer. A single shared chart/series is enough for one chart.
vi.mock('lightweight-charts', () => {
  const series = { setData: vi.fn(), applyOptions: vi.fn() }
  const timeScale = { fitContent: vi.fn() }
  const chart = {
    addSeries: vi.fn(() => series),
    timeScale: vi.fn(() => timeScale),
    applyOptions: vi.fn(),
    remove: vi.fn()
  }
  return {
    createChart: vi.fn(() => chart),
    AreaSeries: 'AreaSeries',
    ColorType: { Solid: 'solid' },
    __mock: { chart, series }
  }
})

const mock = (LWC as unknown as { __mock: { chart: { addSeries: ReturnType<typeof vi.fn> }; series: { setData: ReturnType<typeof vi.fn> } } }).__mock
const createChart = LWC.createChart as unknown as ReturnType<typeof vi.fn>

function history(bars: PriceHistory['bars'], delayed = false): PriceHistory {
  return { symbol: 'AAPL', asOf: '2026-06-26T20:00:00Z', delayed, bars }
}

function installFakeApi(data: PriceHistory): ReturnType<typeof vi.fn> {
  const historyFn = vi.fn(async () => ({ ok: true as const, data }))
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { marketData: { history: historyFn } }
  return historyFn
}

describe('PriceChart', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches daily bars for the symbol and feeds close prices to a chart series', async () => {
    const data = history([
      { date: '2026-01-13', open: 190, high: 192, low: 189, close: 191.5, volume: 1 },
      { date: '2026-01-14', open: 191.5, high: 193, low: 190.5, close: 192.8, volume: 2 }
    ])
    const historyFn = installFakeApi(data)

    render(<PriceChart symbol="AAPL" />)

    await waitFor(() => expect(createChart).toHaveBeenCalled())
    expect(historyFn).toHaveBeenCalledWith('AAPL')
    expect(mock.series.setData).toHaveBeenCalledWith([
      { time: '2026-01-13', value: 191.5 },
      { time: '2026-01-14', value: 192.8 }
    ])
  })

  it('shows a no-history message and does not create a chart when bars are empty', async () => {
    installFakeApi(history([]))

    render(<PriceChart symbol="ZZZZ" />)

    expect(await screen.findByText(/no price history for ZZZZ/i)).toBeInTheDocument()
    expect(createChart).not.toHaveBeenCalled()
  })

  it('surfaces a load error instead of crashing', async () => {
    const historyFn = vi.fn(async () => ({ ok: false as const, error: 'Session expired' }))
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { marketData: { history: historyFn } }

    render(<PriceChart symbol="AAPL" />)

    expect(await screen.findByText(/couldn.t load price history/i)).toBeInTheDocument()
    expect(createChart).not.toHaveBeenCalled()
  })

  it('flags delayed data with a DELAYED tag', async () => {
    installFakeApi(history([{ date: '2026-01-13', open: 1, high: 1, low: 1, close: 1, volume: 1 }], true))

    render(<PriceChart symbol="AAPL" />)

    expect(await screen.findByText('DELAYED')).toBeInTheDocument()
  })
})
