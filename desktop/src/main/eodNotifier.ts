// Phase 9 AC1 (FR-24, D20): decide when the single post-EOD-batch desktop
// notification fires. Pure module (no electron imports) so the decision logic
// is unit-testable; src/main/index.ts wires it to the backend poll and the
// native Notification.

export interface EodBatchSummary {
  batchRunId: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  startedAt: string
  completedAt: string | null
  recommendationCount: number
  topSymbol: string | null
  topConviction: number | null
}

export interface EodNotificationContent {
  title: string
  body: string
}

export function formatEodNotification(batch: EodBatchSummary): EodNotificationContent {
  if (batch.status === 'FAILED') {
    return {
      title: 'Terminal One — EOD engine run failed',
      body: 'No recommendations were produced. Open Terminal One to check the run.'
    }
  }
  const n = batch.recommendationCount
  const recs = n === 1 ? '1 new recommendation' : `${n} new recommendations`
  const top =
    batch.topSymbol != null && batch.topConviction != null
      ? ` · Top conviction: ${batch.topSymbol} (${batch.topConviction})`
      : ''
  return { title: 'Terminal One — EOD engine run', body: `${recs}${top}` }
}

/**
 * Watches the latest scheduled EOD batch and notifies at most once per newly
 * observed batch: when it completed with recommendations, or when it failed.
 *
 * A failure is alerted because silence there is indistinguishable from a quiet
 * no-trade day, which would let a broken engine go unnoticed for as long as it
 * takes to wonder why the ledger stopped growing. Everything else stays quiet:
 * the first batch seen after startup is silently taken as the baseline (so
 * reopening the app never replays old batches), a completed batch with zero
 * recommendations is a normal outcome and advances the baseline without noise,
 * and a RUNNING batch is left pending until it reaches a terminal status.
 */
export class EodBatchWatcher {
  private lastSeenId: number | null = null

  constructor(
    private readonly fetchLatest: () => Promise<EodBatchSummary | null>,
    private readonly notify: (content: EodNotificationContent) => void
  ) {}

  async check(): Promise<void> {
    let latest: EodBatchSummary | null
    try {
      latest = await this.fetchLatest()
    } catch {
      return // transient network/auth failure — try again next poll
    }
    if (latest == null) return
    if (latest.status === 'RUNNING') return // wait for the terminal status

    const isNew = this.lastSeenId != null && latest.batchRunId !== this.lastSeenId
    const worthAnnouncing =
      latest.status === 'FAILED' ||
      (latest.status === 'COMPLETED' && latest.recommendationCount > 0)
    if (isNew && worthAnnouncing) {
      this.notify(formatEodNotification(latest))
    }
    this.lastSeenId = latest.batchRunId
  }
}
