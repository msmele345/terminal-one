import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RecommendationsPanel } from './RecommendationsPanel'
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

describe('RecommendationsPanel', () => {
  beforeEach(() => {
    installEngineApi(vi.fn())
  })

  it('prompts to run before any engine run, showing no recommendations yet', () => {
    render(<RecommendationsPanel />)
    expect(screen.getByRole('button', { name: /run engine/i })).toBeInTheDocument()
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
    expect(screen.queryByTestId('engine-empty')).not.toBeInTheDocument()
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

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

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

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

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

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

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

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

    const card = await screen.findByTestId('recommendation')
    expect(within(card).getByText(/cash secured put/i)).toBeInTheDocument()
    expect(within(card).getByText(/requires cash collateral/i)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /why/i }))
    const rationale = within(card).getByTestId('rationale')
    expect(within(rationale).getByRole('heading', { name: /entry suggestion/i })).toBeInTheDocument()
    expect(within(rationale).getByText(/\$9,000/)).toBeInTheDocument() // required collateral
    expect(within(rationale).getByText(/does not track your cash balance/i)).toBeInTheDocument()
  })

  it('renders a clean abstain state when the engine returns no recommendations', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: true as const, data: { recommendations: [] } }))
    installEngineApi(run)

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

    expect(await screen.findByTestId('engine-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
  })

  it('surfaces an error without crashing when the run fails', async () => {
    const user = userEvent.setup()
    const run = vi.fn(async () => ({ ok: false as const, error: 'Session expired' }))
    installEngineApi(run)

    render(<RecommendationsPanel />)
    await user.click(screen.getByRole('button', { name: /run engine/i }))

    await waitFor(() => expect(screen.getByText('Session expired')).toBeInTheDocument())
    expect(screen.queryByTestId('recommendation')).not.toBeInTheDocument()
  })
})
