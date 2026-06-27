package com.terminalone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Strongly-typed view of the {@code app.*} configuration namespace.
 * All values are sourced from env vars in production (12-factor); the
 * defaults in {@code application.yml} are dev-only.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, SeedUser seedUser, MarketData marketData) {

    public record Jwt(String secret, long expirationMinutes, String issuer) {
    }

    public record SeedUser(String username, String password) {
    }

    /**
     * MarketData.app provider settings (D3). {@code token} is empty in dev; set it
     * via env in production. {@code cacheTtl} gates the read-through cache (FR-7);
     * {@code chainDte}/{@code chainStrikeLimit} bound chain requests so they stay
     * cheap (near-the-money, one expiry window).
     */
    public record MarketData(
            String baseUrl,
            String token,
            Duration cacheTtl,
            int chainDte,
            int chainStrikeLimit) {
    }
}
