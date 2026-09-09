package com.smarsh.discoveryhub.audit.domain;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/**
 * Builds the dynamic filter for an audit-trail query (FR-7.4).
 *
 * <p>Specifications rather than a JPQL query with {@code :param IS NULL}
 * guards. That pattern reads well but breaks on PostgreSQL: an unused
 * {@code Instant} parameter is sent untyped and the server rejects the
 * statement with "could not determine data type of parameter". H2 accepts it,
 * so the fault only appears against the real database. Specifications sidestep
 * it entirely by omitting the predicate instead of binding a null.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    /**
     * Every argument is optional; a null or blank one contributes no predicate.
     *
     * @param from inclusive lower bound on the entry timestamp
     * @param to   inclusive upper bound on the entry timestamp
     */
    public static Specification<AuditLogEntry> matching(String caseId,
                                                        String action,
                                                        String actor,
                                                        String entityType,
                                                        String entityId,
                                                        Instant from,
                                                        Instant to) {
        return Specification.allOf(
                equals("caseId", caseId),
                equals("action", action),
                equals("actor", actor),
                equals("entityType", entityType),
                equals("entityId", entityId),
                atOrAfter(from),
                atOrBefore(to));
    }

    private static Specification<AuditLogEntry> equals(String attribute, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    private static Specification<AuditLogEntry> atOrAfter(Instant from) {
        if (from == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("timestamp"), from);
    }

    private static Specification<AuditLogEntry> atOrBefore(Instant to) {
        if (to == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("timestamp"), to);
    }
}
