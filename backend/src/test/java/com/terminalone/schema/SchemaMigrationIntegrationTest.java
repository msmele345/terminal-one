package com.terminalone.schema;

import com.terminalone.engine.config.EngineConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema gate: boots the whole application against a real Postgres exactly
 * the way Railway does — Flyway applies every migration, then Hibernate runs
 * {@code ddl-auto=validate} against the result.
 *
 * <p>The rest of the suite runs on H2 with Flyway disabled and the schema
 * generated from the entities, which is fast but structurally blind to the two
 * failures that actually reach production: a migration that does not apply, and
 * an entity that has drifted from the migrated schema. Both fail the build here
 * instead of at container start.
 *
 * <p>Requires a running Docker daemon (already a documented prerequisite for
 * local dev, and present on GitHub-hosted runners). It is deliberately not
 * skipped when Docker is absent: a gate that can silently skip is not a gate.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("schema-gate")
@DisplayName("Flyway migrations + JPA schema validation on real Postgres")
class SchemaMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EngineConfigRepository engineConfigs;

    /**
     * Context startup is the assertion: it only completes if Flyway applied the
     * migrations and {@code ddl-auto=validate} matched every entity against the
     * resulting schema. The explicit checks below pin why it passed.
     */
    @Test
    @DisplayName("every migration applies cleanly and the entities validate against the result")
    void migrationsApplyAndEntitiesValidate() {
        List<String> failed = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class);
        assertThat(failed).isEmpty();

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true AND type = 'SQL'",
                Integer.class);
        assertThat(applied)
                .as("every versioned migration in db/migration ran")
                .isEqualTo(countMigrationScripts());
    }

    @Test
    @DisplayName("the tables the app depends on exist after migration")
    void migratedSchemaContainsTheApplicationTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "app_user", "positions", "option_positions", "market_data_cache", "iv_history",
                "engine_config", "recommendations", "signal_snapshots", "engine_batch_runs",
                "paper_trades", "taken_positions");
    }

    /**
     * V5's partial unique index is Postgres-only DDL with no H2 equivalent, so
     * this invariant — the database itself caps active configs at one, backing
     * up the app's transactional flip — is unprovable anywhere but here.
     */
    @Test
    @DisplayName("the database itself rejects a second active engine_config")
    void partialUniqueIndexEnforcesASingleActiveConfig() {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM engine_config WHERE is_active", Integer.class);
        assertThat(active).as("v1 is seeded active at first boot").isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO engine_config (config, is_active, note) VALUES ('{}'::jsonb, true, ?)",
                "second active row must be impossible"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(engineConfigs.findByActiveTrue()).isPresent();
    }

    private static int countMigrationScripts() {
        try {
            var dir = java.nio.file.Path.of("src/main/resources/db/migration");
            try (var files = java.nio.file.Files.list(dir)) {
                return (int) files.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not enumerate migration scripts", e);
        }
    }
}
