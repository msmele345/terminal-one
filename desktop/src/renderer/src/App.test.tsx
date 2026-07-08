import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from './App'
import type { PortfolioSummary } from '../../preload'

// Phase 7 AC5: the terminal panels stay conventional and readable — the
// slot-machine metaphor is confined to the recommendation moment, not the
// whole app. These tests pin the screen navigation (Portfolio Console as the
// default landing screen, the Slot Machine behind its own nav tab, never both
// rendered at once) and the conventional (non-casino) login copy.

// The console embeds the canvas-backed PriceChart; jsdom can't run a <canvas>, so
// stub lightweight-charts to a no-op renderer. The chart's own plumbing is covered
// in PriceChart.test.tsx — here we only assert the console wires symbols to it.
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

function emptySummary(): PortfolioSummary {
  return {
    stocks: [],
    options: [],
    totals: { costValue: 0, marketValue: 0, unrealizedPnl: 0, unrealizedPnlPct: null },
    delayed: false,
    asOf: null,
    unpriced: 0
  }
}

// Stubs the whole preload surface App + PortfolioConsole need. `loggedIn`
// controls which screen App lands on (login vs authed).
function installFakeApi(loggedIn: boolean): void {
  window.api = {
    session: vi.fn(async () => ({ loggedIn })),
    whoami: vi.fn(async () => ({
      ok: true as const,
      payload: { username: 'mitch', authenticated: true, serverTime: '2026-07-08T12:00:00Z' }
    })),
    login: vi.fn(),
    logout: vi.fn(),
    positions: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      import: vi.fn(),
      summary: vi.fn(async () => ({ ok: true as const, data: emptySummary() }))
    },
    marketData: {
      history: vi.fn(async (symbol: string) => ({
        ok: true as const,
        data: { symbol, asOf: null, delayed: false, bars: [] }
      }))
    },
    engine: {
      run: vi.fn(async () => ({ ok: true as const, data: { recommendations: [], abstentions: [] } }))
    }
  }
}

describe('App screens (Phase 7 AC5)', () => {
  beforeEach(() => {
    installFakeApi(true)
  })

  it('lands on the Portfolio Console with the slot machine confined off-screen', async () => {
    render(<App />)

    await waitFor(() => expect(screen.getByText('PORTFOLIO CONSOLE')).toBeInTheDocument())

    expect(screen.queryByTestId('slot-machine')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Pull the lever' })).toBeNull()
  })

  it('shows a screen nav with Console active by default', async () => {
    render(<App />)

    await waitFor(() => expect(screen.getByText('PORTFOLIO CONSOLE')).toBeInTheDocument())

    const nav = screen.getByRole('navigation', { name: 'Screens' })
    const consoleTab = within(nav).getByRole('button', { name: 'Console' })
    const slotMachineTab = within(nav).getByRole('button', { name: 'Slot Machine' })

    expect(consoleTab).toHaveAttribute('aria-current', 'page')
    expect(slotMachineTab).not.toHaveAttribute('aria-current', 'page')
  })

  it('switches to the Slot Machine screen and back, never showing both', async () => {
    const user = userEvent.setup()
    render(<App />)

    await waitFor(() => expect(screen.getByText('PORTFOLIO CONSOLE')).toBeInTheDocument())

    const nav = screen.getByRole('navigation', { name: 'Screens' })
    await user.click(within(nav).getByRole('button', { name: 'Slot Machine' }))

    await waitFor(() => expect(screen.getByTestId('slot-machine')).toBeInTheDocument())
    expect(screen.queryByText('PORTFOLIO CONSOLE')).toBeNull()

    await user.click(within(nav).getByRole('button', { name: 'Console' }))

    await waitFor(() => expect(screen.getByText('PORTFOLIO CONSOLE')).toBeInTheDocument())
    expect(screen.queryByTestId('slot-machine')).toBeNull()
  })
})

describe('Login screen (Phase 7 AC5)', () => {
  beforeEach(() => {
    installFakeApi(false)
  })

  it('keeps the sign-in conventional — no slot-machine metaphor', async () => {
    render(<App />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument())

    expect(screen.queryByText(/lever/i)).toBeNull()
  })
})
