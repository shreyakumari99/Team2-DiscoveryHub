package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.common.http.ServiceClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Reads a case's lifecycle state, so a hold cannot be placed on a closed case
 * (FR-2.5).
 */
@Component
public class CaseServiceClient {

    private static final String BACKEND = "case-service";
    private static final String CLOSED = "CLOSED";

    private static final ParameterizedTypeReference<Map<String, Object>> CASE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CaseServiceClient(ServiceClients serviceClients,
                             @Value("${case-service.url:http://localhost:8084}") String baseUrl) {
        this.restClient = serviceClients.forService(baseUrl);
    }

    /**
     * <p>Deliberately fails rather than assuming "open" when case-service is
     * unreachable. A read-only rule that quietly lapses during an outage is
     * not a rule, and placing a hold on a closed case is a compliance error
     * that is awkward to unwind.
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
