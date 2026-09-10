package com.smarsh.discoveryhub.events;

import java.util.List;

/**
 * Metadata + inline content for an attachment carried on the ingestion event.
 * <p>
 * For the demo corpus, attachment bytes are carried inline as base64 so the
 * ingestion-service can stay truly stateless (no staging bucket of its own).
 * A production design would have Ingestion upload bytes to a staging bucket
 * and pass only a storage key; Archive would then move them into the permanent
 * archive bucket.
 *
 * @param name          original filename
 * @param contentType   MIME type
 * @param sizeBytes     decoded size in bytes
 * @param contentBase64 raw bytes encoded as base64
 */
public record AttachmentPayload(
        String name,
        String contentType,
        long sizeBytes,
        String contentBase64
) {
}
