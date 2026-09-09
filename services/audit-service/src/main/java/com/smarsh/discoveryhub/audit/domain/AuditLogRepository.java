package com.smarsh.discoveryhub.audit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link AuditLogEntry}.
 *
 * <p><b>Append-only by design (FR-7.3).</b> It extends {@link JpaRepository}
 * so Spring can build the bean, but only {@code save} (insert) and reads are
 * ever called. The guarantee is also enforced at the database:
 * {@code data-postgresql.sql} installs triggers that reject UPDATE, DELETE and
 * TRUNCATE on {@code audit_log}, so even a future bug — or a hand-typed query
 * — cannot rewrite the trail.
 *
 * <p>Filtered search (FR-7.4) goes through {@link JpaSpecificationExecutor}
 * and {@link AuditLogSpecifications}; see that class for why it is not a JPQL
 * query with null-guard parameters.
 */
@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLogEntry, String>, JpaSpecificationExecutor<AuditLogEntry> {

    List<AuditLogEntry> findByCaseIdOrderByTimestampDesc(String caseId);

    List<AuditLogEntry> findAllByOrderByTimestampDesc();

    /** Distinct action names, so the UI can offer a filter without hard-coding them. */
    @Query("SELECT DISTINCT e.action FROM AuditLogEntry e ORDER BY e.action")
    List<String> findDistinctActions();
}
