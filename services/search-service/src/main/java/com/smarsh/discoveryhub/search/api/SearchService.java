package com.smarsh.discoveryhub.search.api;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
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
 */
@Service
public class SearchService {

    /** Fields the free-text query searches, and the ones we highlight (FR-3.1, FR-3.3). */
    private static final List<String> FULL_TEXT_FIELDS = List.of("subject", "body", "participants");
    /** Exact-match sub-field of the analyzed {@code participants} field. */
    private static final String PARTICIPANTS_EXACT = "participants.keyword";
    private static final String SENDER = "sender";

    private final ElasticsearchOperations operations;

    public SearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    public SearchResponse search(SearchRequest request) {
        int page = request.pageOrDefault();
        int size = request.sizeOrDefault();

        SearchHits<MessageDocument> hits = operations.search(
                buildQuery(request, PageRequest.of(page, size), true), MessageDocument.class);

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
                    buildQuery(request, PageRequest.of(page, pageSize), false), MessageDocument.class);
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

    private NativeQuery buildQuery(SearchRequest request, PageRequest pageable, boolean highlight) {
        NativeQueryBuilder builder = new NativeQueryBuilder()
                .withPageable(pageable)
                .withQuery(Query.of(q -> q.bool(boolQuery(request))));

        if (highlight) {
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

    private BoolQuery boolQuery(SearchRequest request) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        String text = request.query();
        if (text != null && !text.isBlank()) {
            // multi_match over the three required fields rather than
            // query_string over everything, so a search cannot accidentally
            // match on an internal field like the source id.
            bool.must(m -> m.multiMatch(mm -> mm.query(text).fields(FULL_TEXT_FIELDS)));
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
}
