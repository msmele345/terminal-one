package com.terminalone.engine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds engine_config v1 from the strategy-matrix §9 factory defaults at first
 * boot (FR-27, runbook §2). Idempotent: any existing version — active or not —
 * means seeding already happened, so later boots never touch the table and a
 * runbook retune is never disturbed.
 */
@Component
public class EngineConfigSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EngineConfigSeeder.class);

    private final EngineConfigRepository repository;
    private final DbEngineConfigProvider provider;

    public EngineConfigSeeder(EngineConfigRepository repository, DbEngineConfigProvider provider) {
        this.repository = repository;
        this.provider = provider;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("engine_config already has versions; skipping seed.");
            return;
        }
        ActiveEngineConfig seeded = provider.activate(
                EngineConfigDefaults.load(), "v1 seed from strategy-matrix §9 defaults");
        log.info("Seeded engine_config v{} from the §9 factory defaults.", seeded.version());
    }
}
