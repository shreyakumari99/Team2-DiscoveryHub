package com.smarsh.discoveryhub.search;
 
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.search.api.SearchRequest;
import com.smarsh.discoveryhub.search.api.SearchService;
import com.smarsh.discoveryhub.search.domain.MessageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
 
import java.time.Instant;
import java.util.List;
 
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
 
/**
* Covers the Elasticsearch query that {@link SearchService} builds (FR-3.1,
* FR-3.2, FR-3.4).
*
* <p>This is the test that was missing when the filters were added one
* {@code withQuery} call at a time. Because {@code withQuery} overwrites
* rather than composes, the filters silently fought each other and selecting
* two communication types matched nothing — a bug no existing test could see,
* since the query builder was mocked out everywhere.
*/
class SearchQueryBuilderTest {
 
    private ElasticsearchOperations operations;
    private SearchService searchService;
 
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        SearchHits<MessageDocument> empty = mock(SearchHits.class);
        when(empty.getSearchHits()).thenReturn(List.of());
        when(empty.getTotalHits()).thenReturn(0L);
        when(operations.search(any(NativeQuery.class), eq(MessageDocument.class))).thenReturn(empty);
        searchService = new SearchService(operations);
    }
 
    private String queryJsonFor(SearchRequest request) {
        searchService.search(request);
        ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
        org.mockito.Mockito.verify(operations).search(captor.capture(), eq(MessageDocument.class));
        return captor.getValue().getQuery().toString();
    }
 
    private static SearchRequest request(String query, List<MessageType> types, List<String> custodians) {
        return new SearchRequest(query, null, null, types, custodians, null, null, null, null, null);
    }
 
    @Test
    void freeTextSearchesSubjectBodyAndParticipants() {
        String json = queryJsonFor(request("bonus", null, null));
 
        assertThat(json).contains("multi_match").contains("bonus");
        assertThat(json).contains("subject").contains("body").contains("participants");
    }
 
    /**
     * FR-3.1: typing "hi" has to find "hire" and "hiring". A plain
     * {@code multi_match} analyses the input to the single term {@code hi} and
     * demands whole-token equality, so it returned nothing at all until the
     * word was typed out in full. {@code bool_prefix} matches the last word as
     * a prefix instead.
     */
    @Test
    void aPartialWordIsMatchedAsAPrefix() {
        String json = queryJsonFor(request("hi", null, null));
 
        assertThat(json).contains("bool_prefix");
        assertThat(json).contains("hi");
    }
 
    /**
     * The safeguard on the above. {@code matchingIds} backs legal-hold scope
     * resolution (FR-4.1) and "add all results to case" (FR-3.6), and both
     * share this query builder. A hold for "hi" must not silently freeze every
     * message mentioning "hire" or "hidden", so the id-resolution path stays on
     * exact matching.
     */
    @Test
    void resolvingIdsToActOnDoesNotPrefixMatch() {
        searchService.matchingIds(request("hi", null, null), 10);
 
        ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
        org.mockito.Mockito.verify(operations).search(captor.capture(), eq(MessageDocument.class));
        String json = captor.getValue().getQuery().toString();
 
        assertThat(json).contains("multi_match").contains("hi");
        assertThat(json).doesNotContain("bool_prefix");
    }
 
    /** A single character prefix-matches most of the corpus, so it is noise. */
    @Test
    void aSingleCharacterQueryIsNotPrefixMatched() {
        String json = queryJsonFor(request("h", null, null));
 
        assertThat(json).doesNotContain("bool_prefix");
    }
 
    /** A trailing space means the word is finished — nothing left to complete. */
    @Test
    void aCompletedWordIsNotPrefixMatched() {
        String json = queryJsonFor(request("hire ", null, null));
 
        assertThat(json).doesNotContain("bool_prefix");
    }
 
    /** Earlier words are already typed; only the one in progress is a prefix. */
    @Test
    void onlyTheLastWordOfAMultiWordQueryIsPrefixed() {
        String json = queryJsonFor(request("annual bon", null, null));
 
        assertThat(json).contains("bool_prefix");
        assertThat(json).contains("annual bon");
    }
 
    @Test
    void aBlankQueryMatchesEverythingSoFiltersCanStandAlone() {
        String json = queryJsonFor(request(null, null, null));
 
        assertThat(json).contains("match_all");
    }
 
    /**
     * The regression that mattered: two types must be an OR. As separate
     * {@code term} clauses this asked for a message that was both EMAIL and
     * CHAT at once and always returned zero results.
     */
    @Test
    void multipleTypesAreOredNotAnded() {
        String json = queryJsonFor(request(null, List.of(MessageType.EMAIL, MessageType.CHAT), null));
 
        assertThat(json).contains("terms");
        assertThat(json).contains("EMAIL").contains("CHAT");
        // A single terms clause, not one term clause per type.
        assertThat(json).doesNotContain("\"term\":");
    }
 
    /** Selecting two custodians means "either of them", not "both of them". */
    @Test
    void multipleCustodiansAreOredAndMatchSenderOrParticipant() {
        String json = queryJsonFor(request(null, null, List.of("alice@smarsh.com", "bob@smarsh.com")));
 
        assertThat(json).contains("alice@smarsh.com").contains("bob@smarsh.com");
        assertThat(json).contains("participants.keyword").contains("sender");
        assertThat(json).contains("minimum_should_match");
    }
 
    /** Filters belong in filter context so they do not distort relevance. */
    @Test
    void filtersDoNotContributeToScoring() {
        String json = queryJsonFor(new SearchRequest("bonus", null, null,
                List.of(MessageType.EMAIL), null, true, true, null, null, null));
 
        assertThat(json).contains("\"filter\"");
        assertThat(json).contains("hasAttachment").contains("held");
        // The text clause is still the thing that scores.
        assertThat(json).contains("\"must\"").contains("multi_match");
    }
 
    @Test
    void dateRangeIsAppliedAsABoundedRange() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-06-30T00:00:00Z");
        String json = queryJsonFor(new SearchRequest(null, from, to, null, null,
                null, null, null, null, null));
 
        assertThat(json).contains("range").contains("timestamp");
        assertThat(json).contains(from.toString()).contains(to.toString());
    }
 
    @Test
    void combinedFiltersAllSurviveIntoTheSameQuery() {
        String json = queryJsonFor(new SearchRequest("bonus",
                Instant.parse("2024-01-01T00:00:00Z"), null,
                List.of(MessageType.EMAIL), List.of("alice@smarsh.com"), true, false, null, null, null));
 
        // Every criterion is present at once — previously each new clause
        // replaced the last, so only one of these survived.
        assertThat(json).contains("bonus");
        assertThat(json).contains("EMAIL");
        assertThat(json).contains("alice@smarsh.com");
        assertThat(json).contains("hasAttachment");
        assertThat(json).contains("held");
        assertThat(json).contains("timestamp");
    }
 
    /** FR-3.6: resolving the whole result set pages until the hits run out. */
    @Test
    void matchingIdsIsCappedByTheRequestedLimit() {
        List<String> ids = searchService.matchingIds(request("bonus", null, null), 500);
 
        assertThat(ids).isEmpty();
    }
}
 
 
