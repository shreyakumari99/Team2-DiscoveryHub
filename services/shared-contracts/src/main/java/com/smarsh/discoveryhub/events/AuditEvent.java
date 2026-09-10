package com.smarsh.discoveryhub.events;

import java.time.Instant;

/**
 * The single shared audit event every service emits onto
 * {@link Topics#AUDIT_EVENTS}. Consumed only by <strong>audit-service</strong>,
 * which appends it to its append-only log. This is how "no service writes into
 * another's database" is preserved for auditing (FR-7): services emit events,
 * they do not touch the audit DB.
 *
 * @param eventId     unique id for this audit entry
 * @param timestamp   when the action occurred
 * @param actor       who performed it (investigator id / system)
 * @param service     which service emitted the event
 * @param action      canonical action name, e.g. CASE_CREATED, HOLD_PLACED
 * @param entityType  CASE, HOLD, MESSAGE, EXPORT, CUSTODIAN, EVIDENCE...
 * @param entityId    id of the targeted entity
 * @param caseId      related case id, nullable
 * @param beforeJson  JSON snapshot of state before (nullable)
 * @param afterJson   JSON snapshot of state after (nullable)
 */
public record AuditEvent(
        String eventId,
        Instant timestamp,
        String actor,
        String service,
        String action,
        String entityType,
        String entityId,
        String caseId,
        String beforeJson,
        String afterJson
) {
}
