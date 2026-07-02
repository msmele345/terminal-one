package com.terminalone.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code stub} profile swaps the market-data source (Phase 3 AC 6.5):
 * with it active, the injected {@link MarketDataProvider} is the offline
 * {@link StubMarketDataProvider}, not the real MarketData.app adapter.
 */
@SpringBootTest
@ActiveProfiles({"test", "stub"})
class StubProfileWiringTest {

    @Autowired
    private MarketDataProvider provider;

    @Test
    void stubProfileWiresTheOfflineProvider() {
        assertThat(provider).isInstanceOf(StubMarketDataProvider.class);
    }
}
