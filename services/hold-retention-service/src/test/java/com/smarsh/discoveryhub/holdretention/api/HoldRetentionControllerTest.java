package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.holdretention.domain.DispositionOutcome;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRun;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItem;
import com.smarsh.discoveryhub.holdretention.domain.HoldEntity;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicy;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hold and retention REST API (FR-4, FR-5), as a standalone MockMvc slice
 * so the services are plain mocks.
 *
 * <p>Lives in the {@code api} package because the domain exceptions it needs to
 * throw are package-private, declared alongside the handler that maps them.
 *
 * <p>Two things here are more than plumbing. Placing a hold must answer
 * <strong>202</strong>, not 200 — the hold exists but its scope has not been
 * resolved, and saying "OK" would imply the messages are already frozen. And a
 * dependency being unreachable must surface as <strong>503</strong> rather than
 * 500, so one service's outage is not read as this service failing (NFR-2).
 */
class HoldRetentionControllerTest {

    private MockMvc mockMvc;
    private HoldService holdService;
    private RetentionService retentionService;

    @BeforeEach
    void setUp() {
        holdService = mock(HoldService.class);
        retentionService = mock(RetentionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HoldRetentionController(holdService, retentionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private HoldEntity hold(String id, boolean active) {
        HoldEntity h = new HoldEntity();
        h.setId(id);
        h.setCaseId("case-1");
        h.setActive(active);
        h.setCustodians(List.of("alice@smarsh.com"));
        h.setPlacedAt(Instant.parse("2026-01-01T10:00:00Z"));
        h.setMessageCount(1_696);
        h.setScopeResolved(true);
        h.setScopeResolvedAt(Instant.parse("2026-01-01T10:00:04Z"));
        return h;
    }

    // ---- holds -----------------------------------------------------------

    /**
     * FR-4.3: 202 because the scope resolves asynchronously. A 200 here would
     * be a lie — the hold protects nothing yet.
     */
    @Test
    void placingAHoldIsAcceptedNotCompleted() throws Exception {
        when(holdService.placeHold(any())).thenReturn(hold("hold-1", true));

        mockMvc.perform(post("/api/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\",\"custodians\":[\"alice@smarsh.com\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("hold-1"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.messageCount").value(1_696))
                .andExpect(jsonPath("$.scopeResolved").value(true))
                .andExpect(jsonPath("$.custodians[0]").value("alice@smarsh.com"));
    }

    /** FR-2.5: a closed case takes no new holds, and says which rule refused. */
    @Test
    void placingAHoldOnAClosedCaseIsAConflict() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new CaseClosedException("case-1"));

        mockMvc.perform(post("/api/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"case-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CASE_CLOSED"));
    }

    @Test
    void listsHoldsOptionallyFilteredByCase() throws Exception {
        when(holdService.listHolds(null)).thenReturn(List.of(hold("hold-1", true), hold("hold-2", false)));
        when(holdService.listHolds("case-1")).thenReturn(List.of(hold("hold-1", true)));

        mockMvc.perform(get("/api/v1/holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/holds").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void fetchesASingleHold() throws Exception {
        when(holdService.getHold("hold-1")).thenReturn(hold("hold-1", true));

        mockMvc.perform(get("/api/v1/holds/hold-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    @Test
    void anUnknownHoldIsNotFound() throws Exception {
        when(holdService.getHold(anyString())).thenThrow(new HoldNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/holds/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Hold not found: nope"));
    }

    @Test
    void releasesAHoldWithAReason() throws Exception {
        when(holdService.releaseHold(eq("hold-1"), eq("case-closed"))).thenReturn(hold("hold-1", false));

        mockMvc.perform(post("/api/v1/holds/hold-1/release").param("reason", "case-closed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(holdService).releaseHold("hold-1", "case-closed");
    }

    /**
     * Re-driving a failed scope resolution. Without this endpoint a hold whose
     * resolution failed would stay active while protecting nothing, with no way
     * to fix it — the worst possible state for a legal hold.
     */
    @Test
    void reResolvesAScopeAndAnswersAccepted() throws Exception {
        when(holdService.getHold("hold-1")).thenReturn(hold("hold-1", true));

        mockMvc.perform(post("/api/v1/holds/hold-1/resolve-scope"))
                .andExpect(status().isAccepted());

        verify(holdService).resolveScopeAndPublish("hold-1");
    }

    @Test
    void reportsTheActiveHoldCountForACase() throws Exception {
        when(holdService.activeHoldCount("case-1")).thenReturn(2L);

        mockMvc.perform(get("/api/v1/holds/count").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(2));
    }

    /** FR-4.4: deduplicated across overlapping holds, hence a single figure. */
    @Test
    void reportsTheDistinctHeldMessageCount() throws Exception {
        when(holdService.heldMessageCount("case-1")).thenReturn(1_696L);

        mockMvc.perform(get("/api/v1/holds/held-messages").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldMessages").value(1_696));
    }

    // ---- retention -------------------------------------------------------

    /** FR-5.1: configurable per communication type, stored in minutes. */
    @Test
    void savesAndListsRetentionPolicies() throws Exception {
        RetentionPolicy policy = new RetentionPolicy(MessageType.EMAIL, 3_679_200L);
        when(retentionService.savePolicy(any())).thenReturn(policy);
        when(retentionService.listPolicies()).thenReturn(List.of(policy));

        mockMvc.perform(post("/api/v1/retention/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EMAIL\",\"retentionMinutes\":3679200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionMinutes").value(3_679_200L));

        mockMvc.perform(get("/api/v1/retention/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("EMAIL"));
    }

    @Test
    void runsADispositionOnDemandAndReportsTheTallies() throws Exception {
        DispositionRun run = new DispositionRun(Instant.parse("2026-01-01T12:00:00Z"), "manual");
        run.setId("run-1");
        run.setDeletedCount(412);
        run.setSkippedHeldCount(38);
        run.setNotFoundCount(2);
        run.setErrorCount(0);
        run.setFinishedAt(Instant.parse("2026-01-01T12:04:00Z"));
        when(retentionService.runDisposition("manual")).thenReturn(run);
        when(retentionService.listRuns()).thenReturn(List.of(run));

        mockMvc.perform(post("/api/v1/retention/disposition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value("manual"))
                .andExpect(jsonPath("$.deletedCount").value(412))
                .andExpect(jsonPath("$.skippedHeldCount").value(38))
                .andExpect(jsonPath("$.notFoundCount").value(2))
                .andExpect(jsonPath("$.errorCount").value(0));

        mockMvc.perform(get("/api/v1/retention/disposition/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"));
    }

    /**
     * FR-5.3: the per-message record, filterable by outcome.
     * {@code SKIPPED_HELD} is the list that proves a legal hold blocked
     * deletion message by message rather than as a summary count.
     */
    @Test
    void returnsThePerMessageRecordFilteredByOutcome() throws Exception {
        DispositionRunItem item = new DispositionRunItem("run-1", "msg-1", MessageType.EMAIL,
                DispositionOutcome.SKIPPED_HELD, Instant.parse("2026-01-01T12:00:00Z"));
        when(retentionService.runItems("run-1", DispositionOutcome.SKIPPED_HELD)).thenReturn(List.of(item));
        when(retentionService.runItems("run-1", null)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/retention/disposition/runs/run-1/items")
                        .param("outcome", "SKIPPED_HELD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageId").value("msg-1"))
                .andExpect(jsonPath("$[0].outcome").value("SKIPPED_HELD"))
                .andExpect(jsonPath("$[0].messageType").value("EMAIL"));

        mockMvc.perform(get("/api/v1/retention/disposition/runs/run-1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---- dependency failures (NFR-2) -------------------------------------

    /**
     * An unreachable neighbour is a 503, not a 500. It tells the UI to retry
     * rather than reporting a defect in this service.
     */
    @Test
    void anUnreachableDependencyIsServiceUnavailable() throws Exception {
        when(retentionService.runDisposition(anyString()))
                .thenThrow(new ResourceAccessException("connection refused"));

        mockMvc.perform(post("/api/v1/retention/disposition"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void aMalformedRequestIsABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("Hold id is required"))
                .when(holdService).resolveScopeAndPublish(anyString());

        mockMvc.perform(post("/api/v1/holds/bad/resolve-scope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hold id is required"));
    }
}
