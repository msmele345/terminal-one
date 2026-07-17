package com.terminalone.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EngineBatchRunRepository extends JpaRepository<EngineBatchRun, Long> {

    Optional<EngineBatchRun> findTopByOrderByStartedAtDesc();

    Optional<EngineBatchRun> findTopByKindOrderByStartedAtDesc(EngineBatchKind kind);
}
