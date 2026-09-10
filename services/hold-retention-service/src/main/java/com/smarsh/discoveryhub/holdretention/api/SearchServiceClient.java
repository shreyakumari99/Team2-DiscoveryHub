package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.common.http.ServiceClients;
import com.smarsh.discoveryhub.events.MessageType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves a hold's scope, and finds messages past retention, by asking
 * search-service (FR-4.1, FR-5.2).
 *
 * <p>Both use search-service's {@code /search/ids} endpoint, which returns
 * every matching id rather than a page. Walking pages from here previously
 * meant the client had to know about totals and page arithmetic, and a
 * miscount silently truncated a legal hold — the worst kind of bug to have
 * here, because the result still looks like a successful hold.
 *
 * <p>Failures propagate rather than being swallowed into an empty list. A hold
 * that quietly resolves to "no messages" because search was down is
 * indistinguishable from a hold that correctly matched nothing;
 * {@link HoldService} records the failure instead so it can be retried.
 */
@Component
public class SearchServiceClient {

    private static final String BACKEND = "search-service";
    /** Matches the ceiling search-service enforces on a single id request. */
    private static final int MAX_IDS = 10_000;

    private static final ParameterizedTypeReference<List<String>> ID_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public SearchServiceClient(ServiceClients serviceClients,
                               @Value("${search-service.url:http://localhost:8083}") String baseUrl,
                               @Value("${search-service.scope-timeout-seconds:60}") long scopeTimeoutSeconds) {
        // A corpus-wide scope query legitimately takes longer than a normal
        // inter-service call, so this client gets a longer read timeout rather
        // than the platform default.
        this.restClient = serviceClients.forSlowService(baseUrl, Duration.ofSeconds(scopeTimeoutSeconds));
    }

    /** Every message id matching a hold's scope (FR-4.1). */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public List<String> resolveScope(List<String> custodians, Instant dateFrom, Instant dateTo,
                                     String searchTerms) {
        List<String> ids = restClient.get()
                .uri(uri -> {
                    uri.path("/api/v1/search/ids");
                    if (searchTerms != null && !searchTerms.isBlank()) {
                        uri.queryParam("query", searchTerms);
                    }
                    if (custodians != null && !custodians.isEmpty()) {
                        uri.queryParam("custodians", custodians.toArray());
                    }
                    if (dateFrom != null) {
                        uri.queryParam("dateFrom", dateFrom.toString());
                    }
                    if (dateTo != null) {
                        uri.queryParam("dateTo", dateTo.toString());
                    }
                    return uri.queryParam("limit", MAX_IDS).build();
                })
                .retrieve()
                .body(ID_LIST);
        return ids == null ? List.of() : ids;
    }

    /** Message ids of a given type older than the retention cut-off (FR-5.2). */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public List<String> findExpiredMessageIds(MessageType type, Instant cutoff) {
        List<String> ids = restClient.get()
                .uri(uri -> uri.path("/api/v1/search/ids")
                        .queryParam("types", type.name())
                        .queryParam("dateTo", cutoff.toString())
                        .queryParam("limit", MAX_IDS)
                        .build())
                .retrieve()
                .body(ID_LIST);
        return ids == null ? List.of() : ids;
    }
}
