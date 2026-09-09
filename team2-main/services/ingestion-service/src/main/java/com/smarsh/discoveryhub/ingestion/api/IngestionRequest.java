package com.smarsh.discoveryhub.ingestion.api;

import com.smarsh.discoveryhub.events.AttachmentPayload;
import com.smarsh.discoveryhub.events.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/messages}. The {@code sourceMessageId}
 * is the idempotency key: re-submitting the same value must not create a
 * duplicate (FR-1.6 — enforced downstream by archive-service).
 */
public record IngestionRequest(
        @NotBlank String sourceMessageId,
        @NotNull MessageType type,
        String subject,
        @NotBlank String body,
        Instant timestamp,
        @NotBlank String sender,
        List<String> participants,
        String threadId,
        List<AttachmentPayload> attachments
) {
}
