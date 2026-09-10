package com.smarsh.discoveryhub.export.job;

import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportJobRepository;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fails export jobs that have been RUNNING for too long (FR-6.2, FR-6.6).
 *
 * <p>Export work happens on a virtual thread. If the service is restarted or
 * killed mid-run, that thread disappears and its job is left RUNNING forever:
 * the UI shows a spinner that never resolves, and the job cannot be retried
 * because retry only accepts FAILED. This marks such jobs FAILED so they
 * become retryable again.
 *
 * <p>It only changes status; it never deletes the orphaned partial object,
 * because the retry writes to a new key anyway and keeping the artifact is
 * more useful than tidiness when explaining what happened.
 */
@Component
public class StuckExportReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckExportReaper.class);

    private final ExportJobRepository jobRepository;
    private final Duration maxRuntime;

    public StuckExportReaper(ExportJobRepository jobRepository,
                             @Value("${export.max-runtime-minutes:30}") long maxRuntimeMinutes) {
        this.jobRepository = jobRepository;
        this.maxRuntime = Duration.ofMinutes(maxRuntimeMinutes);
    }

    @Scheduled(fixedDelayString = "${export.reaper-interval-ms:60000}")
    @Transactional
    public void failStuckJobs() {
        Instant cutoff = Instant.now().minus(maxRuntime);

        // Started but never finished: the service was restarted mid-run.
        for (ExportJob job : jobRepository.findByStatusAndStartedAtBefore(ExportStatus.RUNNING, cutoff)) {
            fail(job, "Export did not complete within " + maxRuntime.toMinutes()
                    + " minutes; the service was most likely restarted mid-run. Retry to run it again.");
            log.warn("Export {} was stuck in RUNNING since {}; marked FAILED so it can be retried",
                    job.getId(), job.getStartedAt());
        }

        // Never started at all: the worker was lost before it picked the job
        // up. Without this the job is stranded — QUEUED forever, and retry
        // only accepts FAILED, so nothing can move it.
        for (ExportJob job : jobRepository.findByStatusAndRequestedAtBefore(ExportStatus.QUEUED, cutoff)) {
            fail(job, "Export was never picked up within " + maxRuntime.toMinutes()
                    + " minutes. Retry to run it again.");
            log.warn("Export {} was stuck in QUEUED since {}; marked FAILED so it can be retried",
                    job.getId(), job.getRequestedAt());
        }
    }

    private void fail(ExportJob job, String reason) {
        job.setStatus(ExportStatus.FAILED);
        job.setFailureReason(reason);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }
}
