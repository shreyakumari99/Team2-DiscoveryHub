package com.smarsh.discoveryhub.search.api;

/**
 * Body for {@code POST /api/v1/saved-searches} (FR-3.5).
 *
 * @param caseId    the case the saved search belongs to
 * @param name      a human-readable label
 * @param queryJson the full {@link SearchRequest} serialized as JSON, so the
 *                  saved search can be re-run by deserializing and replaying it
 */
public record SaveSearchRequest(String caseId, String name, String queryJson) {
}
