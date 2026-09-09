package com.smarsh.discoveryhub.archive.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Maps domain exceptions to clean HTTP responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MessageHeldException.class)
    public ResponseEntity<Map<String, Object>> handleHeld(MessageHeldException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.CONFLICT.value(),
                "error", "Conflict",
                "message", ex.getMessage(),
                "reason", "LEGAL_HOLD"
        ));
    }

    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(MessageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.NOT_FOUND.value(),
                "error", "Not Found",
                "message", ex.getMessage()
        ));
    }

    /**
     * A malformed request is a 400, not a 404. Keeping these apart matters for
     * the disposition job, which treats 404 as "already gone" and would
     * otherwise silently swallow a genuine bug as a successful cleanup.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Bad Request",
                "message", String.valueOf(ex.getMessage())
        ));
    }
}
