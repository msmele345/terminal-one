package com.terminalone.engine.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only V1 config API (route list: GET /api/config/active): JWT
 * gating and the JSON shape of the seeded v1. Seeding is re-run per test
 * (idempotent) because sibling test contexts sharing the H2 database recreate
 * the schema and would otherwise wipe the boot-time seed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class EngineConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @BeforeEach
    void seed() {
        seeder.run();
    }

    @Test
    void activeConfigRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/config/active").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheSeededV1ConfigAsTheActiveVersion() throws Exception {
        mockMvc.perform(get("/api/config/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.config.sizing.perTradeRiskPct").value(0.03))
                .andExpect(jsonPath("$.config.ranking.topN").value(3))
                .andExpect(jsonPath("$.config.regime.metric").value("IV_RANK"));
    }
}
