package com.smarsh.discoveryhub.search;

import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.search.api.SearchRequest;
import com.smarsh.discoveryhub.search.api.SearchResponse;
import com.smarsh.discoveryhub.search.api.SearchService;
import com.smarsh.discoveryhub.search.domain.MessageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link SearchService} does with the hits Elasticsearch returns.
 *
 * <p>{@link SearchQueryBuilderTest} covers the query that goes out, but it
 * stubs an <em>empty</em> result, so nothing there ever executes the mapping
 * back into {@link com.smarsh.discoveryhub.search.api.SearchHit} — the path
 * every search response an investigator reads goes through.
 */
class SearchResultMappingTest {

    private ElasticsearchOperations operations;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        searchService = new SearchService(operations);
    }

    private static MessageDocument document(String id) {
        return new MessageDocument(id, "src-" + id, MessageType.EMAIL, "Bonus pool discussion",
                "the bonus pool is final", Instant.parse("2025-01-02T03:04:05Z"),
                "alice.chen@smarsh.com", List.of("alice.chen@smarsh.com"), "thread-1",
                true, false, Instant.parse("2025-01-02T03:04:06Z"));
    }

    /** A stubbed page of hits, with a score and highlight fragments. */
    @SuppressWarnings("unchecked")
    private SearchHits<MessageDocument> hits(long total, String... ids) {
        List<org.springframework.data.elasticsearch.core.SearchHit<MessageDocument>> list = new ArrayList<>();
        for (String id : ids) {
            var hit = (org.springframework.data.elasticsearch.core.SearchHit<MessageDocument>)
                    mock(org.springframework.data.elasticsearch.core.SearchHit.class);
            when(hit.getContent()).thenReturn(document(id));
            when(hit.getScore()).thenReturn(4.25f);
            when(hit.getHighlightFields())
                    .thenReturn(Map.of("subject", List.of("<em>Bonus</em> pool discussion")));
            list.add(hit);
        }
        SearchHits<MessageDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(list);
        when(hits.getTotalHits()).thenReturn(total);
        return hits;
    }

    @Test
    void aHitCarriesTheDocumentFieldsScoreAndHighlights() {
        // Built before the stubbing call: hits() stubs its own mocks, and
        // nesting when() inside when() leaves Mockito with an unfinished stub.
        SearchHits<MessageDocument> page = hits(41, "msg-1");
        when(operations.search(any(NativeQuery.class), eq(MessageDocument.class)))
                .thenReturn(page);

        SearchResponse response = searchService.search(
                new SearchRequest("bonus", null, null, null, null, null, null, 0, 20, null));

        assertThat(response.hits()).hasSize(1);
        var hit = response.hits().get(0);
        assertThat(hit.id()).isEqualTo("msg-1");
        assertThat(hit.type()).isEqualTo(MessageType.EMAIL);
        assertThat(hit.subject()).isEqualTo("Bonus pool discussion");
        assertThat(hit.sender()).isEqualTo("alice.chen@smarsh.com");
        assertThat(hit.timestamp()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(hit.hasAttachment()).isTrue();
        assertThat(hit.held()).isFalse();
        assertThat(hit.score()).isEqualTo(4.25);
        // FR-3.3: the fragment is what the UI renders in bold.
        assertThat(hit.highlight()).containsEntry("subject", List.of("<em>Bonus</em> pool discussion"));
        // A partial last page still counts as a page, so this rounds up.
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
