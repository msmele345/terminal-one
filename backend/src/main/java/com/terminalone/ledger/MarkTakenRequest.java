package com.terminalone.ledger;

/**
 * Request body for {@code POST /api/recommendations/{id}/take} (Phase 8 AC3).
 *
 * @param fillPrice the manually entered net fill price (OQ-8); sign follows the
 *                  entry-debit convention (&gt;0 debit paid, &lt;0 credit received)
 */
public record MarkTakenRequest(Double fillPrice) {
}
