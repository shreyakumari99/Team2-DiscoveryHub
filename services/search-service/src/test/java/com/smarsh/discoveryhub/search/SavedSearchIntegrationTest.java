package com.smarsh.discoveryhub.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.search.api.SaveSearchRequest;
import com.smarsh.discoveryhub.search.api.SearchRequest;
import com.smarsh.discoveryhub.search.api.SearchResponse;
import com.smarsh.discoveryhub.search.api.SearchService;
import com.smarsh.discoveryhub.search.domain.MessageSearchRepository;
import com.smarsh.discoveryhub.search.domain.SavedSearchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saved searches, end to end through the controller and the embedded H2 store
 * (FR-3.5): save a query against a case, list it, and re-run it server-side.
 *
 * <p>{@code SearchService} is mocked so the test needs no running
 * Elasticsearch; the query-building logic itself is covered by
 * {@link SearchQueryBuilderTest}.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
        // No real broker needed for this slice; the producer/consumer beans are
        // created but never connect because no traffic is sent/received here.
        "spring.kafka.bootstrap-servers=localhost:0"
})
@AutoConfigureMockMvc
class SavedSearchIntegrationTest {

    private static final String CRITERIA = "{\"query\":\"bonus\",\"types\":[\"EMAIL\"],\"size\":20}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SavedSearchRepository savedSearchRepository;

    @MockBean
    private SearchService searchService;

    @MockBean
    private MessageSearchRepository messageSearchRepository;

    @MockBean
    private AuditTrail auditTrail;

    @MockBean
    private ElasticsearchOperations elasticsearchOperations;

    @AfterEach
    void cleanUp() {
        // The H2 store is file-based, so leftovers would bleed across tests.
        savedSearchRepository.deleteAll();
    }

    private String save(String name, String criteria) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/saved-searches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SaveSearchRequest("case-1", name, criteria))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void saveAndRetrieveSavedSearch() throws Exception {
        save("Q3 bonus emails", CRITERIA);

        mockMvc.perform(get("/api/v1/saved-searches").param("caseId", "case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Q3 bonus emails"));
    }

    /** FR-3.5 "re-run it later" — the server replays the stored criteria. */
    @Test
    void savedSearchCanBeReRun() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse("bonus", 0, 20, 7, 1, List.of()));

        String id = save("Q3 bonus emails", CRITERIA);

        mockMvc.perform(post("/api/v1/saved-searches/{id}/run", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(7));

        // The stored criteria are what actually got replayed.
        org.mockito.ArgumentCaptor<SearchRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().query()).isEqualTo("bonus");
        assertThat(captor.getValue().types()).containsExactly(MessageType.EMAIL);
    }

    /** Pagination can be overridden without re-saving the search. */
    @Test
    void reRunAcceptsPaginationOverrides() throws Exception {
        when(searchService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResponse("bonus", 2, 50, 500, 10, List.of()));

        String id = save("Q3 bonus emails", CRITERIA);

        mockMvc.perform(post("/api/v1/saved-searches/{id}/run", id)
                        .param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SearchRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().pageOrDefault()).isEqualTo(2);
        assertThat(captor.getValue().sizeOrDefault()).isEqualTo(50);
    }

    @Test
    void runningAnUnknownSavedSearchIs404() throws Exception {
        mockMvc.perform(post("/api/v1/saved-searches/{id}/run", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    /**
     * A search we could never replay is rejected at save time rather than
     * failing later, when the investigator actually needs it.
     */
    @Test
    void unparseableCriteriaAreRejectedOnSave() throws Exception {
        mockMvc.perform(post("/api/v1/saved-searches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SaveSearchRequest("case-1", "broken", "{not json"))))
                .andExpect(status().isBadRequest());

        assertThat(savedSearchRepository.findByCaseId("case-1")).isEmpty();
    }
}
