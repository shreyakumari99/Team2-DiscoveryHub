package com.smarsh.discoveryhub.cases.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Maps case-service domain exceptions to clean HTTP responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalCaseTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleTransition(IllegalCaseTransitionException ex) {
        return conflict(ex.getMessage(), "ILLEGAL_TRANSITION");
    }

    @ExceptionHandler(CaseClosedException.class)
    public ResponseEntity<Map<String, Object>> handleClosed(CaseClosedException ex) {
        return conflict(ex.getMessage(), "CASE_CLOSED");
    }

    /**
     * A constraint violation that reaches here is a conflict with existing
     * data, not a server fault, so it must not surface as a 500. The known
     * case — re-adding evidence already on the case — is filtered out before
     * the insert; this is the net for the rest.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(DataIntegrityViolationException ex) {
        return conflict("The request conflicts with data that already exists", "CONSTRAINT_VIOLATION");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.NOT_FOUND.value(),
                "error", "Not Found",
                "message", ex.getMessage()
        ));
    }

    private ResponseEntity<Map<String, Object>> conflict(String message, String reason) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.CONFLICT.value(),
                "error", "Conflict",
                "message", message,
                "reason", reason
        ));
    }
}
