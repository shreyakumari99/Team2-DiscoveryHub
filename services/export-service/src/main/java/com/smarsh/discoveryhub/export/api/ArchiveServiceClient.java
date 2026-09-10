package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.http.ServiceClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads message content and attachment bytes from archive-service for
 * packaging (FR-6.3).
 *
 * <p>Attachments are fetched through the archive's API rather than by reading
 * its bucket directly: the archive stays the only component that knows where
 * message content physically lives, so export never needs credentials for
 * someone else's data (NFR-1).
 */
@Component
public class ArchiveServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceClient.class);
    private static final String BACKEND = "archive-service";
    /** Keeps the query string well inside typical URL limits. */
    private static final int FETCH_BATCH = 100;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> MESSAGE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<String>> ID_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public ArchiveServiceClient(ServiceClients serviceClients,
                                @Value("${archive-service.url:http://localhost:8082}") String baseUrl) {
        this.restClient = serviceClients.forService(baseUrl);
    }

    /**
     * Fetch many messages at once. An export of a few thousand messages used
     * to be a few thousand sequential HTTP calls, which made a slow archive
     * look like a hung export.
     */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public List<Map<String, Object>> getMessages(List<String> messageIds) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < messageIds.size(); i += FETCH_BATCH) {
            List<String> batch = messageIds.subList(i, Math.min(messageIds.size(), i + FETCH_BATCH));
            List<Map<String, Object>> page = restClient.get()
                    .uri(uri -> uri.path("/api/v1/messages").queryParam("ids", batch.toArray()).build())
                    .retrieve()
                    .body(MESSAGE_LIST);
            if (page != null) {
                all.addAll(page);
            }
        }
        return all;
    }

    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public Map<String, Object> getMessage(String messageId) {
        return restClient.get()
                .uri("/api/v1/messages/{id}", messageId)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /**
     * Raw bytes of one attachment.
     *
     * @return the bytes, or {@code null} if the attachment is gone — a single
     *         missing file is recorded in the manifest rather than failing the
     *         whole package
     */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public byte[] getAttachment(String messageId, int index) {
        try {
            return restClient.get()
                    .uri("/api/v1/messages/{id}/attachments/{index}", messageId, index)
                    .retrieve()
                    .body(byte[].class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Attachment {} of message {} is missing from the archive", index, messageId);
            return null;
        }
    }

    /** Message ids frozen by a hold, for a hold-scoped export (FR-6.1). */
    @CircuitBreaker(name = BACKEND)
    @Retry(name = BACKEND)
    public List<String> getMessageIdsForHold(String holdId) {
        List<String> ids = restClient.get()
                .uri("/api/v1/messages/by-hold/{holdId}", holdId)
                .retrieve()
                .body(ID_LIST);
        return ids == null ? List.of() : ids;
    }
}
