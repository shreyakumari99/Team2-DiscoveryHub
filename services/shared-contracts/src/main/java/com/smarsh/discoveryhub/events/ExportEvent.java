package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.Map;

/**
 * Published by <strong>export-service</strong> onto {@link Topics#EXPORT_EVENTS}
 * as an export job moves through its lifecycle. Allows the UI (and other
 * services) to track progress asynchronously.
 *
 * @param jobId      export job id
 * @param caseId     case the export belongs to
 * @param type       REQUESTED, STARTED, COMPLETED, FAILED
 * @param status     current status label (QUEUED, RUNNING, COMPLETED, FAILED)
 * @param occurredAt when the event happened
 * @param details    extra context (item counts, package checksum, failure reason...)
 */
public record ExportEvent(
        String jobId,
        String caseId,
        String type,
        String status,
        Instant occurredAt,
        Map<String, String> details
) {
}
