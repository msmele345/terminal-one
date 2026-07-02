package com.terminalone.engine.config;

/**
 * The seam the recommendation engine reads its tuning through (D21/FR-27).
 * Read at the start of every run — EOD batch or lever-pull — so a config
 * change takes effect on the next run with no redeploy.
 */
public interface EngineConfigProvider {

    /** The single active config version. Fails loudly if none is active (seed missing). */
    ActiveEngineConfig getActive();
}
