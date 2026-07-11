package com.terminalone.ledger;

import com.terminalone.engine.Recommendation;
import com.terminalone.engine.StrategyType;
import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class PaperTradeService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeService.class);
    private static final double OPTION_MULTIPLIER = 100.0;

    private final PaperTradeRepository paperTrades;
    private final MarketDataProvider marketData;
    private final Clock clock;

    public PaperTradeService(PaperTradeRepository paperTrades, MarketDataProvider marketData, Clock clock) {
        this.paperTrades = paperTrades;
        this.marketData = marketData;
        this.clock = clock;
    }

    @Transactional
    public PaperTrade createForRecommendation(Recommendation recommendation) {
        if (recommendation.getId() == null) {
            throw new IllegalArgumentException("recommendation must be persisted before creating a paper trade");
        }
        return paperTrades.findByRecommendation_Id(recommendation.getId())
                .orElseGet(() -> paperTrades.save(PaperTrade.fromRecommendation(recommendation, clock.instant())));
    }

    @Transactional
    public PaperTradeSettlementResult settleOpenTrades() {
        Instant markedAt = clock.instant();
        LocalDate asOfDate = LocalDate.now(clock);
        int marked = 0;
        int settled = 0;
        int skipped = 0;

        for (PaperTrade trade : paperTrades.findByStatusOrderByOpenedAtAsc(PaperTrade.Status.OPEN)) {
            OptionChain chain = currentChain(trade.getSymbol());
            if (chain == null || chain.underlyingPrice() <= 0.0) {
                skipped++;
                continue;
            }

            Optional<Double> markDebit = markDebit(trade.getRecommendation(), chain, asOfDate);
            if (markDebit.isEmpty()) {
                skipped++;
                continue;
            }

            double pnl = (markDebit.get() - trade.getEntryDebit().doubleValue())
                    * OPTION_MULTIPLIER * trade.getContracts();
            if (!asOfDate.isBefore(trade.getExpiry())) {
                trade.settle(markDebit.get(), pnl, markedAt);
                settled++;
            } else {
                trade.markOpen(markDebit.get(), pnl, markedAt);
                marked++;
            }
        }

        return new PaperTradeSettlementResult(marked, settled, skipped);
    }

    private OptionChain currentChain(String symbol) {
        try {
            return marketData.getChain(symbol);
        } catch (RuntimeException e) {
            log.warn("Could not settle paper trade for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Optional<Double> markDebit(Recommendation recommendation, OptionChain chain, LocalDate asOfDate) {
        boolean expired = !asOfDate.isBefore(recommendation.getExpiry());
        CallPut type = optionType(recommendation.getStrategy());
        double debit = 0.0;

        if (recommendation.getLongOptionSymbol() != null) {
            Optional<Double> mark = legMark(recommendation.getLongOptionSymbol(), recommendation.getLongStrike(),
                    recommendation.getExpiry(), type, chain, expired);
            if (mark.isEmpty()) {
                return Optional.empty();
            }
            debit += mark.get();
        }

        if (recommendation.getShortOptionSymbol() != null) {
            Optional<Double> mark = legMark(recommendation.getShortOptionSymbol(), recommendation.getShortStrike(),
                    recommendation.getExpiry(), type, chain, expired);
            if (mark.isEmpty()) {
                return Optional.empty();
            }
            debit -= mark.get();
        }

        return Optional.of(debit);
    }

    private Optional<Double> legMark(String optionSymbol,
            BigDecimal strike,
            LocalDate expiry,
            CallPut type,
            OptionChain chain,
            boolean expired) {
        if (expired) {
            return Optional.of(intrinsicValue(chain.underlyingPrice(), strike.doubleValue(), type));
        }
        return chain.contracts().stream()
                .filter(contract -> matches(contract, optionSymbol, strike.doubleValue(), expiry, type))
                .findFirst()
                .map(OptionContract::mid)
                .filter(mid -> mid >= 0.0 && Double.isFinite(mid));
    }

    private static boolean matches(OptionContract contract,
            String optionSymbol,
            double strike,
            LocalDate expiry,
            CallPut type) {
        return contract.optionSymbol().equals(optionSymbol)
                || (contract.callPut() == type
                        && contract.expiration().equals(expiry)
                        && Math.abs(contract.strike() - strike) < 0.0001);
    }

    private static double intrinsicValue(double spot, double strike, CallPut type) {
        return switch (type) {
            case CALL -> Math.max(0.0, spot - strike);
            case PUT -> Math.max(0.0, strike - spot);
        };
    }

    private static CallPut optionType(StrategyType strategy) {
        return switch (strategy) {
            case BULL_CALL_DEBIT_SPREAD, BEAR_CALL_CREDIT_SPREAD, LONG_CALL, COVERED_CALL -> CallPut.CALL;
            case BEAR_PUT_DEBIT_SPREAD, BULL_PUT_CREDIT_SPREAD, LONG_PUT, CASH_SECURED_PUT -> CallPut.PUT;
        };
    }
}
