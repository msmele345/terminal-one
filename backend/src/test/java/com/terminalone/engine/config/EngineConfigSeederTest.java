package com.terminalone.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-boot seeding (Phase 4 AC1): version 1 is inserted from the §9 factory
 * defaults exactly once; subsequent boots never re-seed, overwrite, or flip
 * the active version (runbook §8 — the defaults are a baseline, not a reset).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EngineConfigSeeder.class, DbEngineConfigProvider.class, EngineConfigValidator.class,
        EngineConfigSeederTest.JacksonConfig.class})
class EngineConfigSeederTest {

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private DbEngineConfigProvider provider;

    @Autowired
    private EngineConfigRepository repository;

    @Test
    void seedsVersion1FromTheFactoryDefaultsOnFirstBoot() {
        seeder.run();

        ActiveEngineConfig active = provider.getActive();
        assertThat(active.version()).isEqualTo(1);
        assertThat(active.config()).isEqualTo(EngineConfigDefaults.load());
    }

    @Test
    void aSecondBootDoesNotReseedOrDisturbTheActiveVersion() {
        seeder.run();
        provider.activate(EngineConfigDefaults.load(), "v2 retune");

        seeder.run();

        assertThat(repository.count()).isEqualTo(2);
        assertThat(provider.getActive().version()).isEqualTo(2);
    }
}
