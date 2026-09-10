package com.smarsh.discoveryhub.events;

/**
 * Case lifecycle (FR-2.2). Only valid transitions are allowed; invalid ones
 * are rejected by the case-service. Valid transitions:
 * <pre>
 *   DRAFT -&gt; ACTIVE -&gt; UNDER_REVIEW -&gt; CLOSED
 * </pre>
 * A CLOSED case is read-only (FR-2.5).
 */
public enum CaseState {
    DRAFT,
    ACTIVE,
    UNDER_REVIEW,
    CLOSED
}
