package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Phase 5 AC5: strategy-matrix §7 position sizing. The defined-risk cap is
 * {@code perTradeRiskPct} of the portfolio, so a structure's per-contract max
 * loss fixes how many contracts the engine may recommend. If a single contract
 * already exceeds the cap, the engine abstains with {@code RISK_TOO_LARGE}
 * rather than recommend an oversized trade.
 */
class PositionSizerTest {

    @Test
    void sizesToWholeContractsUnderTheRiskCap() {
        // strategy-matrix §10 Example A: $50k book, 3% cap => $1,500 risk budget,
        // $320 per-contract max loss => floor(1500 / 320) = 4 contracts.
        PositionSizer.Sizing sizing = PositionSizer.size(320.0, 50_000.0, 0.03);

        assertThat(sizing.abstain()).isFalse();
        assertThat(sizing.contracts()).isEqualTo(4);
        assertThat(sizing.riskAmount()).isCloseTo(4 * 320.0, within(1e-9));
    }

    @Test
    void floorsContractsAtOneWhenTheCapCoversASingleContract() {
        // $40 risk budget, $30 per-contract loss => 1.33 -> 1 contract.
        PositionSizer.Sizing sizing = PositionSizer.size(30.0, 1_000.0, 0.04);

        assertThat(sizing.abstain()).isFalse();
        assertThat(sizing.contracts()).isEqualTo(1);
        assertThat(sizing.riskAmount()).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void fillsTheRiskBudgetWhenContractsDivideEvenly() {
        PositionSizer.Sizing sizing = PositionSizer.size(200.0, 10_000.0, 0.02);

        // $200 budget / $200 per contract => exactly 1 contract, capped.
        assertThat(sizing.abstain()).isFalse();
        assertThat(sizing.contracts()).isEqualTo(1);
    }

    @Test
    void abstainsWhenEvenOneContractExceedsTheCap() {
        // $1,500 cap, $2,000 per-contract max loss -> a single contract overdraws.
        PositionSizer.Sizing sizing = PositionSizer.size(2_000.0, 50_000.0, 0.03);

        assertThat(sizing.abstain()).isTrue();
        assertThat(sizing.contracts()).isZero();
        assertThat(sizing.reason()).isEqualTo(PositionSizer.RISK_TOO_LARGE);
    }

    @Test
    void abstainsWhenPortfolioValueIsZero() {
        PositionSizer.Sizing sizing = PositionSizer.size(200.0, 0.0, 0.03);

        assertThat(sizing.abstain()).isTrue();
        assertThat(sizing.reason()).isEqualTo(PositionSizer.RISK_TOO_LARGE);
    }

    @Test
    void abstainsWhenPerContractRiskIsNonPositive() {
        // Nothing to size — abstain defensively rather than divide by zero.
        PositionSizer.Sizing sizing = PositionSizer.size(0.0, 50_000.0, 0.03);

        assertThat(sizing.abstain()).isTrue();
        assertThat(sizing.reason()).isEqualTo(PositionSizer.RISK_TOO_LARGE);
    }
}