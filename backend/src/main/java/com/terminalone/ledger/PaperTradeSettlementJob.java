package com.terminalone.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaperTradeSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeSettlementJob.class);

    private final PaperTradeService paperTrades;

    public PaperTradeSettlementJob(PaperTradeService paperTrades) {
        this.paperTrades = paperTrades;
    }

    /** Scheduled post-close paper-ledger settlement (17:00 ET, weekdays). */
    @Scheduled(cron = "${app.ledger.settlement-cron:0 0 17 * * MON-FRI}", zone = "America/New_York")
    void scheduledSettlement() {
        PaperTradeSettlementResult result = paperTrades.settleOpenTrades();
        log.info("Paper-trade settlement completed: {} marked, {} settled, {} skipped",
                result.markedCount(), result.settledCount(), result.skippedCount());
    }
}
