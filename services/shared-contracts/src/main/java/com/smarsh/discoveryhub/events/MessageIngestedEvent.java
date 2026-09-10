package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

/**
 * Published by <strong>ingestion-service</strong> onto {@link Topics#MESSAGE_INGESTED}
 * and consumed by <strong>archive-service</strong>.
 * <p>
 * Carries everything Archive needs to durably store a message and assign its
 * immutable ID. {@code sourceMessageId} is the idempotency key: re-submitting
 * the same message must not create a duplicate (FR-1.6).
 *
 * @param sourceMessageId idempotency key from the source system / client
 * @param type            EMAIL or CHAT
 * @param subject         message subject (may be null for chat)
 * @param body            full message body
 * @param timestamp       when the original message was sent
 * @param sender          sending custodian
 * @param participants    all participants (including sender)
 * @param threadId        optional thread/conversation id
 * @param attachments     attachment payloads (inline base64); empty if none
 */
public record MessageIngestedEvent(
        String sourceMessageId,
        MessageType type,
        String subject,
        String body,
        Instant timestamp,
        String sender,
        List<String> participants,
        String threadId,
        List<AttachmentPayload> attachments
) {
}
