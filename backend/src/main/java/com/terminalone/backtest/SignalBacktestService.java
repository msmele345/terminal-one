package com.terminalone.backtest;

import com.terminalone.engine.config.EngineConfig;
import com.terminalone.engine.config.EngineConfigProvider;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.PriceHistory;
import org.springframework.stereotype.Service;

/**
 * Wires the pure {@link SignalBacktester} to live inputs: historical daily bars
 * from the {@link MarketDataProvider} and the active signal config from the
 * {@link EngineConfigProvider} (Phase 8 AC4, FR-19). The backtest therefore
 * measures the same signal the engine currently trades on.
 */
@Service
public class SignalBacktestService {

    /** Default forward window (a trading week) when the request omits one. */
    static final int DEFAULT_HORIZON_DAYS = 5;

    private final MarketDataProvider marketData;
    private final EngineConfigProvider configProvider;
    private final SignalBacktester backtester;

    public SignalBacktestService(MarketDataProvider marketData,
            EngineConfigProvider configProvider,
            SignalBacktester backtester) {
        this.marketData = marketData;
        this.configProvider = configProvider;
        this.backtester = backtester;
    }

    public SignalBacktestSummary run(String symbol, Integer horizonDays) {
        int horizon = horizonDays == null ? DEFAULT_HORIZON_DAYS : horizonDays;
        if (horizon < 1) {
            throw new IllegalArgumentException("horizonDays must be >= 1");
        }
        PriceHistory history = marketData.getDailyBars(symbol);
        EngineConfig.Signal signalConfig = configProvider.getActive().config().signal();
        return backtester.backtest(history, signalConfig, horizon);
    }
}
