package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.http.ServiceClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads a case's evidence list and lifecycle state from case-service
 * (FR-6.1, FR-2.5).
 */
@Component
public class CaseServiceClient {

    private static final String BACKEND = "case-service";
    private static final String CLOSED = "CLOSED";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> EVIDENCE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> CASE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CaseServiceClient(ServiceClients serviceClients,
                             @Value("${case-service.url:http://localhost:8084}") String baseUrl) {
        this.restClient = serviceClients.forService(baseUrl);
    }

    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public List<String> getEvidenceMessageIds(String caseId) {
        List<Map<String, Object>> evidence = restClient.get()
                .uri("/api/v1/cases/{caseId}/evidence", caseId)
                .retrieve()
                .body(EVIDENCE_LIST);
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .map(e -> (String) e.get("messageId"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Whether the case is closed, so an export can be refused (FR-2.5: "a
     * closed case becomes read-only — no new evidence, holds, or exports").
     *
     * <p>An unreachable case-service throws rather than defaulting to "open".
     * Guessing would let a closed case be exported during an outage, and a
     * read-only rule that lapses when a dependency is down is not a rule.
     */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public boolean isClosed(String caseId) {
        Map<String, Object> caseRecord = restClient.get()
                .uri("/api/v1/cases/{caseId}", caseId)
                .retrieve()
                .body(CASE);
        if (caseRecord == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }
        return CLOSED.equalsIgnoreCase(String.valueOf(caseRecord.get("state")));
    }
}
