package com.terminalone.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger API (Phase 8 AC2, FR-22): the paper/taken track record.
 * Authentication is handled globally.
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    public LedgerResponse ledger(@RequestParam(required = false) Integer configVersion) {
        return this.ledger.ledger(configVersion);
    }
}
