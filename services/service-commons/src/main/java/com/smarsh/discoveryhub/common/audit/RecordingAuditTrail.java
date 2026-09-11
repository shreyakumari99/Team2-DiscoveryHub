package com.smarsh.discoveryhub.common.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An {@link AuditTrail} that keeps entries in memory instead of publishing
 * them, so a service's tests can assert "this action was audited" without a
 * Kafka broker.
 *
 * <p>Shipped in main rather than test sources on purpose: each service is an
 * independent Maven project, and depending on another project's test-jar would
 * force every service to build {@code service-commons} with its tests. This is
 * a test double and is not wired into any production context.
 */
public class RecordingAuditTrail implements AuditTrail {

    private final List<AuditRecord> entries = new ArrayList<>();

    @Override
    public void record(AuditRecord entry) {
        entries.add(entry);
    }

    public List<AuditRecord> entries() {
        return List.copyOf(entries);
    }

    public List<String> actions() {
        return entries.stream().map(AuditRecord::actionName).toList();
    }

    /** The first entry recorded for {@code action}, if any. */
    public Optional<AuditRecord> firstWithAction(String action) {
        return entries.stream().filter(e -> e.actionName().equals(action)).findFirst();
    }

    public boolean recorded(String action) {
        return entries.stream().anyMatch(e -> e.actionName().equals(action));
    }

    public void clear() {
        entries.clear();
    }
}
