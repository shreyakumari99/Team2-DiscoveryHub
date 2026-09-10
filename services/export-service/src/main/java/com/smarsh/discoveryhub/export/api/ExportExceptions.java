package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.export.domain.ExportStatus;

/** Marker for the export-service domain exceptions declared alongside it. */
final class ExportExceptions {

    private ExportExceptions() {
    }
}

/** The export job id does not exist. Mapped to 404. */
class ExportJobNotFoundException extends RuntimeException {

    ExportJobNotFoundException(String jobId) {
        super("Export job not found: " + jobId);
    }
}

/** FR-2.5: a closed case is read-only, so it cannot be exported. Mapped to 409. */
class CaseClosedException extends RuntimeException {

    CaseClosedException(String caseId) {
        super("Case " + caseId + " is closed and is read-only; exports are not permitted");
    }
}

/** A download was requested for a job that has not produced a package. Mapped to 409. */
class ExportNotDownloadableException extends RuntimeException {

    ExportNotDownloadableException(String jobId, ExportStatus status) {
        super("Export " + jobId + " is " + status + "; only a COMPLETED export can be downloaded");
    }
}

/** FR-6.6: only a failed export may be retried. Mapped to 409. */
class ExportNotRetryableException extends RuntimeException {

    ExportNotRetryableException(String jobId, ExportStatus status) {
        super("Export " + jobId + " is " + status + "; only a FAILED export can be retried");
    }
}
