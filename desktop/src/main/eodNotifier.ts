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
  const n = batch.recommendationCount
  const recs = n === 1 ? '1 new recommendation' : `${n} new recommendations`
  const top =
    batch.topSymbol != null && batch.topConviction != null
      ? ` · Top conviction: ${batch.topSymbol} (${batch.topConviction})`
      : ''
  return { title: 'Terminal One — EOD engine run', body: `${recs}${top}` }
}

/**
 * Watches the latest scheduled EOD batch and notifies exactly once per newly
 * completed batch that produced recommendations. The first batch seen after
 * startup is silently taken as the baseline, so reopening the app never
 * replays old batches; zero-recommendation and failed batches advance the
 * baseline without noise; a RUNNING batch is left pending until it completes.
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
    if (isNew && latest.status === 'COMPLETED' && latest.recommendationCount > 0) {
      this.notify(formatEodNotification(latest))
    }
    this.lastSeenId = latest.batchRunId
  }
}
