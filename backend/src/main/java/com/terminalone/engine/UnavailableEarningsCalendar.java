package com.terminalone.engine;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
class UnavailableEarningsCalendar implements EarningsCalendar {

    @Override
    public EarningsCheck check(String symbol, LocalDate asOf, LocalDate expiry) {
        return EarningsCheck.unavailable();
    }
}
