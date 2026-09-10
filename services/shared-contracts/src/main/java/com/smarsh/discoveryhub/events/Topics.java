package com.smarsh.discoveryhub.events;

/**
 * Canonical Kafka topic names used across all DiscoveryHub services.
 * Reference these constants instead of hard-coding strings so producers and
 * consumers can never drift.
 */
public final class Topics {

    public static final String MESSAGE_INGESTED = "message-ingested";
    public static final String MESSAGE_ARCHIVED = "message-archived";
    public static final String CASE_EVENTS      = "case-events";
    public static final String HOLD_EVENTS      = "hold-events";
    /** Authoritative per-message hold state, emitted by archive-service after it applies a hold. */
    public static final String MESSAGE_HOLD_STATE = "message-hold-state";
    /** Emitted by archive-service once a message is gone, so the search index can drop it. */
    public static final String MESSAGE_DELETED  = "message-deleted";
    public static final String EXPORT_EVENTS    = "export-events";
    public static final String AUDIT_EVENTS     = "audit-events";

    private Topics() {
        throw new UnsupportedOperationException("constants only");
    }
}
