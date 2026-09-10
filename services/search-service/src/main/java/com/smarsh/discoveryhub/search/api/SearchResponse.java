package com.smarsh.discoveryhub.search.api;

import java.util.List;

/**
 * Paginated search response (FR-3.4).
 *
 * @param query       the original query echo
 * @param page        current page index
 * @param size        page size
 * @param totalHits   total matching documents
 * @param totalPages  total pages
 * @param hits        hits on the current page
 */
public record SearchResponse(
        String query,
        int page,
        int size,
        long totalHits,
        int totalPages,
        List<SearchHit> hits
) {
}
