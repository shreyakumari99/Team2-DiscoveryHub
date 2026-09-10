package com.smarsh.discoveryhub.search.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Maps domain and infrastructure failures to clean HTTP responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SavedSearchNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(SavedSearchNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    /**
     * Elasticsearch being unavailable is a dependency outage, not a server
     * bug: answering 503 lets the UI say "search is temporarily unavailable"
     * and keeps the rest of the application usable (NFR-2).
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String, Object>> handleSearchBackendDown(DataAccessResourceFailureException ex) {
        log.warn("Elasticsearch unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Search index is temporarily unavailable; please retry.", "SEARCH_BACKEND_UNAVAILABLE");
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message, String reason) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", String.valueOf(message));
        if (reason != null) {
            body.put("reason", reason);
        }
        return ResponseEntity.status(status).body(body);
    }
}
