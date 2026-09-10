package com.smarsh.discoveryhub.export.api;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps domain and dependency failures to clean HTTP responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ExportJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ExportJobNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    /** FR-2.5: a closed case is read-only. */
    @ExceptionHandler(CaseClosedException.class)
    public ResponseEntity<Map<String, Object>> handleCaseClosed(CaseClosedException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "CASE_CLOSED");
    }

    @ExceptionHandler(ExportNotRetryableException.class)
    public ResponseEntity<Map<String, Object>> handleNotRetryable(ExportNotRetryableException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "NOT_RETRYABLE");
    }

    @ExceptionHandler(ExportNotDownloadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotDownloadable(ExportNotDownloadableException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "NOT_COMPLETED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    /**
     * A dependency being unreachable — or its circuit being open — is a 503,
     * not a 500. It tells the UI to say "try again shortly" rather than
     * reporting a bug, and it keeps one service's outage from looking like
     * this service failing (NFR-2).
     */
    @ExceptionHandler({ResourceAccessException.class, CallNotPermittedException.class})
    public ResponseEntity<Map<String, Object>> handleDependencyDown(Exception ex) {
        log.warn("Downstream dependency unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "A required service is temporarily unavailable; please retry.", "DEPENDENCY_UNAVAILABLE");
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String message, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
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
