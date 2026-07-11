package com.terminalone.portfolio;

import com.terminalone.portfolio.PositionPnl.Valuation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pure P&L math (FR-5): delayed market value and unrealized P&L ($ and %) for a
 * single position. Every case here is a deterministic function of inputs — no
 * market-data or persistence in sight.
 */
class PositionPnlTest {

    @Test
    void stockGain() {
        // 100 shares bought at 150, now 165 → +1500 (+10%)
        Valuation v = PositionPnl.forStock(bd("100"), bd("150"), 165.0);
        assertThat(v.marketValue()).isEqualTo(16_500.0);
        assertThat(v.costValue()).isEqualTo(15_000.0);
        assertThat(v.unrealizedPnl()).isEqualTo(1_500.0);
        assertThat(v.pnlPct()).isEqualTo(10.0);
    }

    @Test
    void stockLoss() {
        // 10 shares bought at 100, now 90 → -100 (-10%)
        Valuation v = PositionPnl.forStock(bd("10"), bd("100"), 90.0);
        assertThat(v.unrealizedPnl()).isEqualTo(-100.0);
        assertThat(v.pnlPct()).isEqualTo(-10.0);
    }

    @Test
    void longOptionUsesHundredMultiplier() {
        // 2 contracts bought at 5.00, now mid 6.50 → +300 (+30%)
        Valuation v = PositionPnl.forOption(bd("2"), bd("5.00"), PositionSide.LONG, 6.50);
        assertThat(v.marketValue()).isEqualTo(1_300.0);
        assertThat(v.costValue()).isEqualTo(1_000.0);
        assertThat(v.unrealizedPnl()).isEqualTo(300.0);
        assertThat(v.pnlPct()).isEqualTo(30.0);
    }

    @Test
    void shortOptionProfitsWhenMarkFalls() {
        // 1 contract sold at 5.00 (credit), now mid 2.00 → +300 (+60% of premium)
        Valuation v = PositionPnl.forOption(bd("1"), bd("5.00"), PositionSide.SHORT, 2.00);
        assertThat(v.unrealizedPnl()).isEqualTo(300.0);
        // Signed exposure: a short is a liability (negative MV) financed by the credit basis.
        assertThat(v.marketValue()).isEqualTo(-200.0);
        assertThat(v.costValue()).isEqualTo(-500.0);
        assertThat(v.pnlPct()).isEqualTo(60.0);
    }

    @Test
    void shortOptionLosesWhenMarkRises() {
        // 1 contract sold at 2.00, now mid 3.50 → -150 (-75% of premium)
        Valuation v = PositionPnl.forOption(bd("1"), bd("2.00"), PositionSide.SHORT, 3.50);
        assertThat(v.unrealizedPnl()).isCloseTo(-150.0, offset(1e-9));
        assertThat(v.pnlPct()).isCloseTo(-75.0, offset(1e-9));
    }

    @Test
    void zeroBasisYieldsNullPercent() {
        // A free position has no meaningful return-on-cost; % is undefined, not 0/∞.
        Valuation v = PositionPnl.forStock(bd("10"), bd("0"), 5.0);
        assertThat(v.unrealizedPnl()).isEqualTo(50.0);
        assertThat(v.pnlPct()).isNull();
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
