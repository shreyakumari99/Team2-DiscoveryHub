package com.smarsh.discoveryhub.export.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An export job record (FR-6). Status transitions:
 * <pre>QUEUED -&gt; RUNNING -&gt; COMPLETED | FAILED</pre>
 *
 * <p>A FAILED job is retryable without producing a corrupt or duplicate
 * package (FR-6.6): the retry is a separate job with its own id and therefore
 * its own object key, so the failed run's partial output is orphaned rather
 * than overwritten or served.
 *
 * <p>Two checksums are recorded, and they answer different questions.
 * {@code packageChecksum} covers these exact zip bytes and detects tampering
 * with the artifact. {@code contentChecksum} covers only the packaged evidence
 * and is stable across runs, so re-exporting the same evidence is provably the
 * same evidence (FR-6.5).
 */
@Entity
@Table(name = "export_jobs", indexes = {
        @Index(name = "idx_export_case", columnList = "caseId"),
        @Index(name = "idx_export_status", columnList = "status")
})
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String caseId;
    /** "evidence" or "hold:&lt;holdId&gt;" (FR-6.1). */
    private String scope;

    @Enumerated(EnumType.STRING)
    private ExportStatus status;

    private String requestedBy;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant completedAt;

    /** Files in the package: one per message plus one per attachment. */
    private long itemCount;

    /** Messages in scope, which is what an investigator actually counts. */
    private long messageCount;

    /** S3 key of the produced zip. */
    private String packageObjectKey;

    /** SHA-256 of the exact zip bytes (FR-6.3). */
    private String packageChecksum;

    /** SHA-256 over the packaged evidence only; stable across runs (FR-6.5). */
    private String contentChecksum;

    @Column(length = 2000)
    private String failureReason;

    public ExportJob() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public ExportStatus getStatus() {
        return status;
    }

    public void setStatus(ExportStatus status) {
        this.status = status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getItemCount() {
        return itemCount;
    }

    public void setItemCount(long itemCount) {
        this.itemCount = itemCount;
    }

    public String getPackageObjectKey() {
        return packageObjectKey;
    }

    public void setPackageObjectKey(String packageObjectKey) {
        this.packageObjectKey = packageObjectKey;
    }

    public String getPackageChecksum() {
        return packageChecksum;
    }

    public void setPackageChecksum(String packageChecksum) {
        this.packageChecksum = packageChecksum;
    }

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }

    public String getContentChecksum() {
        return contentChecksum;
    }

    public void setContentChecksum(String contentChecksum) {
        this.contentChecksum = contentChecksum;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
