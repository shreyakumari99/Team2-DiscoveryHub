package com.smarsh.discoveryhub.holdretention.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Record of a single disposition run (FR-5.3). Append-only log per run: how
 * many messages were deleted, how many were skipped because on hold, and when.
 */
@Entity
@Table(name = "disposition_runs")
public class DispositionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private Instant startedAt;
    private Instant finishedAt;
    private long deletedCount;
    private long skippedHeldCount;

    /** Messages already gone from the archive; counted but not an error. */
    private long notFoundCount;

    /** Messages the archive could not be asked about — the run was incomplete. */
    private long errorCount;

    /** How the run was started: the schedule, or an operator on demand. */
    private String trigger;

    public DispositionRun() {
    }

    public DispositionRun(Instant startedAt, String trigger) {
        this.startedAt = startedAt;
        this.trigger = trigger;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(long deletedCount) {
        this.deletedCount = deletedCount;
    }

    public long getSkippedHeldCount() {
        return skippedHeldCount;
    }

    public void setSkippedHeldCount(long skippedHeldCount) {
        this.skippedHeldCount = skippedHeldCount;
    }

    public long getNotFoundCount() {
        return notFoundCount;
    }

    public void setNotFoundCount(long notFoundCount) {
        this.notFoundCount = notFoundCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }
}
