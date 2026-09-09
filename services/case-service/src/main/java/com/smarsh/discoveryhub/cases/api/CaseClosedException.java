package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.CaseState;

/**
 * Thrown when an action is attempted on a closed case. A closed case is
 * read-only (FR-2.5). Mapped to HTTP 409 Conflict.
 */
public class CaseClosedException extends RuntimeException {

    public CaseClosedException(String caseId) {
        super("Case " + caseId + " is " + CaseState.CLOSED + " and read-only");
    }
}
