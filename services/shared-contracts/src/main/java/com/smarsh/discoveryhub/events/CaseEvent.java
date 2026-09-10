package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.Map;

/**
 * Published by <strong>case-service</strong> onto {@link Topics#CASE_EVENTS}.
 * Consumed by hold-retention-service and export-service so they can react to
 * case lifecycle changes without case-service calling them synchronously.
 *
 * @param caseId     the case the event concerns
 * @param type       one of CREATED, UPDATED, TRANSITIONED, CLOSED,
 *                   CUSTODIAN_ADDED, EVIDENCE_ADDED, EVIDENCE_REMOVED
 * @param caseName   current case name
 * @param state      current case state
 * @param occurredAt when the event happened
 * @param details    free-form before/after context
 */
public record CaseEvent(
        String caseId,
        String type,
        String caseName,
        CaseState state,
        Instant occurredAt,
        Map<String, String> details
) {
}
