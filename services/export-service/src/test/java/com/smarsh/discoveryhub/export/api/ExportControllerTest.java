package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.export.api.ExportVerificationService.VerificationResult;
import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The export REST API (FR-6), as a standalone MockMvc slice.
 *
 * <p>Lives in the {@code api} package so it can construct the package-private
 * domain exceptions the handler maps.
 *
 * <p>The interesting assertions are about status codes, because this API's
 * whole job is to be honest about what has and has not happened yet. Requesting
 * an export is <strong>202</strong>, since the package does not exist. Asking
 * to download an unfinished job is a <strong>409</strong>, not a 404 — the job
 * exists, it just has nothing to give you. And a verification that <em>fails</em>
 * is still a <strong>200</strong>, because "this package has been tampered
 * with" is a legitimate answer to the question, not an error.
 */
class ExportControllerTest {

    private MockMvc mockMvc;
    private ExportService exportService;
    private ExportVerificationService verificationService;

    @BeforeEach
    void setUp() {
        exportService = mock(ExportService.class);
        verificationService = mock(ExportVerificationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExportController(exportService, verificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ExportJob job(String id, ExportStatus status) {
        ExportJob j = new ExportJob();
        j.setId(id);
        j.setCaseId("case-1");
        j.setScope("evidence");
        j.setStatus(status);
        j.setRequestedBy("investigator@smarsh.com");
        j.setRequestedAt(Instant.parse("2026-01-01T10:00:00Z"));
        j.setItemCount(14);
        j.setMessageCount(11);
        j.setPackageChecksum("aaa");
        j.setContentChecksum("bbb");
        return j;
    }

    /** FR-6.2: the caller gets a job id immediately, not a finished package. */
    @Test
    void requestingAnExportIsAcceptedNotCompleted() throws Exception {
        when(exportService.requestExport(eq("case-1"), eq("evidence"), any()))
                .thenReturn(job("job-1", ExportStatus.QUEUED));

        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\",\"scope\":\"evidence\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    /** FR-6.1: a hold's full frozen scope is the other supported scope. */
    @Test
    void acceptsAHoldScopedExport() throws Exception {
        when(exportService.requestExport(eq("case-1"), eq("hold:hold-9"), any()))
                .thenReturn(job("job-2", ExportStatus.QUEUED));

        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\",\"scope\":\"hold:hold-9\"}"))
                .andExpect(status().isAccepted());

        verify(exportService).requestExport("case-1", "hold:hold-9", null);
    }

    /** An unsupported scope is rejected up front, not as a mysterious FAILED job. */
    @Test
    void anUnsupportedScopeIsABadRequest() throws Exception {
        when(exportService.requestExport(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Unsupported export scope: everything"));

        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\",\"scope\":\"everything\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported export scope: everything"));
    }

    /** FR-2.5: no new exports from a closed case. */
    @Test
    void exportingAClosedCaseIsAConflict() throws Exception {
        when(exportService.requestExport(anyString(), any(), any()))
                .thenThrow(new CaseClosedException("case-1"));

        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CASE_CLOSED"));
    }

    @Test
    void listsJobsForACaseAndOverall() throws Exception {
        when(exportService.listJobs(null)).thenReturn(List.of(job("job-1", ExportStatus.COMPLETED)));
        when(exportService.listJobs("case-1")).thenReturn(List.of(job("job-1", ExportStatus.COMPLETED)));

        mockMvc.perform(get("/api/v1/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/exports").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemCount").value(14))
                .andExpect(jsonPath("$[0].messageCount").value(11));
    }

    @Test
    void reportsASingleJobsStatus() throws Exception {
        when(exportService.getJob("job-1")).thenReturn(job("job-1", ExportStatus.RUNNING));

        mockMvc.perform(get("/api/v1/exports/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.scope").value("evidence"));
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        when(exportService.getJob(anyString())).thenThrow(new ExportJobNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/exports/nope"))
                .andExpect(status().isNotFound());
    }

    /** FR-6.4: the download is an expiring link, handed over as a URL. */
    @Test
    void returnsAnExpiringDownloadUrl() throws Exception {
        when(exportService.downloadUrl("job-1"))
                .thenReturn("https://bucket.s3.amazonaws.com/exports/job-1/package.zip?X-Amz-Expires=1800");

        mockMvc.perform(get("/api/v1/exports/job-1/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(
                        "https://bucket.s3.amazonaws.com/exports/job-1/package.zip?X-Amz-Expires=1800"));
    }

    /**
     * Downloading an unfinished job is a conflict, not a 404. The job exists —
     * it simply has no package yet, and the two cases must be distinguishable.
     */
    @Test
    void downloadingAnUnfinishedJobIsAConflict() throws Exception {
        when(exportService.downloadUrl(anyString()))
                .thenThrow(new ExportNotDownloadableException("job-1", ExportStatus.RUNNING));

        mockMvc.perform(get("/api/v1/exports/job-1/download"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOT_COMPLETED"));
    }

    /** FR-6.5: verification passing. */
    @Test
    void reportsASuccessfulVerification() throws Exception {
        when(exportService.getJob("job-1")).thenReturn(job("job-1", ExportStatus.COMPLETED));
        when(verificationService.verify(any()))
                .thenReturn(new VerificationResult(true, 14, true, true, List.of()));

        mockMvc.perform(get("/api/v1/exports/job-1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.itemsChecked").value(14))
                .andExpect(jsonPath("$.problems.length()").value(0));
    }

    /**
     * A failed verification is still a 200. "This package does not match its
     * manifest" is the answer to the question that was asked, and the UI needs
     * the findings in order to show which item is wrong — an error status would
     * throw that detail away.
     */
    @Test
    void aFailedVerificationIsAnAnswerNotAnError() throws Exception {
        when(exportService.getJob("job-1")).thenReturn(job("job-1", ExportStatus.COMPLETED));
        when(verificationService.verify(any())).thenReturn(new VerificationResult(
                false, 14, false, true,
                List.of("messages/msg-1.json: sha256 mismatch")));

        mockMvc.perform(get("/api/v1/exports/job-1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.contentChecksumMatches").value(false))
                .andExpect(jsonPath("$.problems[0]").value("messages/msg-1.json: sha256 mismatch"));
    }

    /** FR-6.6: a retry is accepted, and produces a new job. */
    @Test
    void retryingAFailedJobIsAccepted() throws Exception {
        when(exportService.retry("job-1")).thenReturn(job("job-3", ExportStatus.QUEUED));

        mockMvc.perform(post("/api/v1/exports/job-1/retry"))
                .andExpect(status().isAccepted())
                // A new id: the retry can neither overwrite nor be confused
                // with the partial output of the run that failed.
                .andExpect(jsonPath("$.id").value("job-3"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    /**
     * Only a FAILED job may be retried. Retrying a completed one would quietly
     * produce a second package of the same evidence under a different name,
     * which is exactly the ambiguity a chain of custody must not have.
     */
    @Test
    void retryingACompletedJobIsRefused() throws Exception {
        when(exportService.retry(anyString()))
                .thenThrow(new ExportNotRetryableException("job-1", ExportStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/exports/job-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOT_RETRYABLE"));
    }

    /** NFR-2: an unreachable neighbour is a 503, so the UI says "retry shortly". */
    @Test
    void anUnreachableDependencyIsServiceUnavailable() throws Exception {
        when(exportService.listJobs(any()))
                .thenThrow(new ResourceAccessException("connection refused"));

        mockMvc.perform(get("/api/v1/exports"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("DEPENDENCY_UNAVAILABLE"));
    }

    /** An open circuit breaker is treated the same way, for the same reason. */
    @Test
    void anOpenCircuitBreakerIsAlsoServiceUnavailable() throws Exception {
        when(exportService.listJobs(any())).thenThrow(
                CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("archive-service")));

        mockMvc.perform(get("/api/v1/exports"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("DEPENDENCY_UNAVAILABLE"));
    }
}
