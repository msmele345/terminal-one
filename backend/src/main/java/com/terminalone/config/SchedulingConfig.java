package com.terminalone.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled-task support. V1's only scheduled job is the daily
 * ATM IV accumulation (Phase 3 AC5); the EOD engine batch (Phase 6) joins it here.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
