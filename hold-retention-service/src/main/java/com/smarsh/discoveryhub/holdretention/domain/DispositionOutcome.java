package com.smarsh.discoveryhub.holdretention.domain;

/**
 * What happened to one message during a disposition run (FR-5.3: "every
 * disposition run must record what was deleted, what was skipped due to hold,
 * and when").
 */
public enum DispositionOutcome {

    /** Past retention and not held — removed from the archive. */
    DELETED,

    /**
     * Past retention but on legal hold: archive-service refused the delete
     * with 409. This is the FR-4.6 proof, captured per message.
     */
    SKIPPED_HELD,

    /** Already absent from the archive; nothing to do. */
    NOT_FOUND,

    /** The delete could not be attempted — the archive was unreachable. */
    ERROR
}
