package com.terminalone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigDefaults;
import com.terminalone.marketdata.CallPut;

/**
 * Phase 5 AC6 (§8): the pure ranking objective — sort surviving candidates by
 * {@code score = rawEV × regimeFit × convFactor} descending, drop non-positive-EV
 * candidates when {@code requirePositiveEV} is set, surface the global top-N
 * across all underlyings, and break score ties by higher POP → tighter
 * liquidity (smaller relative bid/ask) → better reward:risk.
 */
class CandidateRankerTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);
    private final EngineConfig.Ranking ranking = EngineConfigDefaults.load().ranking();

    @Test
    void ranksAnEmptyListToAnEmptyList() {
        assertThat(CandidateRanker.rank(List.of(), ranking)).isEmpty();
    }

    @Test
    void ordersCandidatesByScoreDescending() {
        RecommendationCandidate high = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD, 100.0, 0.7, 2.0);
        RecommendationCandidate mid = candidate("MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD, 60.0, 0.7, 2.0);
        RecommendationCandidate low = candidate("TSLA", StrategyType.BULL_CALL_DEBIT_SPREAD, 20.0, 0.7, 2.0);

        List<RecommendationCandidate> ranked = CandidateRanker.rank(List.of(low, high, mid), ranking);

        assertThat(ranked).extracting(RecommendationCandidate::symbol)
                .containsExactly("AAPL", "MSFT", "TSLA");
    }

    @Test
    void truncatesToTopN() {
        // topN default = 3 — five candidates present, keep only the top 3 by score.
        List<RecommendationCandidate> candidates = List.of(
                candidate("Z", StrategyType.BULL_CALL_DEBIT_SPREAD, 50.0, 0.7, 2.0),
                candidate("A", StrategyType.BULL_CALL_DEBIT_SPREAD, 10.0, 0.7, 2.0),
                candidate("C", StrategyType.BULL_CALL_DEBIT_SPREAD, 30.0, 0.7, 2.0),
                candidate("B", StrategyType.BULL_CALL_DEBIT_SPREAD, 20.0, 0.7, 2.0),
                candidate("D", StrategyType.BULL_CALL_DEBIT_SPREAD, 40.0, 0.7, 2.0));

        List<RecommendationCandidate> ranked = CandidateRanker.rank(candidates, ranking);

        assertThat(ranked).extracting(RecommendationCandidate::symbol)
                .containsExactly("Z", "D", "C");
    }

    @Test
    void dropsNonPositiveEVCandidatesWhenRequirePositiveEvisTrue() {
        EngineConfig.Ranking strict = new EngineConfig.Ranking(ranking.topN(),
                ranking.regimeFitMatch(), ranking.regimeFitNeutral(),
                ranking.regimeFitCounter(), true);
        RecommendationCandidate positive = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.7, 2.0);
        RecommendationCandidate zeroEv = candidate("MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.7, 2.0, 0.0);
        RecommendationCandidate negativeEv = candidate("TSLA", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.7, 2.0, -10.0);

        List<RecommendationCandidate> ranked = CandidateRanker.rank(
                List.of(positive, zeroEv, negativeEv), strict);

        assertThat(ranked).extracting(RecommendationCandidate::symbol).containsExactly("AAPL");
    }

    @Test
    void retainsNonPositiveEVCandidatesWhenRequirePositiveEvisFalse() {
        EngineConfig.Ranking lenient = new EngineConfig.Ranking(ranking.topN(),
                ranking.regimeFitMatch(), ranking.regimeFitNeutral(),
                ranking.regimeFitCounter(), false);
        RecommendationCandidate positive = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.7, 2.0);
        RecommendationCandidate negative = candidate("TSLA", StrategyType.BULL_CALL_DEBIT_SPREAD,
                30.0, 0.7, 2.0, -10.0);

        List<RecommendationCandidate> ranked = CandidateRanker.rank(List.of(positive, negative), lenient);

        assertThat(ranked).extracting(RecommendationCandidate::symbol).containsExactly("AAPL", "TSLA");
    }

    @Test
    void tiebreaksOnHigherPopWhenScoresAreEqual() {
        RecommendationCandidate higherPop = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.75, 2.0);
        RecommendationCandidate lowerPop = candidate("MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.65, 2.0);
        // both legs identical bid/ask so liquidity is equal; riskReward equal too.

        List<RecommendationCandidate> ranked = CandidateRanker.rank(List.of(lowerPop, higherPop), ranking);

        assertThat(ranked).extracting(RecommendationCandidate::symbol).containsExactly("AAPL", "MSFT");
    }

    @Test
    void tiebreaksOnTighterLiquidityWhenScoreAndPopAreEqual() {
        // identical score/POP/risk-reward; AAPL has tighter (smaller) relative spread.
        RecommendationCandidate tight = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.70, 2.0, 5.0, 5.10, 5.05, 1.50, 1.55, 1.525);
        RecommendationCandidate wide = candidate("MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.70, 2.0, 5.00, 5.20, 5.10, 1.50, 1.70, 1.60);

        List<RecommendationCandidate> ranked = CandidateRanker.rank(List.of(wide, tight), ranking);

        assertThat(ranked).extracting(RecommendationCandidate::symbol).containsExactly("AAPL", "MSFT");
    }

    @Test
    void tiebreaksOnBetterRiskRewardWhenScorePopAndLiquidityAreEqual() {
        // identical score/POP and equal legs, so liquidity ties; reward:risk breaks it.
        RecommendationCandidate better = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.70, 2.5);
        RecommendationCandidate worse = candidate("MSFT", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.70, 1.5);

        List<RecommendationCandidate> ranked = CandidateRanker.rank(List.of(worse, better), ranking);

        assertThat(ranked).extracting(RecommendationCandidate::symbol).containsExactly("AAPL", "MSFT");
    }

    @Test
    void topNZeroReturnsEmptyList() {
        EngineConfig.Ranking zero = new EngineConfig.Ranking(0,
                ranking.regimeFitMatch(), ranking.regimeFitNeutral(),
                ranking.regimeFitCounter(), false);
        RecommendationCandidate c = candidate("AAPL", StrategyType.BULL_CALL_DEBIT_SPREAD,
                50.0, 0.7, 2.0);

        assertThat(CandidateRanker.rank(List.of(c), zero)).isEmpty();
    }

    // ---- fixtures ----

    private static RecommendationCandidate candidate(String symbol, StrategyType strategy,
                                                       double score, double pop, double riskReward) {
        return candidate(symbol, strategy, score, pop, riskReward, 50.0,
                5.0, 5.10, 5.05, 1.50, 1.55, 1.525);
    }

    private static RecommendationCandidate candidate(String symbol, StrategyType strategy,
                                                       double score, double pop, double riskReward,
                                                       double rawEv) {
        return candidate(symbol, strategy, score, pop, riskReward, rawEv,
                5.0, 5.10, 5.05, 1.50, 1.55, 1.525);
    }

    private static RecommendationCandidate candidate(String symbol, StrategyType strategy,
                                                       double score, double pop, double riskReward,
                                                       double longBid, double longAsk, double longMid,
                                                       double shortBid, double shortAsk, double shortMid) {
        return candidate(symbol, strategy, score, pop, riskReward, 50.0,
                longBid, longAsk, longMid, shortBid, shortAsk, shortMid);
    }

    private static RecommendationCandidate candidate(String symbol, StrategyType strategy,
                                                       double score, double pop, double riskReward,
                                                       double rawEv,
                                                       double longBid, double longAsk, double longMid,
                                                       double shortBid, double shortAsk, double shortMid) {
        DirectionSignal signal = new DirectionSignal(Direction.BULLISH, 60,
                0.75, 0.8, 0.7, 0.6, 102.0, 100.0, 0.5, 55.0);
        VolatilityRegimeResult regime = VolatilityRegimeResult.phase4Normal(null);
        RecommendationLeg longLeg = new RecommendationLeg(
                "BUY", symbol + "-100C", CallPut.CALL, 100.0, EXPIRY, longBid, longAsk, longMid, 0.55);
        RecommendationLeg shortLeg = new RecommendationLeg(
                "SELL", symbol + "-110C", CallPut.CALL, 110.0, EXPIRY, shortBid, shortAsk, shortMid, 0.30);
        double maxLoss = 100.0;
        double maxProfit = riskReward * maxLoss;
        RecommendationRationale rationale = new RecommendationRationale(
                new RecommendationRationale.Signals(0.8, 0.7, 0.6, 0.75, 60, 102.0, 100.0, 0.5, 55.0),
                new RecommendationRationale.Regime(VolatilityRegime.NORMAL, "TEST", null),
                new RecommendationRationale.Selection("standard", 51, 0.55, 0.30, 0.55, 0.30),
                new RecommendationRationale.Pricing(10.0, 3.50, 103.50, pop, maxProfit, maxLoss,
                        riskReward, rawEv),
                null);
        return new RecommendationCandidate(symbol, strategy, signal, regime, EXPIRY,
                List.of(longLeg, shortLeg), 3.50, pop, maxProfit, maxLoss,
                riskReward, score, 0, rationale);
    }
}