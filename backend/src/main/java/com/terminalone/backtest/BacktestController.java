package com.terminalone.backtest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Directional-signal backtest API (Phase 8 AC4, FR-19): runs the technical
 * signal over a symbol's historical bars and returns a hit-rate/return summary.
 * Authentication is handled globally.
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final SignalBacktestService service;

    public BacktestController(SignalBacktestService service) {
        this.service = service;
    }

    @PostMapping("/signal")
    public SignalBacktestSummary signal(@RequestBody(required = false) BacktestRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        try {
            return service.run(request.symbol().trim().toUpperCase(), request.horizonDays());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
