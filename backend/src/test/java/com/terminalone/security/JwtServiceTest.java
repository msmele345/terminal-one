package com.terminalone.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for JWT issuance/parsing. No Spring context.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-at-least-thirty-two-bytes-long-1234";
    private static final String ISSUER = "terminal-one-test";

    private JwtService service(long expirationMinutes) {
        return new JwtService(SECRET, expirationMinutes, ISSUER);
    }

    @Test
    void issuesTokenWhoseSubjectIsTheUsername() {
        JwtService jwt = service(60);

        String token = jwt.generateToken("trader");

        assertThat(token).isNotBlank();
        assertThat(jwt.extractUsername(token)).isEqualTo("trader");
    }

    @Test
    void freshlyIssuedTokenIsValid() {
        JwtService jwt = service(60);

        String token = jwt.generateToken("trader");

        assertThat(jwt.isValid(token)).isTrue();
    }

    @Test
    void expiredTokenIsNotValid() {
        // Negative expiry → token already expired at issue time.
        JwtService jwt = service(-1);

        String token = jwt.generateToken("trader");

        assertThat(jwt.isValid(token)).isFalse();
    }

    @Test
    void garbageTokenIsNotValid() {
        JwtService jwt = service(60);

        assertThat(jwt.isValid("not.a.jwt")).isFalse();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService issuer = service(60);
        JwtService otherSecret = new JwtService(
                "a-completely-different-secret-key-thirty-two-bytes!!", 60, ISSUER);

        String token = issuer.generateToken("trader");

        assertThat(otherSecret.isValid(token)).isFalse();
        assertThatThrownBy(() -> otherSecret.extractUsername(token))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void expiryIsRoughlyExpirationMinutesAhead() {
        JwtService jwt = service(120);

        String token = jwt.generateToken("trader");

        Instant expiry = jwt.extractExpiration(token);
        Instant expected = Instant.now().plus(Duration.ofMinutes(120));
        assertThat(expiry).isCloseTo(expected,
                org.assertj.core.api.Assertions.within(30, java.time.temporal.ChronoUnit.SECONDS));
    }
}
