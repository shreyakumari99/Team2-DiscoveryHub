package com.smarsh.discoveryhub.search.api;
 
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.json.JsonData;
import com.smarsh.discoveryhub.search.domain.MessageDocument;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.stereotype.Service;
 
import java.util.ArrayList;
import java.util.List;
 
/**
* Builds and runs the Elasticsearch query for a {@link SearchRequest} (FR-3).
*
* <p>Everything is assembled into a <em>single</em> {@code bool} query. That
* matters: {@code NativeQueryBuilder.withQuery} does not compose, so adding
* each filter as its own call produced a query where the clauses fought each
* other — selecting both EMAIL and CHAT asked for messages that were
* simultaneously both types and returned nothing at all, and picking two
* custodians required a message to involve <em>all</em> of them.
*
* <p>Structure:
* <ul>
*   <li>the free-text query goes in {@code must}, so it drives relevance;</li>
*   <li>every filter goes in {@code filter}, where it is a yes/no predicate —
*       it does not perturb scoring and Elasticsearch can cache it;</li>
*   <li>multi-valued filters (types, custodians) use {@code terms}, which is
*       an OR, because "show me Alice's and Bob's messages" means either.</li>
* </ul>
*
* <p>The free-text clause is built differently depending on {@link Mode}, and
* the distinction is deliberate — see that enum. An interactive search matches
* the final word as a prefix so typing "hi" finds "hire" and "hiring";
* resolving a set of ids to act on does not, because widening a legal hold's
* scope by stemming its search terms is not a UI nicety.
*/
@Service
public class SearchService {
 
    /** Fields the free-text query searches, and the ones we highlight (FR-3.1, FR-3.3). */
    private static final List<String> FULL_TEXT_FIELDS = List.of("subject", "body", "participants");
    /** Exact-match sub-field of the analyzed {@code participants} field. */
    private static final String PARTICIPANTS_EXACT = "participants.keyword";
    private static final String SENDER = "sender";
    /**
     * Below this, a prefix is not worth running: a single character matches a
     * large fraction of the corpus, every hit scores about the same, and the
     * result is noise rather than a search.
     */
    private static final int MIN_PREFIX_LENGTH = 2;
 
    /**
     * What a query is being built for. The two callers want genuinely different
     * matching semantics, so this is an explicit choice rather than a pair of
     * booleans at the call site.
     */
    private enum Mode {
        /**
         * A person typing in the UI: highlight the hits (FR-3.3) and treat the
         * last word as a prefix, since they may not have finished typing it.
         */
        INTERACTIVE,
        /**
         * Resolving the whole matching set to act on — legal-hold scope
         * (FR-4.1) or "add all results to case" (FR-3.6). Terms match exactly.
         * A hold for "hi" must not quietly freeze every message mentioning
         * "hire", "hiring" or "hidden"; the scope a hold claims has to be the
         * scope a reviewer would predict from reading it.
         */
        EXACT
    }
 
    private final ElasticsearchOperations operations;
 
    public SearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }
 
    public SearchResponse search(SearchRequest request) {
        int page = request.pageOrDefault();
        int size = request.sizeOrDefault();
 
        SearchHits<MessageDocument> hits = operations.search(
                buildQuery(request, PageRequest.of(page, size), Mode.INTERACTIVE), MessageDocument.class);
 
        List<SearchHit> out = new ArrayList<>();
        for (var hit : hits.getSearchHits()) {
            MessageDocument d = hit.getContent();
            out.add(new SearchHit(d.id(), d.type(), d.subject(), d.sender(), d.timestamp(),
                    d.hasAttachment(), d.held(), hit.getScore(), hit.getHighlightFields()));
        }
 
        long total = hits.getTotalHits();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new SearchResponse(request.query(), page, size, total, totalPages, out);
    }
 
    /**
     * Every message id matching the criteria, ignoring pagination — this backs
     * "add all results to case" (FR-3.6) and legal-hold scope resolution
     * (FR-4.1), both of which act on the whole result set rather than a page.
     *
     * @param limit hard cap, so one request can never try to materialise an
     *              unbounded set into memory
     */
    public List<String> matchingIds(SearchRequest request, int limit) {
        List<String> ids = new ArrayList<>();
        int pageSize = Math.min(limit, 1_000);
        for (int page = 0; ids.size() < limit; page++) {
            SearchHits<MessageDocument> hits = operations.search(
                    buildQuery(request, PageRequest.of(page, pageSize), Mode.EXACT), MessageDocument.class);
            if (hits.getSearchHits().isEmpty()) {
                break;
            }
            for (var hit : hits.getSearchHits()) {
                if (ids.size() >= limit) {
                    break;
                }
                ids.add(hit.getContent().id());
            }
            if ((long) (page + 1) * pageSize >= hits.getTotalHits()) {
                break;
            }
        }
        return ids;
    }
 
    // ---- query construction ----------------------------------------------
 
    private NativeQuery buildQuery(SearchRequest request, PageRequest pageable, Mode mode) {
        NativeQueryBuilder builder = new NativeQueryBuilder()
                .withPageable(pageable)
                .withQuery(Query.of(q -> q.bool(boolQuery(request, mode))));
 
        if (mode == Mode.INTERACTIVE) {
            builder.withHighlightQuery(new HighlightQuery(
                    new Highlight(FULL_TEXT_FIELDS.stream().map(HighlightField::new).toList()),
                    MessageDocument.class));
        }
 
        if ("date".equalsIgnoreCase(request.sortOrDefault())) {
            builder.withSort(s -> s.field(f -> f.field("timestamp").order(SortOrder.Desc)));
        } else {
            builder.withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
        }
        return builder.build();
    }
 
    private BoolQuery boolQuery(SearchRequest request, Mode mode) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
 
        String text = request.query();
        if (text != null && !text.isBlank()) {
            // multi_match over the three required fields rather than
            // query_string over everything, so a search cannot accidentally
            // match on an internal field like the source id.
            boolean prefix = mode == Mode.INTERACTIVE && lastWordIsWorthPrefixing(text);
            bool.must(m -> m.multiMatch(mm -> {
                mm.query(text).fields(FULL_TEXT_FIELDS);
                if (prefix) {
                    // bool_prefix matches every word but the last as a term and
                    // the last as a prefix, which is what makes "hi" find
                    // "hire" (FR-3.1). It needs no mapping change: it runs
                    // against the same analyzed text fields.
                    mm.type(TextQueryType.BoolPrefix);
                }
                return mm;
            }));
        } else {
            bool.must(m -> m.matchAll(ma -> ma));
        }
 
        if (isNotEmpty(request.types())) {
            List<co.elastic.clients.elasticsearch._types.FieldValue> values = request.types().stream()
                    .map(t -> co.elastic.clients.elasticsearch._types.FieldValue.of(t.name()))
                    .toList();
            bool.filter(f -> f.terms(t -> t.field("type").terms(v -> v.value(values))));
        }
 
        if (isNotEmpty(request.custodians())) {
            // A custodian matches if they sent the message or took part in it.
            List<co.elastic.clients.elasticsearch._types.FieldValue> values = request.custodians().stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .toList();
            bool.filter(f -> f.bool(b -> b
                    .should(s -> s.terms(t -> t.field(PARTICIPANTS_EXACT).terms(v -> v.value(values))))
                    .should(s -> s.terms(t -> t.field(SENDER).terms(v -> v.value(values))))
                    .minimumShouldMatch("1")));
        }
 
        if (request.hasAttachment() != null) {
            bool.filter(f -> f.term(t -> t.field("hasAttachment").value(request.hasAttachment())));
        }
        if (request.onHold() != null) {
            bool.filter(f -> f.term(t -> t.field("held").value(request.onHold())));
        }
        if (request.dateFrom() != null || request.dateTo() != null) {
            bool.filter(f -> f.range(r -> {
                r.field("timestamp");
                if (request.dateFrom() != null) {
                    r.gte(JsonData.of(request.dateFrom().toString()));
                }
                if (request.dateTo() != null) {
                    r.lte(JsonData.of(request.dateTo().toString()));
                }
                return r;
            }));
        }
 
        return bool.build();
    }
 
    private static boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
 
    /**
     * Only the final word is prefix-matched, so it is the only one whose length
     * decides whether prefixing is worthwhile. A trailing space means the word
     * is finished and there is nothing to complete.
     */
    private static boolean lastWordIsWorthPrefixing(String text) {
        if (!text.equals(text.stripTrailing())) {
            return false;
        }
        String[] words = text.strip().split("\\s+");
        return words[words.length - 1].length() >= MIN_PREFIX_LENGTH;
    }
}
 
