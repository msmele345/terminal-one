package com.terminalone.engine;

import com.terminalone.marketdata.CallPut;

import java.time.LocalDate;

public record RecommendationLeg(
        String action,
        String optionSymbol,
        CallPut callPut,
        double strike,
        LocalDate expiry,
        double bid,
        double ask,
        double mid,
        double delta) {
}
