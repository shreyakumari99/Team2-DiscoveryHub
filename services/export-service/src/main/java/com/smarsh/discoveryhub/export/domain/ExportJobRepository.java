package com.smarsh.discoveryhub.export.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ExportJobRepository extends JpaRepository<ExportJob, String> {

    List<ExportJob> findByCaseIdOrderByRequestedAtDesc(String caseId);

    /** Backs the stuck-job reaper: runs that started before a cutoff and never finished. */
    List<ExportJob> findByStatusAndStartedAtBefore(ExportStatus status, Instant cutoff);

    /** Jobs whose worker never picked them up at all — stranded in QUEUED. */
    List<ExportJob> findByStatusAndRequestedAtBefore(ExportStatus status, Instant cutoff);

    long countByStatus(ExportStatus status);
}
