package com.terminalone.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SignalSnapshotRepository extends JpaRepository<SignalSnapshot, Long> {

    List<SignalSnapshot> findByBatchRunIdOrderBySymbolAsc(Long batchRunId);
}
