package com.terminalone.engine.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EngineConfigRepository extends JpaRepository<EngineConfigVersion, Integer> {

    Optional<EngineConfigVersion> findByActiveTrue();
}
