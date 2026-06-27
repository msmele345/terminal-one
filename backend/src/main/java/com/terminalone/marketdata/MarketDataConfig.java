package com.terminalone.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terminalone.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the market-data provider chain (D3, FR-6/FR-7):
 * <pre>
 *   MarketDataAppHttpClient → CachingMarketDataClient (TTL) → MarketDataAppProvider
 * </pre>
 * The HTTP client is the only piece that touches the network; the caching client
 * serves repeat calls from {@link MarketDataCache} (Postgres) within the TTL.
 */
@Configuration
public class MarketDataConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient marketDataRestClient(AppProperties props) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(props.marketData().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    MarketDataProvider marketDataProvider(RestClient marketDataRestClient, AppProperties props,
                                          MarketDataCache cache, Clock clock, ObjectMapper mapper) {
        AppProperties.MarketData cfg = props.marketData();
        MarketDataClient http = new MarketDataAppHttpClient(marketDataRestClient, cfg.token());
        MarketDataClient caching = new CachingMarketDataClient(http, cache, clock, cfg.cacheTtl());
        return new MarketDataAppProvider(caching, mapper, cfg.chainDte(), cfg.chainStrikeLimit());
    }
}
