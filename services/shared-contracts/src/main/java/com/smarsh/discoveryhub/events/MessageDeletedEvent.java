package com.smarsh.discoveryhub.events;

import java.time.Instant;

/**
 * Published by <strong>archive-service</strong> onto {@link Topics#MESSAGE_DELETED}
 * once a message has actually been removed from the archive, and consumed by
 * <strong>search-service</strong> to drop the document from its index (FR-5.2).
 *
 * <p>Deletion is the one lifecycle step the index cannot infer for itself.
 * Without this event the two stores diverge permanently: a message disposed of
 * by the retention job stays searchable for ever, and following the hit returns
 * a 404 from the archive that no longer holds it.
 *
 * <p>Carried over Kafka rather than a direct call to search-service so that a
 * search outage <em>delays</em> the removal instead of losing it — the archive
 * has already deleted the message, so there is nothing to roll back to.
 *
 * @param messageId archive id of the deleted message
 * @param reason    why it went — {@code "disposition"} for the retention job,
 *                  {@code "api"} for an unqualified manual delete
 * @param deletedAt when archive removed it
 */
public record MessageDeletedEvent(
        String messageId,
        String reason,
        Instant deletedAt
) {
}
