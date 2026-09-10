package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.events.MessageType;

import java.time.Instant;
import java.util.List;

/**
 * Query parameters for {@code GET /api/v1/search} (FR-3).
 *
 * @param query          free-text query across subject, body, participants
 * @param dateFrom       inclusive lower bound on message timestamp (nullable)
 * @param dateTo         inclusive upper bound on message timestamp (nullable)
 * @param types          filter by communication type (nullable = any)
 * @param custodians     filter by sender/participant (nullable = any)
 * @param hasAttachment  filter on attachment presence (nullable = ignore)
 * @param onHold         filter on hold status (nullable = ignore)
 * @param page           zero-based page index (default 0)
 * @param size           page size (default 20)
 * @param sort           "date" or "relevance" (default relevance)
 */
public record SearchRequest(
        String query,
        Instant dateFrom,
        Instant dateTo,
        List<MessageType> types,
        List<String> custodians,
        Boolean hasAttachment,
        Boolean onHold,
        Integer page,
        Integer size,
        String sort
) {
    public int pageOrDefault() {
        return page == null || page < 0 ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null || size <= 0 ? 20 : size;
    }

    public String sortOrDefault() {
        return sort == null || sort.isBlank() ? "relevance" : sort;
    }
}
