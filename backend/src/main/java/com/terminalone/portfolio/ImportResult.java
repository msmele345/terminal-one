package com.terminalone.portfolio;

import java.util.List;

/**
 * Outcome of a CSV import (FR-3). Carries the parsed (not-yet-persisted) positions
 * alongside per-row errors so malformed rows are surfaced, never silently dropped.
 */
public record ImportResult(
        List<StockPosition> stocks,
        List<OptionPosition> options,
        List<RowError> errors) {

    /** A single rejected row, identified by its 1-based line number in the file. */
    public record RowError(int line, String message) {
    }
}
