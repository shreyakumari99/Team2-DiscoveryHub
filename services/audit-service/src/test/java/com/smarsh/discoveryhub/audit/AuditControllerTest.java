package com.smarsh.discoveryhub.audit;

import com.smarsh.discoveryhub.audit.api.AuditController;
import com.smarsh.discoveryhub.audit.domain.AuditLogEntry;
import com.smarsh.discoveryhub.audit.domain.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail's read API (FR-7.4), and the fact that it is read-only
 * (FR-7.3).
 *
 * <p>The most valuable assertions here are the negative ones. There must be no
 * way to write to the trail through HTTP — no POST, PUT or PATCH — and the
 * paging must be bounded, because this table becomes the largest in the system
 * and an unbounded page size is a denial of service against your own database.
 */
class AuditControllerTest {

    private MockMvc mockMvc;
    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(repository)).build();
    }

    private AuditLogEntry entry(String action) {
        AuditLogEntry e = new AuditLogEntry(
                "evt-1", Instant.parse("2026-01-01T10:00:00Z"), "investigator@smarsh.com",
                "case-service", action, "CASE", "case-1", "case-1",
                "{\"state\":\"ACTIVE\"}", "{\"state\":\"UNDER_REVIEW\"}");
        e.setDbId("db-1");
        return e;
    }

    @SuppressWarnings("unchecked")
    private void stubSearch(List<AuditLogEntry> entries) {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(entries, PageRequest.of(0, 100), entries.size()));
    }

    @Test
    void returnsAPageOfEntriesWithTheirBeforeAndAfterDetail() throws Exception {
        stubSearch(List.of(entry("CASE_TRANSITIONED")));

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("CASE_TRANSITIONED"))
                .andExpect(jsonPath("$.content[0].actor").value("investigator@smarsh.com"))
                .andExpect(jsonPath("$.content[0].service").value("case-service"))
                .andExpect(jsonPath("$.content[0].entityType").value("CASE"))
                .andExpect(jsonPath("$.content[0].entityId").value("case-1"))
                .andExpect(jsonPath("$.content[0].caseId").value("case-1"))
                // FR-7.2: before/after state is part of the entry, not a summary.
                .andExpect(jsonPath("$.content[0].beforeJson").value("{\"state\":\"ACTIVE\"}"))
                .andExpect(jsonPath("$.content[0].afterJson").value("{\"state\":\"UNDER_REVIEW\"}"))
                .andExpect(jsonPath("$.content[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.content[0].dbId").value("db-1"));
    }

    /** Newest first: an investigator reads a trail from the most recent action. */
    @Test
    @SuppressWarnings("unchecked")
    void sortsNewestFirst() throws Exception {
        stubSearch(List.of(entry("CASE_CREATED")));

        mockMvc.perform(get("/api/v1/audit")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        Sort.Order order = pageable.getValue().getSort().getOrderFor("timestamp");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesADefaultPageSizeWhenNoneIsGiven() throws Exception {
        stubSearch(List.of());

        mockMvc.perform(get("/api/v1/audit")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    /**
     * The page size is capped. Without this, one request could ask for every
     * row in what becomes the largest table in the platform.
     */
    @Test
    @SuppressWarnings("unchecked")
    void capsAnOversizedPageRequest() throws Exception {
        stubSearch(List.of());

        mockMvc.perform(get("/api/v1/audit").param("size", "99999")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1_000);
    }

    /** Nonsensical paging falls back to the defaults rather than failing. */
    @Test
    @SuppressWarnings("unchecked")
    void negativePagingFallsBackToTheDefaults() throws Exception {
        stubSearch(List.of());

        mockMvc.perform(get("/api/v1/audit").param("page", "-5").param("size", "0"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    /** FR-7.4: every filter is optional and they compose. */
    @Test
    void acceptsEveryFilterTogether() throws Exception {
        stubSearch(List.of(entry("HOLD_PLACED")));

        mockMvc.perform(get("/api/v1/audit")
                        .param("caseId", "case-1")
                        .param("action", "HOLD_PLACED")
                        .param("actor", "investigator@smarsh.com")
                        .param("entityType", "HOLD")
                        .param("entityId", "hold-1")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z")
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk());
    }

    @Test
    void listsDistinctActionsForTheFilterDropdown() throws Exception {
        when(repository.findDistinctActions())
                .thenReturn(List.of("CASE_CREATED", "HOLD_PLACED", "MESSAGE_DELETE_BLOCKED"));

        mockMvc.perform(get("/api/v1/audit/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2]").value("MESSAGE_DELETE_BLOCKED"));
    }

    @Test
    void reportsTheTotalEntryCount() throws Exception {
        when(repository.count()).thenReturn(57_746L);

        mockMvc.perform(get("/api/v1/audit/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntries").value(57_746));
    }

    /**
     * FR-7.3, enforced at the API surface: the trail can never be rewritten
     * through HTTP. There is no write mapping to reach, so every attempt is
     * rejected by the framework before any code of ours runs. The database
     * triggers in {@code data-postgresql.sql} are the second line of defence.
     */
    @Test
    void exposesNoWriteVerbs() throws Exception {
        mockMvc.perform(post("/api/v1/audit")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/v1/audit")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/v1/audit")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/audit")).andExpect(status().isMethodNotAllowed());
    }
}
