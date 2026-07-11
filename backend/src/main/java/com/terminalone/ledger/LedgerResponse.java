package com.terminalone.ledger;

import java.util.List;

/**
 * Phase 8 AC2 ledger payload: entries newest-first plus the aggregate track
 * record over exactly those entries. {@code configVersion} echoes the applied
 * filter (null = unfiltered); {@code configVersions} lists every version with
 * ledger history so the UI can offer the filter.
 */
public record LedgerResponse(
        List<LedgerEntryResponse> entries,
        LedgerStats stats,
        Integer configVersion,
        List<Integer> configVersions) {
}
