package com.terminalone.whoami;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * The Phase 1 "trivial authenticated payload": echoes who you are + server time.
 * Proves the full stack (Electron → JWT → Spring → Postgres-backed identity).
 * No domain logic — replaced by real endpoints in later phases.
 */
@RestController
@RequestMapping("/api/whoami")
public class WhoamiController {

    @GetMapping
    public Map<String, Object> whoami(Authentication authentication) {
        return Map.of(
                "username", authentication.getName(),
                "authenticated", true,
                "serverTime", Instant.now().toString());
    }
}
