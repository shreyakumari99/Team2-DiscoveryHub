package com.smarsh.discoveryhub.search.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.search.domain.SavedSearch;
import com.smarsh.discoveryhub.search.domain.SavedSearchRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Search REST API (FR-3).
 *
 * <pre>
 *   GET  /api/v1/search                     run a query (paginated, highlighted)
 *   GET  /api/v1/search/ids                 every matching id, for "add all results" (FR-3.6)
 *   POST /api/v1/saved-searches             save a query against a case (FR-3.5)
 *   GET  /api/v1/saved-searches?caseId=     saved searches for a case
 *   POST /api/v1/saved-searches/{id}/run    re-run a saved search (FR-3.5)
 * </pre>
 *
 * <p>Every executed search emits a SEARCH_EXECUTED audit event (FR-7.1). The
 * acting investigator is taken from the {@code X-Actor} header the UI sends,
 * so the audit trail names a person rather than the service.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    /** Ceiling on "add all results", so one click cannot pull an unbounded set. */
    private static final int MAX_BULK_IDS = 10_000;

    private final SearchService searchService;
    private final SavedSearchRepository savedSearchRepository;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;

    public SearchController(SearchService searchService,
                            SavedSearchRepository savedSearchRepository,
                            AuditTrail auditTrail,
                            ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.savedSearchRepository = savedSearchRepository;
        this.auditTrail = auditTrail;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/search")
    public SearchResponse search(Criteria criteria,
                                 @RequestParam(required = false) String caseId,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        SearchRequest request = criteria.toRequest();
        SearchResponse response = searchService.search(request);
        auditSearch(request, caseId, actor, response.totalHits());
        return response;
    }

    /**
     * Ids of <em>all</em> matching messages, not just the current page.
     *
     * <p>This is what makes the stretch half of FR-3.6 ("add all results to
     * case") possible: the UI would otherwise have to walk every page client
     * side, and a hold placed on "all results" would silently cover only the
     * twenty rows on screen.
     */
    @GetMapping("/search/ids")
    public List<String> searchIds(Criteria criteria,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String caseId,
                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        int cap = limit == null || limit <= 0 ? MAX_BULK_IDS : Math.min(limit, MAX_BULK_IDS);
        List<String> ids = searchService.matchingIds(criteria.toRequest(), cap);
        auditTrail.record(AuditRecord.action("SEARCH_RESULTS_RESOLVED")
                .on("SEARCH", UUID.randomUUID().toString())
                .by(actor)
                .inCase(caseId)
                .after("matchedIds", ids.size()));
        return ids;
    }

    @PostMapping("/saved-searches")
    public ResponseEntity<SavedSearch> saveSearch(@RequestBody SaveSearchRequest request,
                                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        // Reject a query we could never replay, rather than discovering it is
        // unusable at re-run time (FR-3.5).
        parseCriteria(request.queryJson());

        SavedSearch result = savedSearchRepository.save(new SavedSearch(
                UUID.randomUUID().toString(), request.caseId(), request.name(), request.queryJson()));

        auditTrail.record(AuditRecord.action("SEARCH_SAVED")
                .on("SAVED_SEARCH", result.getId())
                .by(actor)
                .inCase(request.caseId())
                .after("name", request.name()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/saved-searches")
    public List<SavedSearch> listSavedSearches(@RequestParam String caseId) {
        return savedSearchRepository.findByCaseId(caseId);
    }

    @GetMapping("/saved-searches/{id}")
    public ResponseEntity<SavedSearch> getSavedSearch(@PathVariable String id) {
        return savedSearchRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Re-run a saved search server-side (FR-3.5). Pagination can be overridden
     * so the caller can page through a saved query without re-saving it.
     */
    @PostMapping("/saved-searches/{id}/run")
    public SearchResponse runSavedSearch(@PathVariable String id,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestHeader(value = "X-Actor", required = false) String actor) {
        SavedSearch saved = savedSearchRepository.findById(id)
                .orElseThrow(() -> new SavedSearchNotFoundException(id));

        SearchRequest stored = parseCriteria(saved.getQueryJson());
        SearchRequest request = new SearchRequest(
                stored.query(), stored.dateFrom(), stored.dateTo(), stored.types(), stored.custodians(),
                stored.hasAttachment(), stored.onHold(),
                page != null ? page : stored.page(),
                size != null ? size : stored.size(),
                stored.sort());

        SearchResponse response = searchService.search(request);
        auditTrail.record(AuditRecord.action("SAVED_SEARCH_RUN")
                .on("SAVED_SEARCH", id)
                .by(actor)
                .inCase(saved.getCaseId())
                .after("hits", response.totalHits()));
        return response;
    }

    // ---- helpers ---------------------------------------------------------

    private void auditSearch(SearchRequest request, String caseId, String actor, long hits) {
        auditTrail.record(AuditRecord.action("SEARCH_EXECUTED")
                .on("SEARCH", UUID.randomUUID().toString())
                .by(actor)
                .inCase(caseId)
                .after("query", request.query() == null ? "" : request.query())
                .after("custodians", request.custodians())
                .after("hits", hits));
    }

    private SearchRequest parseCriteria(String queryJson) {
        if (queryJson == null || queryJson.isBlank()) {
            return new SearchRequest(null, null, null, null, null, null, null, null, null, null);
        }
        try {
            return objectMapper.readValue(queryJson, SearchRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Saved search criteria are not a valid search request: "
                    + e.getOriginalMessage(), e);
        }
    }

    /**
     * Query-parameter binding for a search. Kept as a separate type so the
     * plain search and the "all ids" endpoint accept exactly the same filters
     * — when they were two hand-written parameter lists, the second one
     * quietly drifted out of step with the first.
     */
    public record Criteria(String query,
                           Instant dateFrom,
                           Instant dateTo,
                           List<String> types,
                           List<String> custodians,
                           Boolean hasAttachment,
                           Boolean onHold,
                           Integer page,
                           Integer size,
                           String sort) {

        SearchRequest toRequest() {
            return new SearchRequest(query, dateFrom, dateTo, parseTypes(types), custodians,
                    hasAttachment, onHold, page, size, sort);
        }

        private static List<MessageType> parseTypes(List<String> types) {
            if (types == null || types.isEmpty()) {
                return null;
            }
            List<MessageType> parsed = types.stream()
                    .map(String::toUpperCase)
                    .map(Criteria::toType)
                    .filter(Objects::nonNull)
                    .toList();
            return parsed.isEmpty() ? null : parsed;
        }

        private static MessageType toType(String value) {
            try {
                return MessageType.valueOf(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
