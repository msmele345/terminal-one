package com.terminalone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of the {@code app.*} configuration namespace.
 * All values are sourced from env vars in production (12-factor); the
 * defaults in {@code application.yml} are dev-only.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, SeedUser seedUser) {

    public record Jwt(String secret, long expirationMinutes, String issuer) {
    }

    public record SeedUser(String username, String password) {
    }
}
