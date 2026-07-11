import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SlotMachine } from './SlotMachine'
import type { Recommendation } from '../../../preload'

// Phase 7 UX polish: the reels must stay visible for a guaranteed minimum spin
// window even when the engine resolves almost instantly (e.g. the stub profile).
// Fake timers let us prove the gate deterministically: resolve the engine at
// t≈0, then show the reels only settle after the min-spin elapses.

function sampleRec(): Recommendation {
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
    }
  }
}

describe('SlotMachine minimum spin window', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps the reels spinning until the min-spin window elapses even when the engine resolves instantly', async () => {
    // Engine resolves immediately — the delay must come from the min-spin gate.
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    // @ts-expect-error — partial stub of the preload surface for the test
    window.api = { engine: { run } }

    render(<SlotMachine minSpinMs={1400} />)
    fireEvent.click(screen.getByRole('button', { name: /pull the lever/i }))

    // Let the engine promise settle without advancing the clock.
    await vi.advanceTimersByTimeAsync(0)
    expect(run).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('reel-spinning').length).toBeGreaterThan(0)
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()

    // Just short of the window: still spinning.
    await vi.advanceTimersByTimeAsync(1399)
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()

    // Window elapsed: reels settle onto the recommendation.
    await vi.advanceTimersByTimeAsync(1)
    expect(screen.getByTestId('recommendation')).toBeInTheDocument()
    expect(screen.queryByTestId('reel-spinning')).not.toBeInTheDocument()
  })
})
