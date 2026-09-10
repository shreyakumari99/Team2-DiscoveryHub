package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.events.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single hit in a {@link SearchResponse}. {@code highlight} maps a field name
 * to its highlighted fragments (FR-3.3).
 */
public record SearchHit(
        String id,
        MessageType type,
        String subject,
        String sender,
        Instant timestamp,
        boolean hasAttachment,
        boolean held,
        double score,
        Map<String, List<String>> highlight
) {
}
