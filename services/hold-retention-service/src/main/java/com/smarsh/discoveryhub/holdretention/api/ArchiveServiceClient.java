package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.common.http.ServiceClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Talks to archive-service during disposition and for hold counts.
 *
 * <p>The delete call is where FR-4.6 is proved: archive refuses a held message
 * with 409, and that refusal is mapped to {@link DeleteResult#SKIPPED_HELD}
 * rather than treated as an error.
 */
@Component
public class ArchiveServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceClient.class);
    private static final String BACKEND = "archive-service";

    private static final ParameterizedTypeReference<Map<String, Object>> COUNT =
            new ParameterizedTypeReference<>() {
            };

    public enum DeleteResult {
        DELETED, SKIPPED_HELD, NOT_FOUND, ERROR
    }

    private final RestClient restClient;

    public ArchiveServiceClient(ServiceClients serviceClients,
                                @Value("${archive-service.url:http://localhost:8082}") String baseUrl) {
        this.restClient = serviceClients.forService(baseUrl);
    }

    /**
     * No {@code @Retry} here on purpose: a delete is not idempotent from the
     * caller's point of view, and the disposition record must reflect exactly
     * what the archive said. An unreachable archive yields ERROR, the run
     * records it, and the next scheduled pass picks the message up again.
     */
    @CircuitBreaker(name = BACKEND)
    public DeleteResult deleteMessage(String messageId, String reason) {
        try {
            restClient.delete()
                    .uri("/api/v1/messages/{id}?reason={reason}", messageId, reason)
                    .retrieve()
                    .toBodilessEntity();
            return DeleteResult.DELETED;
        } catch (HttpClientErrorException.Conflict e) {
            // 409 = the message is on legal hold (FR-4.6).
            return DeleteResult.SKIPPED_HELD;
        } catch (HttpClientErrorException.NotFound e) {
            return DeleteResult.NOT_FOUND;
        } catch (Exception e) {
            log.warn("archive-service delete failed for {}: {}", messageId, e.getMessage());
            return DeleteResult.ERROR;
        }
    }

    /**
     * Distinct messages covered by any of these holds (FR-4.4). Returns 0 if
     * the archive is unreachable — a stale badge is a better failure than a
     * broken case page.
     */
    @CircuitBreaker(name = BACKEND)
    public long heldMessageCount(List<String> holdIds) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/api/v1/messages/held-count")
                    .body(Map.of("holdIds", holdIds))
                    .retrieve()
                    .body(COUNT);
            Object count = body == null ? null : body.get("heldMessages");
            return count instanceof Number n ? n.longValue() : 0L;
        } catch (Exception e) {
            log.warn("archive-service held-count failed: {}", e.getMessage());
            return 0L;
        }
    }
}
