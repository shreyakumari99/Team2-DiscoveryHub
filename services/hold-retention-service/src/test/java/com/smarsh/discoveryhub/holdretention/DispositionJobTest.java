package com.smarsh.discoveryhub.holdretention;

import com.smarsh.discoveryhub.holdretention.api.RetentionService;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRun;
import com.smarsh.discoveryhub.holdretention.job.DispositionJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduled disposition job (FR-5.2).
 *
 * <p>The job itself is deliberately almost empty — the work lives in
 * {@link RetentionService} so that the same behaviour is reachable from both
 * the cron and the REST endpoint. That leaves exactly two things worth testing,
 * and the second matters more than it looks.
 */
class DispositionJobTest {

    /**
     * The trigger is recorded as "schedule", which is what makes a disposition
     * record show whether a deletion was routine or requested by a person.
     */
    @Test
    void runsDispositionAndRecordsItAsScheduled() {
        RetentionService retentionService = mock(RetentionService.class);
        when(retentionService.runDisposition("schedule"))
                .thenReturn(new DispositionRun(Instant.now(), "schedule"));

        new DispositionJob(retentionService).run();

        verify(retentionService).runDisposition("schedule");
    }

    /**
     * A failed run must not escape. Spring's scheduler abandons a fixed-rate
     * task whose exception propagates, so one transient failure — a search
     * service restart, say — would silently stop all future disposition runs
     * for the lifetime of the process. Retention would simply stop happening,
     * with nothing in the UI to indicate it.
     */
    @Test
    void aFailedRunIsSwallowedSoTheScheduleSurvives() {
        RetentionService retentionService = mock(RetentionService.class);
        when(retentionService.runDisposition("schedule"))
                .thenThrow(new IllegalStateException("search-service unreachable"));

        DispositionJob job = new DispositionJob(retentionService);

        Assertions.assertDoesNotThrow(job::run);
        verify(retentionService).runDisposition("schedule");
    }
}
