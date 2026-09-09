package com.smarsh.discoveryhub.cases.api;

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
