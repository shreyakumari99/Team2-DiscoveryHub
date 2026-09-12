package com.smarsh.discoveryhub.export.job;

import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportJobRepository;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recovery for export jobs orphaned by a restart (FR-6.2, FR-6.6).
 *
 * <p>Export work runs on a virtual thread. If the service is killed mid-run
 * that thread simply disappears, and its job is left behind in a state nothing
 * will ever move it out of: the UI shows a spinner for ever, and retry refuses
 * it because retry only accepts a FAILED job. The reaper exists to turn those
 * strandings back into something retryable.
 *
 * <p>There are two distinct strandings, and both have to be handled. A job that
 * <em>started</em> and never finished, and a job that was never picked up at
 * all — which is what the pre-{@code afterCommit} dispatch race used to
 * produce.
 */
class StuckExportReaperTest {

    private ExportJobRepository jobRepository;
    private StuckExportReaper reaper;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ExportJobRepository.class);
        // 30 minutes, matching the production default.
        reaper = new StuckExportReaper(jobRepository, 30);
        when(jobRepository.findByStatusAndStartedAtBefore(any(), any())).thenReturn(List.of());
        when(jobRepository.findByStatusAndRequestedAtBefore(any(), any())).thenReturn(List.of());
    }

    private ExportJob job(String id, ExportStatus status) {
        ExportJob j = new ExportJob();
        j.setId(id);
        j.setCaseId("case-1");
        j.setScope("evidence");
        j.setStatus(status);
        j.setRequestedBy("investigator@smarsh.com");
        j.setRequestedAt(Instant.now().minus(Duration.ofHours(2)));
        return j;
    }

    @Test
    void failsAJobThatStartedAndNeverFinished() {
        ExportJob stuck = job("job-1", ExportStatus.RUNNING);
        stuck.setStartedAt(Instant.now().minus(Duration.ofHours(1)));
        when(jobRepository.findByStatusAndStartedAtBefore(eq(ExportStatus.RUNNING), any()))
                .thenReturn(List.of(stuck));

        reaper.failStuckJobs();

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(saved.getValue().getFailureReason())
                .contains("did not complete within 30 minutes")
                .contains("Retry");
        assertThat(saved.getValue().getCompletedAt()).isNotNull();
    }

    /**
     * The stranding the dispatch race produced: a job that was never started.
     * Without this branch it stays QUEUED for ever and retry cannot touch it,
     * so nothing in the system can move it.
     */
    @Test
    void failsAJobThatWasNeverPickedUp() {
        when(jobRepository.findByStatusAndRequestedAtBefore(eq(ExportStatus.QUEUED), any()))
                .thenReturn(List.of(job("job-2", ExportStatus.QUEUED)));

        reaper.failStuckJobs();

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(saved.getValue().getFailureReason()).contains("never picked up");
    }

    @Test
    void failsBothKindsOfStrandingInOnePass() {
        ExportJob running = job("job-1", ExportStatus.RUNNING);
        running.setStartedAt(Instant.now().minus(Duration.ofHours(1)));
        when(jobRepository.findByStatusAndStartedAtBefore(eq(ExportStatus.RUNNING), any()))
                .thenReturn(List.of(running));
        when(jobRepository.findByStatusAndRequestedAtBefore(eq(ExportStatus.QUEUED), any()))
                .thenReturn(List.of(job("job-2", ExportStatus.QUEUED)));

        reaper.failStuckJobs();

        verify(jobRepository, org.mockito.Mockito.times(2)).save(any());
    }

    /** A healthy queue is left completely alone. */
    @Test
    void touchesNothingWhenNoJobIsStranded() {
        reaper.failStuckJobs();

        verify(jobRepository, never()).save(any());
    }

    /**
     * The cutoff is derived from the configured maximum runtime, so a shorter
     * setting reaps sooner. Both queries must use the same cutoff — a job is
     * either past its allowance or it isn't.
     */
    @Test
    void theCutoffFollowsTheConfiguredMaxRuntime() {
        StuckExportReaper impatient = new StuckExportReaper(jobRepository, 5);
        Instant before = Instant.now();

        impatient.failStuckJobs();

        ArgumentCaptor<Instant> runningCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> queuedCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(jobRepository).findByStatusAndStartedAtBefore(eq(ExportStatus.RUNNING), runningCutoff.capture());
        verify(jobRepository).findByStatusAndRequestedAtBefore(eq(ExportStatus.QUEUED), queuedCutoff.capture());

        // Both queries must use the same cutoff — a job is either past its
        // allowance or it is not.
        assertThat(runningCutoff.getValue()).isEqualTo(queuedCutoff.getValue());
        // Five minutes before "now", where now is taken inside the call and so
        // is a moment after `before`. Allow a window rather than an exact value.
        Instant expected = before.minus(Duration.ofMinutes(5));
        assertThat(runningCutoff.getValue()).isBetween(expected, expected.plusSeconds(10));
    }

    /**
     * The reaper only changes status. The orphaned partial object in S3 is
     * deliberately left in place: a retry writes to a new key anyway, and
     * keeping the artifact is more useful than tidiness when explaining what
     * happened.
     */
    @Test
    void leavesThePartialPackageKeyIntact() {
        ExportJob stuck = job("job-1", ExportStatus.RUNNING);
        stuck.setStartedAt(Instant.now().minus(Duration.ofHours(1)));
        stuck.setPackageObjectKey("exports/job-1/package.zip");
        when(jobRepository.findByStatusAndStartedAtBefore(eq(ExportStatus.RUNNING), any()))
                .thenReturn(List.of(stuck));

        reaper.failStuckJobs();

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getPackageObjectKey()).isEqualTo("exports/job-1/package.zip");
    }
}
