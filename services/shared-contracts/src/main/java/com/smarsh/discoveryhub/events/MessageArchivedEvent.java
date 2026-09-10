package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

/**
 * Published by <strong>archive-service</strong> onto {@link Topics#MESSAGE_ARCHIVED}
 * once a message has been durably stored and given its immutable id.
 * Consumed by <strong>search-service</strong> (to index) and optionally by
 * <strong>hold-retention-service</strong>.
 *
 * @param messageId           immutable id assigned by archive-service (FR-1.5)
 * @param sourceMessageId     original idempotency key
 * @param type                EMAIL or CHAT
 * @param subject             message subject
 * @param body                message body
 * @param timestamp           original send timestamp
 * @param sender              sending custodian
 * @param participants        all participants
 * @param threadId            conversation thread this message belongs to
 * @param attachmentObjectKeys object keys of attachments stored in the archive bucket
 * @param hasAttachment       true if the message has at least one attachment
 * @param held                current hold status at time of archival
 * @param archivedAt          when archive-service stored it
 */
public record MessageArchivedEvent(
        String messageId,
        String sourceMessageId,
        MessageType type,
        String subject,
        String body,
        Instant timestamp,
        String sender,
        List<String> participants,
        String threadId,
        List<String> attachmentObjectKeys,
        boolean hasAttachment,
        boolean held,
        Instant archivedAt
) {
}
