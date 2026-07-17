import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SlotMachine } from './SlotMachine'
import type { EngineRunResult, Recommendation } from '../../../preload'

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
      },
      {
        action: 'SELL',
        optionSymbol: 'AAPL260821C00200000',
        callPut: 'CALL',
        strike: 200,
        expiry: '2026-08-21',
        bid: 3.2,
        ask: 3.5,
        mid: 3.35,
        delta: 0.3
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
      sizing: {
        contracts: 7,
        maxLossPerContract: 490,
        portfolioValue: 150_000,
        perTradeRiskPct: 0.03,
        riskAmount: 3_430
      }
    },
    ...overrides
  }
}

function installEngineApi(run: ReturnType<typeof vi.fn>): void {
  // @ts-expect-error — partial stub of the preload surface for the test
  window.api = { engine: { run } }
}

// A promise we resolve by hand, to hold the machine in its "spinning" state.
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

describe('SlotMachine', () => {
  beforeEach(() => {
    installEngineApi(vi.fn())
  })

  it('prompts to pull the lever before any run, showing no recommendations yet', () => {
    render(<SlotMachine minSpinMs={0} />)
    expect(screen.getByRole('button', { name: /pull the lever/i })).toBeInTheDocument()
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
    expect(screen.queryByTestId('engine-empty')).not.toBeInTheDocument()
  })

  // ---- Phase 7 AC1 ----

  it('pulling the lever triggers the engine run', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    expect(run).toHaveBeenCalledTimes(1)
  })

  it('spins the reels while the run is in flight, then settles onto the results', async () => {
    const user = userEvent.setup()
    const gate = deferred<{ ok: true; data: EngineRunResult }>()
    const run = vi.fn(() => gate.promise)
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // Mid-run: reels are spinning and no result cards exist yet.
    expect(screen.getAllByTestId('reel-spinning').length).toBeGreaterThan(0)
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()

    // Engine resolves → reels settle onto the recommendation cards.
    gate.resolve({ ok: true, data: { recommendations: [sampleRec()] } })
    expect(await screen.findByTestId('recommendation')).toBeInTheDocument()
    expect(screen.queryByTestId('reel-spinning')).not.toBeInTheDocument()
  })

  it('resolves the reels onto every returned top-N recommendation, in order', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [
          sampleRec({ id: 1, symbol: 'MSFT' }),
          sampleRec({ id: 2, symbol: 'TSLA' }),
          sampleRec({ id: 3, symbol: 'NVDA' })
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const cards = await screen.findAllByTestId('recommendation')
    expect(cards).toHaveLength(3)
    expect(cards.map((c) => within(c).getByText(/^(MSFT|TSLA|NVDA)$/).textContent)).toEqual([
      'MSFT',
      'TSLA',
      'NVDA'
    ])
  })

  // ---- Phase 7 AC2 ----

  it('fires a distinct jackpot payout for a high-conviction recommendation', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: { recommendations: [sampleRec({ conviction: 88 })] }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    expect(await screen.findByTestId('jackpot')).toBeInTheDocument()
    expect(screen.getByTestId('jackpot-badge')).toBeInTheDocument()
  })

  it('does not fire a jackpot for ordinary-conviction recommendations', async () => {
    const user = userEvent.setup()
    // sampleRec defaults to conviction 72 — below the High band (≥ 80).
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    await screen.findByTestId('recommendation')
    expect(screen.queryByTestId('jackpot')).not.toBeInTheDocument()
    expect(screen.queryByTestId('jackpot-badge')).not.toBeInTheDocument()
  })

  it('marks only the high-conviction cards when a run mixes conviction levels', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [
          sampleRec({ id: 1, symbol: 'NVDA', conviction: 92 }),
          sampleRec({ id: 2, symbol: 'AAPL', conviction: 61 })
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    expect(await screen.findByTestId('jackpot')).toBeInTheDocument()
    const badges = screen.getAllByTestId('jackpot-badge')
    expect(badges).toHaveLength(1)
    // The lone badge sits on the NVDA reel, not the ordinary-conviction AAPL one.
    const badgedReel = badges[0].closest('.reel-result') as HTMLElement
    expect(within(badgedReel).getByText('NVDA')).toBeInTheDocument()
  })

  it('resolves to a calm no-trade state with no jackpot when the engine abstains', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const empty = await screen.findByTestId('engine-empty')
    expect(empty).toHaveTextContent(/no trade/i)
    expect(screen.queryByTestId('jackpot')).not.toBeInTheDocument()
    expect(screen.queryByTestId('reel-spinning')).not.toBeInTheDocument()
  })

  // ---- Casino-floor ambience (the room around the machine) ----

  it('sets the casino-floor scene around the cabinet, all decorative and hidden from assistive tech', () => {
    render(<SlotMachine minSpinMs={0} />)

    // The room (backdrop + bokeh) and the cabinet's marquee are always present,
    // and every decorative layer is aria-hidden so the screen reader experience
    // is unchanged from the plain machine.
    expect(screen.getByTestId('casino-ambience')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByTestId('marquee')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByTestId('floor-spill')).toHaveAttribute('aria-hidden', 'true')
  })

  it('marks the room with the run state — spinning, then jackpot — and rains coins on a high-conviction hit', async () => {
    const user = userEvent.setup()
    const gate = deferred<{ ok: true; data: EngineRunResult }>()
    installEngineApi(vi.fn(() => gate.promise))

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // While the reels tumble, the room is in spin mode and no coins have fallen.
    expect(screen.getByTestId('slot-machine')).toHaveClass('is-running')
    expect(screen.queryByTestId('coin-burst')).not.toBeInTheDocument()

    gate.resolve({ ok: true, data: { recommendations: [sampleRec({ conviction: 88 })] } })

    expect(await screen.findByTestId('coin-burst')).toBeInTheDocument()
    const machine = screen.getByTestId('slot-machine')
    expect(machine).toHaveClass('is-jackpot')
    expect(machine).not.toHaveClass('is-running')
  })

  it('pays out no coins for an ordinary-conviction run', async () => {
    const user = userEvent.setup()
    // sampleRec defaults to conviction 72 — below the High band (≥ 80).
    installEngineApi(
      vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    )

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    await screen.findByTestId('recommendation')
    expect(screen.queryByTestId('coin-burst')).not.toBeInTheDocument()
    expect(screen.getByTestId('slot-machine')).not.toHaveClass('is-jackpot')
  })

  // ---- Phase 7 AC3 ----

  it('expands a reel result to the full rationale — signals, regime, strikes, POP, risk/reward, and sizing', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // Strikes, POP and reward:risk sit on the settled reel's card face…
    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText('190.00 CALL')).toBeInTheDocument()
    expect(within(card).getByText('200.00 CALL')).toBeInTheDocument()
    expect(within(card).getByText('58%')).toBeInTheDocument()
    expect(within(card).getByText('1.04 : 1')).toBeInTheDocument()

    // …and expanding reveals every structured rationale group with its values.
    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByRole('heading', { name: 'Signal' })).toBeInTheDocument()
    expect(within(rationale).getByText('58.3')).toBeInTheDocument() // RSI
    expect(within(rationale).getByText('191.20 / 185.40')).toBeInTheDocument() // EMA fast/slow
    expect(within(rationale).getByRole('heading', { name: 'Regime' })).toBeInTheDocument()
    expect(within(rationale).getByText('NORMAL IV')).toBeInTheDocument()
    expect(within(rationale).getByRole('heading', { name: 'Selection' })).toBeInTheDocument()
    expect(within(rationale).getByText('0.55 / 0.55')).toBeInTheDocument() // long Δ target/actual
    expect(within(rationale).getByText('0.28 / 0.30')).toBeInTheDocument() // short Δ target/actual
    expect(within(rationale).getByRole('heading', { name: 'Pricing' })).toBeInTheDocument()
    expect(within(rationale).getByText('$194.90')).toBeInTheDocument() // breakeven
    expect(within(rationale).getByRole('heading', { name: 'Sizing' })).toBeInTheDocument()
    expect(within(rationale).getByText('$3,430.00')).toBeInTheDocument() // risk per trade
    expect(within(rationale).getByText('$150,000.00')).toBeInTheDocument() // portfolio value
  })

  it('expands each reel result independently of the others', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [sampleRec({ id: 1, symbol: 'MSFT' }), sampleRec({ id: 2, symbol: 'TSLA' })]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const cards = await screen.findAllByTestId('recommendation')
    expect(cards).toHaveLength(2)

    // Expanding the second reel opens only the second reel's rationale.
    await user.click(within(cards[1]).getByRole('button', { name: /why/i }))
    expect(screen.getAllByTestId('rationale')).toHaveLength(1)
    expect(within(cards[1]).getByTestId('rationale')).toBeInTheDocument()
    expect(within(cards[0]).queryByTestId('rationale')).not.toBeInTheDocument()

    // The first expands (and stays) independently.
    await user.click(within(cards[0]).getByRole('button', { name: /why/i }))
    expect(screen.getAllByTestId('rationale')).toHaveLength(2)
  })

  // ---- Phase 7 AC4 ----

  it('pulses the jackpot glow on a dedicated opacity layer, not the banner itself', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: { recommendations: [sampleRec({ conviction: 88 })] }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    // The infinite pulse lives on a decorative glow layer that animates opacity
    // only (box-shadow stays static CSS), so the payout is compositor-friendly.
    const banner = await screen.findByTestId('jackpot')
    const glow = within(banner).getByTestId('jackpot-glow')
    expect(glow).toHaveAttribute('aria-hidden', 'true')
  })

  it('lists returned recommendations with their key trade fields on run', async () => {
    const user = userEvent.setup()
    const run = vi.fn(
      async (): Promise<{ ok: true; data: EngineRunResult }> => ({
        ok: true,
        data: { recommendations: [sampleRec()] }
      })
    )
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText('AAPL')).toBeInTheDocument()
    expect(within(card).getByText(/bull call debit spread/i)).toBeInTheDocument()
    expect(within(card).getByText(/58%/)).toBeInTheDocument() // POP
    expect(within(card).getByText('BUY')).toBeInTheDocument()
    expect(within(card).getByText('SELL')).toBeInTheDocument()
  })

  it('reveals the structured rationale only after expanding a recommendation', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [sampleRec()] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).queryByTestId('rationale')).not.toBeInTheDocument()

    const toggle = within(card).getByRole('button', { name: /why/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)

    const rationale = within(card).getByTestId('rationale')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(rationale).getByText(/Phase 4 assumes a NORMAL IV regime/i)).toBeInTheDocument()
    expect(within(rationale).getByText(/confident/i)).toBeInTheDocument() // conviction band
  })

  it('labels covered-call income overlays as capped upside', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [
          sampleRec({
            strategy: 'COVERED_CALL',
            direction: 'NEUTRAL',
            regime: 'HIGH',
            contracts: 2,
            legs: [
              {
                action: 'SELL',
                optionSymbol: 'MSFT260808C00110000',
                callPut: 'CALL',
                strike: 110,
                expiry: '2026-08-08',
                bid: 2.3,
                ask: 2.36,
                mid: 2.33,
                delta: 0.3
              }
            ],
            rationale: {
              ...sampleRec().rationale,
              incomeOverlay: {
                label: 'CAPS_UPSIDE_ABOVE_STRIKE',
                note: 'Covered call income on held shares; upside is capped above the short-call strike.',
                heldShares: 200,
                contracts: 2,
                capStrike: 110,
                premiumPerShare: 2.33,
                cappedUpsidePerContract: 1233
              }
            }
          })
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText(/covered call/i)).toBeInTheDocument()
    expect(within(card).getByText(/caps upside above strike/i)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByText(/income overlay/i)).toBeInTheDocument()
    expect(within(rationale).getByText(/covered call income on held shares/i)).toBeInTheDocument()
  })

  it('flags cash-secured put entry suggestions with their required collateral', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [
          sampleRec({
            strategy: 'CASH_SECURED_PUT',
            direction: 'NEUTRAL',
            regime: 'HIGH',
            contracts: 1,
            legs: [
              {
                action: 'SELL',
                optionSymbol: 'MSFT260808P00090000',
                callPut: 'PUT',
                strike: 90,
                expiry: '2026-08-08',
                bid: 1.9,
                ask: 1.96,
                mid: 1.93,
                delta: -0.3
              }
            ],
            rationale: {
              ...sampleRec().rationale,
              entrySuggestion: {
                label: 'REQUIRES_CASH_COLLATERAL',
                note: 'Cash-secured put entry suggestion; requires strike × 100 × contracts in cash collateral (V1 does not track your cash balance).',
                contracts: 1,
                strike: 90,
                premiumPerShare: 1.93,
                requiredCapital: 9000
              }
            }
          })
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText(/cash secured put/i)).toBeInTheDocument()
    expect(within(card).getByText(/requires cash collateral/i)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByRole('heading', { name: /entry suggestion/i })).toBeInTheDocument()
    expect(within(rationale).getByText(/\$9,000/)).toBeInTheDocument() // required collateral
    expect(within(rationale).getByText(/does not track your cash balance/i)).toBeInTheDocument()
  })

  it('flags recommendations when earnings were not screened', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [
          sampleRec({
            rationale: {
              ...sampleRec().rationale,
              warnings: [
                {
                  label: 'EARNINGS_CALENDAR_UNAVAILABLE',
                  note: 'Earnings calendar unavailable; expiry was not screened for earnings.'
                }
              ]
            }
          })
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText(/earnings not screened/i)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByRole('heading', { name: /warnings/i })).toBeInTheDocument()
    expect(within(rationale).getByText(/not screened for earnings/i)).toBeInTheDocument()
  })

  it('surfaces the explicit abstain reasons the engine returns', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({
      ok: true as const,
      data: {
        recommendations: [],
        abstentions: [
          {
            symbol: 'TSLA',
            reason: 'WEAK_SIGNAL',
            detail: 'Signal NEUTRAL (direction score -0.180 within the ±threshold); no directional edge'
          }
        ]
      }
    }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    const abstentions = await screen.findByTestId('engine-abstentions')
    expect(within(abstentions).getByText('TSLA')).toBeInTheDocument()
    expect(within(abstentions).getByText(/weak signal/i)).toBeInTheDocument()
    expect(within(abstentions).getByText(/no directional edge/i)).toBeInTheDocument()
    // The reasons explain the empty result, so the generic empty-state is suppressed.
    expect(screen.queryByTestId('engine-empty')).not.toBeInTheDocument()
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
  })

  it('renders a clean abstain state when the engine returns no recommendations', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [] } }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    expect(await screen.findByTestId('engine-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
  })

  it('surfaces an error without crashing when the run fails', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: false as const, error: 'Session expired' }))
    installEngineApi(run)

    render(<SlotMachine minSpinMs={0} />)
    await user.click(screen.getByRole('button', { name: /pull the lever/i }))

    await waitFor(() => expect(screen.getByText('Session expired')).toBeInTheDocument())
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
  })
})
