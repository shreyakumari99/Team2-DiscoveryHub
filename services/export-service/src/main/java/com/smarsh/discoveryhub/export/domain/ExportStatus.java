package com.smarsh.discoveryhub.export.domain;

/**
 * Lifecycle of an export job (FR-6.2): {@code QUEUED → RUNNING → COMPLETED |
 * FAILED}.
 *
 * <p>An enum rather than a free-text column so a typo cannot invent a status
 * the UI will never render, and so the "only a FAILED job may be retried" rule
 * is a type-level comparison instead of a string match.
 */
public enum ExportStatus {

    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    /** Terminal states, where no further work will happen on its own. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
