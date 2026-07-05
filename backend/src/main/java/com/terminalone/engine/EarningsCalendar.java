package com.terminalone.engine;

import java.time.LocalDate;

interface EarningsCalendar {

    EarningsCheck check(String symbol, LocalDate asOf, LocalDate expiry);

    static EarningsCalendar unavailable() {
        return (symbol, asOf, expiry) -> EarningsCheck.unavailable();
    }
}
