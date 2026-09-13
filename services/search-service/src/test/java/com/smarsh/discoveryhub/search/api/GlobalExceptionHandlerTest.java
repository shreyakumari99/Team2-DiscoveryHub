package com.smarsh.discoveryhub.search.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a search backend outage is presented to the UI. The 404 and 400 paths
 * are already exercised through HTTP by
 * {@link com.smarsh.discoveryhub.search.SavedSearchIntegrationTest}; this
 * covers the one failure mode that has no other test.
 */
class GlobalExceptionHandlerTest {

    /**
     * NFR-2: Elasticsearch being down is a dependency outage, not a bug in
     * this service. A 503 with a machine-readable reason lets the UI say
     * "search is temporarily unavailable" and keep the rest of the app usable,
     * where a 500 would read as data loss.
     */
    @Test
    void elasticsearchBeingDownIsReportedAsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                .handleSearchBackendDown(new DataAccessResourceFailureException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("status", 503);
        assertThat(response.getBody()).containsEntry("reason", "SEARCH_BACKEND_UNAVAILABLE");
        // The internal message is not leaked to the caller.
        assertThat(response.getBody().get("message").toString())
                .isEqualTo("Search index is temporarily unavailable; please retry.");
    }
}
