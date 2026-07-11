package com.terminalone.ledger;

public record PaperTradeSettlementResult(int markedCount, int settledCount, int skippedCount) {
}
