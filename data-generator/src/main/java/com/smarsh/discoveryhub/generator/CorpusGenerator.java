package com.smarsh.discoveryhub.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The corpus generator (FR-1.2). Runs once on startup, posts {@code count}
 * messages to the ingestion-service REST API like a real client would, then
 * the process can be left running or stopped.
 *
 * <p>Posts concurrently (virtual threads) so 10,000 messages finish in well
 * under a minute against a local ingestion-service.
 */
@Component
public class CorpusGenerator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusGenerator.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String ingestionUrl;
    private final int count;

    public CorpusGenerator(@Value("${ingestion.url:http://localhost:8081}") String ingestionUrl,
                           @Value("${count:10000}") int count) {
        this.ingestionUrl = ingestionUrl;
        this.count = count;
        this.restClient = RestClient.builder().baseUrl(ingestionUrl).build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Generating {} messages against ingestion-service at {}", count, ingestionUrl);
        MessageFactory factory = new MessageFactory(42L);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong attachments = new AtomicLong();
        Instant start = Instant.now();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = i;
                futures.add(executor.submit(() -> {
                    MessageIngestedEvent event = factory.newMessage();
                    if (!event.attachments().isEmpty()) {
                        attachments.incrementAndGet();
                    }
                    try {
                        Map<String, Object> body = Map.of(
                                "sourceMessageId", event.sourceMessageId(),
                                "type", event.type().name(),
                                "subject", event.subject() == null ? "" : event.subject(),
                                "body", event.body(),
                                "timestamp", event.timestamp().toString(),
                                "sender", event.sender(),
                                "participants", event.participants(),
                                "threadId", event.threadId(),
                                "attachments", event.attachments()
                        );
                        restClient.post()
                                .uri("/api/v1/messages")
                                .body(body)
                                .retrieve()
                                .toBodilessEntity();
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        if (failed.get() <= 5) {
                            log.warn("Failed to ingest message #{}: {}", n, e.getMessage());
                        }
                    }
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        }

        long seconds = java.time.Duration.between(start, Instant.now()).getSeconds();
        log.info("Corpus generation complete: {} accepted, {} failed, {} with attachments, in {}s",
                ok.get(), failed.get(), attachments.get(), seconds);
    }
}
