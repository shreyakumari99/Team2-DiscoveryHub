package com.smarsh.discoveryhub.holdretention.api;

/** Marker for the hold-retention domain exceptions declared alongside it. */
final class HoldExceptions {

    private HoldExceptions() {
    }
}

/** The hold id does not exist. Mapped to 404. */
class HoldNotFoundException extends RuntimeException {

    HoldNotFoundException(String holdId) {
        super("Hold not found: " + holdId);
    }
}

/** FR-2.5: a closed case is read-only, so no new holds may be placed on it. Mapped to 409. */
class CaseClosedException extends RuntimeException {

    CaseClosedException(String caseId) {
        super("Case " + caseId + " is closed and is read-only; new holds are not permitted");
    }
}
