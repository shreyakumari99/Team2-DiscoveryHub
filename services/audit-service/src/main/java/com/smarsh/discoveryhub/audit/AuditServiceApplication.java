package com.smarsh.discoveryhub.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Audit Service entry point.
 *
 * <p>Owns the {@code audit_db} Postgres database — an append-only log. It is the
 * <strong>only</strong> consumer of the shared {@code audit-events} Kafka topic
 * that every other service publishes to. Because no service writes into this
 * database directly, "no shared database schemas" (NFR-1) is preserved for
 * auditing: services emit events, audit-service appends them (FR-7).
 *
 * <p>The log is truly append-only: the JPA repository exposes only insert +
 * read methods (no save-update, no delete) — see {@link
 * com.smarsh.discoveryhub.audit.domain.AuditLogRepository}. Audit entries can
 * never be updated or deleted through any API (FR-7.3).
 */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
