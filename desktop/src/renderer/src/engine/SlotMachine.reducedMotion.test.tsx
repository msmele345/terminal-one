import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SlotMachine } from './SlotMachine'
import type { EngineRunResult, Recommendation } from '../../../preload'

// Phase 7 AC4: reduced-motion preferences are respected. This lives in its own
// test file because framer-motion initializes its prefers-reduced-motion
// listener once per module registry — stubbing matchMedia here (before the
// first render) makes useReducedMotion() report `true` for the whole file,
// exactly as it would for a user with the OS preference set.
beforeAll(() => {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('prefers-reduced-motion'),
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })) as unknown as typeof window.matchMedia
})

function sampleRec(overrides: Partial<Recommendation> = {}): Recommendation {
  return {
    id: 1,
    symbol: 'AAPL',
    strategy: 'BULL_CALL_DEBIT_SPREAD',
    direction: 'BULLISH',
    regime: 'NORMAL',
    conviction: 72,
    configVersion: 1,
    expiry: '2026-08-21',
    legs: [
      {
        action: 'BUY',
        optionSymbol: 'AAPL260821C00190000',
        callPut: 'CALL',
        strike: 190,
        expiry: '2026-08-21',
        bid: 8.1,
        ask: 8.4,
        mid: 8.25,
        delta: 0.55
      }
    ],
    contracts: 7,
    entryDebit: 4.9,
    probabilityOfProfit: 0.58,
    maxProfit: 510,
    maxLoss: 490,
    riskReward: 1.04,
    score: 42.3,
    rationale: {
      signals: {
        trendVote: 1,
        macdVote: 1,
        rsiVote: 0,
        directionScore: 0.7,
        conviction: 72,
        emaFast: 191.2,
        emaSlow: 185.4,
        macdHistogram: 0.42,
        rsi: 58.3
      },
      regime: { value: 'NORMAL', reason: 'Phase 4 assumes a NORMAL IV regime', currentIv: 0.284 },
      selection: {
        convictionBand: 'confident',
        dte: 49,
        longDeltaTarget: 0.55,
        shortDeltaTarget: 0.28,
        selectedLongDelta: 0.55,
        selectedShortDelta: 0.3
      },
      pricing: {
        width: 10,
        entryDebit: 4.9,
        breakeven: 194.9,
        probabilityOfProfit: 0.58,
        maxProfit: 510,
        maxLoss: 490,
        riskReward: 1.04,
        rawExpectedValue: 89.2
      },
      sizing: null
    },
    ...overrides
  }
}

function installEngineApi(run: ReturnType<typeof vi.fn>): void {
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { engine: { run } }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

// No inline transform/opacity means framer-motion is not driving the element —
// the reduced-motion branch rendered it static.
function expectStatic(el: HTMLElement): void {
  expect(el.style.transform).toBe('')
  expect(el.style.opacity).toBe('')
}

describe('SlotMachine under prefers-reduced-motion', () => {
  beforeEach(() => {
    installEngineApi(vi.fn())
  })

  it('holds the reels and lever still while a run is in flight', async () => {
    const user = userEvent.setup()
    const gate = deferred<{ ok: true; data: EngineRunResult }>()
    installEngineApi(vi.fn(() => gate.promise))

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // The reels render but do not tumble, and the lever arm does not swing.
    expect(screen.getAllByTestId('reel-spinning').length).toBeGreaterThan(0)
    document
      .querySelectorAll<HTMLElement>('.reel-strip, .lever-arm')
      .forEach((el) => expectStatic(el))

    gate.resolve({ ok: true, data: { recommendations: [sampleRec()] } })
    expect(await screen.findByTestId('recommendation')).toBeInTheDocument()
  })

  it('settles results without entry motion and renders the jackpot without its pulsing glow', async () => {
    const user = userEvent.setup()
    installEngineApi(
      vi.fn(async () => ({
        ok: true as const,
        data: { recommendations: [sampleRec({ conviction: 88 })] }
      }))
    )

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // Results land as plain, static elements.
    await screen.findByTestId('recommendation')
    document.querySelectorAll<HTMLElement>('.reel-result').forEach((el) => expectStatic(el))

    // The jackpot still announces itself, but the infinite pulse layer is gone.
    const banner = screen.getByTestId('jackpot')
    expectStatic(banner)
    expect(within(banner).queryByTestId('jackpot-glow')).not.toBeInTheDocument()
    expect(screen.getByTestId('jackpot-badge')).toBeInTheDocument()
  })

  it('keeps the full rationale drill-down working with motion off', async () => {
    const user = userEvent.setup()
    installEngineApi(
      vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    )

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByRole('heading', { name: 'Signal' })).toBeInTheDocument()
    expect(within(rationale).getByText('58.3')).toBeInTheDocument()
  })
})
