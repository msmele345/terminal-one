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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DB-backed config store (Phase 4 AC1/AC2, D21/FR-27–29): versioned,
 * immutable rows in engine_config; exactly one active; reads via the
 * {@link EngineConfigProvider} seam the engine depends on.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DbEngineConfigProvider.class, EngineConfigValidator.class,
        DbEngineConfigProviderTest.JacksonConfig.class})
class DbEngineConfigProviderTest {

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private DbEngineConfigProvider provider;

    @Autowired
    private EngineConfigRepository repository;

    @Test
    void activatedConfigIsReadBackAsTheActiveVersion() {
        EngineConfig defaults = EngineConfigDefaults.load();

        ActiveEngineConfig activated = provider.activate(defaults, "v1 seed from strategy-matrix §9");
        ActiveEngineConfig active = provider.getActive();

        assertThat(active.version()).isEqualTo(activated.version());
        assertThat(active.config()).isEqualTo(defaults);
    }

    @Test
    void activatingANewVersionLeavesExactlyOneActiveRow() {
        EngineConfig defaults = EngineConfigDefaults.load();
        ActiveEngineConfig v1 = provider.activate(defaults, "v1 seed");

        EngineConfig retuned = new EngineConfig(defaults.signal(), defaults.regime(),
                defaults.conviction(), defaults.strikes(), defaults.expiry(), defaults.filters(),
                new EngineConfig.Sizing(0.02), defaults.ranking());
        ActiveEngineConfig v2 = provider.activate(retuned, "drop risk cap to 2%");

        assertThat(v2.version()).isGreaterThan(v1.version());
        assertThat(provider.getActive().version()).isEqualTo(v2.version());
        assertThat(provider.getActive().config().sizing().perTradeRiskPct()).isEqualTo(0.02);
        assertThat(repository.findAll()).filteredOn(EngineConfigVersion::isActive).hasSize(1);
        assertThat(repository.count()).isEqualTo(2); // v1 kept, immutable, just inactive
    }

    @Test
    void anInvalidConfigIsRejectedAndNothingChanges() {
        EngineConfig defaults = EngineConfigDefaults.load();
        ActiveEngineConfig v1 = provider.activate(defaults, "v1 seed");

        EngineConfig invalid = new EngineConfig(defaults.signal(), defaults.regime(),
                defaults.conviction(), defaults.strikes(), defaults.expiry(), defaults.filters(),
                new EngineConfig.Sizing(0.90), defaults.ranking());

        assertThatThrownBy(() -> provider.activate(invalid, "way too risky"))
                .isInstanceOf(InvalidEngineConfigException.class)
                .hasMessageContaining("sizing.perTradeRiskPct");
        assertThat(provider.getActive().version()).isEqualTo(v1.version());
        assertThat(repository.count()).isEqualTo(1); // the invalid config was never persisted
    }

    @Test
    void getActiveFailsLoudlyWhenNoRowIsActive() {
        assertThatThrownBy(() -> provider.getActive())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active row");
    }
}
