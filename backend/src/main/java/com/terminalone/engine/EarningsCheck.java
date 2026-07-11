package com.terminalone.engine;

import java.time.LocalDate;

record EarningsCheck(Status status, LocalDate earningsDate, String detail) {

    enum Status {
        CLEAR,
        SPANS_EARNINGS,
        UNAVAILABLE
    }

    static EarningsCheck clear() {
        return new EarningsCheck(Status.CLEAR, null, null);
    }

    static EarningsCheck spans(LocalDate earningsDate) {
        return new EarningsCheck(Status.SPANS_EARNINGS, earningsDate,
                "expiry spans earnings date %s".formatted(earningsDate));
    }

    static EarningsCheck unavailable() {
        return new EarningsCheck(Status.UNAVAILABLE, null,
                "earnings calendar unavailable");
    }

    static EarningsCheck fromKnownDate(LocalDate earningsDate, LocalDate asOf, LocalDate expiry) {
        if (earningsDate == null) {
            return unavailable();
        }
        if (!earningsDate.isBefore(asOf) && !earningsDate.isAfter(expiry)) {
            return spans(earningsDate);
        }
        return clear();
    }
}
