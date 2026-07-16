import { describe, it, expect, vi } from 'vitest'
import { EodBatchWatcher, formatEodNotification, type EodBatchSummary } from './eodNotifier'

// Phase 9 AC1 (FR-24, D20): exactly one native notification per completed EOD
// batch — "N new recommendations, top conviction: X" — and no other alert
// noise: nothing on app start for batches that predate it, nothing for
// zero-recommendation or failed batches, and never a repeat for the same batch.

function batch(overrides: Partial<EodBatchSummary> = {}): EodBatchSummary {
  return {
    batchRunId: 41,
    status: 'COMPLETED',
    startedAt: '2026-07-15T20:30:00Z',
    completedAt: '2026-07-15T20:31:00Z',
    recommendationCount: 3,
    topSymbol: 'TSLA',
    topConviction: 82,
    ...overrides
  }
}

describe('EodBatchWatcher', () => {
  it('does not notify for the batch already latest when the app starts (baseline)', async () => {
    const notify = vi.fn()
    const watcher = new EodBatchWatcher(async () => batch(), notify)

    await watcher.check()

    expect(notify).not.toHaveBeenCalled()
  })

  it('notifies exactly once when a new completed batch with recommendations lands', async () => {
    const notify = vi.fn()
    let latest = batch({ batchRunId: 41 })
    const watcher = new EodBatchWatcher(async () => latest, notify)

    await watcher.check() // baseline
    latest = batch({ batchRunId: 42, recommendationCount: 2, topSymbol: 'AAPL', topConviction: 74 })
    await watcher.check() // new batch → notify
    await watcher.check() // same batch again → silent

    expect(notify).toHaveBeenCalledTimes(1)
    expect(notify).toHaveBeenCalledWith({
      title: 'Terminal One — EOD engine run',
      body: '2 new recommendations · Top conviction: AAPL (74)'
    })
  })

  it('stays silent for a new batch that produced no recommendations', async () => {
    const notify = vi.fn()
    let latest = batch({ batchRunId: 41 })
    const watcher = new EodBatchWatcher(async () => latest, notify)

    await watcher.check()
    latest = batch({ batchRunId: 42, recommendationCount: 0, topSymbol: null, topConviction: null })
    await watcher.check()

    expect(notify).not.toHaveBeenCalled()
  })

  it('stays silent for failed batches', async () => {
    const notify = vi.fn()
    let latest = batch({ batchRunId: 41 })
    const watcher = new EodBatchWatcher(async () => latest, notify)

    await watcher.check()
    latest = batch({ batchRunId: 42, status: 'FAILED' })
    await watcher.check()

    expect(notify).not.toHaveBeenCalled()
  })

  it('waits for a RUNNING batch and notifies once it completes', async () => {
    const notify = vi.fn()
    let latest = batch({ batchRunId: 41 })
    const watcher = new EodBatchWatcher(async () => latest, notify)

    await watcher.check()
    latest = batch({ batchRunId: 42, status: 'RUNNING', completedAt: null, recommendationCount: 0 })
    await watcher.check()
    expect(notify).not.toHaveBeenCalled()

    latest = batch({ batchRunId: 42, status: 'COMPLETED', recommendationCount: 1 })
    await watcher.check()
    expect(notify).toHaveBeenCalledTimes(1)
  })

  it('skips quietly while no batch is available (logged out / no runs yet), then baselines', async () => {
    const notify = vi.fn()
    let latest: EodBatchSummary | null = null
    const watcher = new EodBatchWatcher(async () => latest, notify)

    await watcher.check() // nothing available — no baseline yet
    latest = batch({ batchRunId: 41 })
    await watcher.check() // first visible batch becomes the baseline, silently

    expect(notify).not.toHaveBeenCalled()
  })

  it('never throws when the fetch fails', async () => {
    const notify = vi.fn()
    const watcher = new EodBatchWatcher(async () => {
      throw new Error('backend unreachable')
    }, notify)

    await expect(watcher.check()).resolves.toBeUndefined()
    expect(notify).not.toHaveBeenCalled()
  })
})

describe('formatEodNotification', () => {
  it('reads "N new recommendations, top conviction: X"', () => {
    expect(formatEodNotification(batch()).body).toBe(
      '3 new recommendations · Top conviction: TSLA (82)'
    )
  })

  it('uses the singular for one recommendation', () => {
    expect(formatEodNotification(batch({ recommendationCount: 1 })).body).toBe(
      '1 new recommendation · Top conviction: TSLA (82)'
    )
  })
})
