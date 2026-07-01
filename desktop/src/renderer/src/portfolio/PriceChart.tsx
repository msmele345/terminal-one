import { useEffect, useRef, useState } from 'react'
import { AreaSeries, ColorType, createChart } from 'lightweight-charts'
import type { PriceHistory } from '../../../preload'

// Neon-on-dark theme for the per-symbol price chart (FR-20). The chart panel is a
// thin client: it asks the main process for cached daily bars and plots the close.
const CHART_OPTIONS = {
  autoSize: true,
  layout: {
    background: { type: ColorType.Solid, color: 'transparent' },
    textColor: '#6f7591',
    fontSize: 11
  },
  grid: {
    vertLines: { color: 'rgba(120, 130, 170, 0.08)' },
    horzLines: { color: 'rgba(120, 130, 170, 0.08)' }
  },
  rightPriceScale: { borderColor: 'rgba(120, 130, 170, 0.18)' },
  timeScale: { borderColor: 'rgba(120, 130, 170, 0.18)', fixLeftEdge: true, fixRightEdge: true },
  crosshair: { vertLine: { color: '#39d0ff' }, horzLine: { color: '#39d0ff' } }
} as const

const SERIES_OPTIONS = {
  lineColor: '#39d0ff',
  topColor: 'rgba(57, 208, 255, 0.35)',
  bottomColor: 'rgba(57, 208, 255, 0.0)',
  lineWidth: 2 as const,
  priceLineVisible: false,
  lastValueVisible: true
}

type Status =
  | { phase: 'loading' }
  | { phase: 'error'; message: string }
  | { phase: 'ready'; history: PriceHistory }

export function PriceChart({ symbol }: { symbol: string }): JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null)
  const [status, setStatus] = useState<Status>({ phase: 'loading' })

  // Fetch (cached, delayed) daily bars whenever the selected symbol changes.
  useEffect(() => {
    let cancelled = false
    setStatus({ phase: 'loading' })
    void window.api.marketData.history(symbol).then((res) => {
      if (cancelled) return
      setStatus(res.ok ? { phase: 'ready', history: res.data } : { phase: 'error', message: res.error })
    })
    return () => {
      cancelled = true
    }
  }, [symbol])

  // Mount the chart once bars are available; tear it down on symbol change/unmount.
  useEffect(() => {
    const container = containerRef.current
    if (!container || status.phase !== 'ready' || status.history.bars.length === 0) return

    const chart = createChart(container, CHART_OPTIONS)
    const series = chart.addSeries(AreaSeries, SERIES_OPTIONS)
    series.setData(status.history.bars.map((b) => ({ time: b.date, value: b.close })))
    chart.timeScale().fitContent()

    return () => chart.remove()
  }, [status])

  const hasBars = status.phase === 'ready' && status.history.bars.length > 0
  const delayed = status.phase === 'ready' && status.history.delayed

  return (
    <div className="price-chart" data-testid="price-chart">
      <div className="price-chart-head">
        <span className="neon">{symbol}</span>
        {delayed && <span className="tag delayed">DELAYED</span>}
      </div>
      {status.phase === 'loading' && <p className="muted price-chart-msg">Loading price history…</p>}
      {status.phase === 'error' && (
        <p className="muted price-chart-msg">Couldn’t load price history: {status.message}</p>
      )}
      {status.phase === 'ready' && !hasBars && (
        <p className="muted price-chart-msg">No price history for {symbol}.</p>
      )}
      <div
        ref={containerRef}
        className="price-chart-canvas"
        data-testid="price-chart-canvas"
        hidden={!hasBars}
      />
    </div>
  )
}
