package com.smarsh.discoveryhub.common.audit;

/**
 * Appends entries to the platform-wide audit trail (FR-7).
 *
 * <p>Every service depends on this interface rather than on Kafka directly, so
 * a unit test can substitute a recording fake and assert on what was audited
 * without a broker. The only production implementation publishes to the shared
 * {@code audit-events} topic; audit-service is its sole consumer, which is how
 * the append-only guarantee is kept without any service writing into another
 * service's database (NFR-1).
 */
@FunctionalInterface
public interface AuditTrail {

    /**
     * Append one entry. Implementations must never throw: a failure to audit
     * must not fail the business operation that triggered it, and must not
     * cascade (NFR-2). Failures are logged instead.
     */
    void record(AuditRecord record);
}
