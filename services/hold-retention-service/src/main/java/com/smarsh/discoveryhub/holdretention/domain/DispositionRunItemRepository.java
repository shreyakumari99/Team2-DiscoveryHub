package com.smarsh.discoveryhub.holdretention.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Per-message disposition outcomes (FR-5.3). Insert and read only — a
 * disposition record that could be edited afterwards would be worthless as
 * evidence.
 */
@Repository
public interface DispositionRunItemRepository extends JpaRepository<DispositionRunItem, String> {

    List<DispositionRunItem> findByRunId(String runId);

    List<DispositionRunItem> findByRunIdAndOutcome(String runId, DispositionOutcome outcome);
}
