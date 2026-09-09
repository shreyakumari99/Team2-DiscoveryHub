package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.CaseState;

/**
 * Thrown when a case lifecycle transition is not allowed (FR-2.2). Mapped to
 * HTTP 409 Conflict by {@link GlobalExceptionHandler}.
 */
public class IllegalCaseTransitionException extends RuntimeException {

    public IllegalCaseTransitionException(String caseId, CaseState from, CaseState to) {
        super("Illegal transition for case " + caseId + ": " + from + " -> " + to);
    }
}
