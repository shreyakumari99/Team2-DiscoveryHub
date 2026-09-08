package com.smarsh.discoveryhub.ingestion.api;

/**
 * Response returned for an accepted ingestion request (HTTP 202).
 *
 * @param sourceMessageId the idempotency key echoed back
 * @param status          ACCEPTED
 * @param message         human-readable note
 */
public record IngestionResponse(
        String sourceMessageId,
        String status,
        String message
) {
}
