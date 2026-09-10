package com.smarsh.discoveryhub.generator;

import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The corpus generator (FR-1.2). Runs once on startup and posts {@code count}
 * messages to the ingestion-service REST API exactly as a real client would.
 *
 * <p>Posts concurrently on virtual threads, but with the number of requests in
 * flight <em>bounded</em>. Firing all 10,000 at once is trivial to write and
 * does not work: it opens ten thousand simultaneous connections, overruns the
 * server's accept queue, and roughly a seventh of the corpus is refused. The
 * failures look like a platform fault but are self-inflicted load. A real
 * client would rate-limit itself, so this one does too.
 *
 * <p>Transient failures are retried once, because a refused connection under
 * load is not a reason to lose evidence. Retrying is safe: ingestion is
 * idempotent on {@code sourceMessageId} (FR-1.6), so a duplicate delivery can
 * never create a duplicate message.
 */
@Component
public class CorpusGenerator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusGenerator.class);
    private static final int RETRY_ATTEMPTS = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(250);

    private final RestClient restClient;
    private final String ingestionUrl;
    private final int count;
    private final int concurrency;

    public CorpusGenerator(@Value("${ingestion.url:http://localhost:8081}") String ingestionUrl,
                           @Value("${count:10000}") int count,
                           @Value("${concurrency:64}") int concurrency) {
        this.ingestionUrl = ingestionUrl;
        this.count = count;
        this.concurrency = Math.max(1, concurrency);
        this.restClient = RestClient.builder().baseUrl(ingestionUrl).build();
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Generating {} messages against ingestion-service at {} ({} requests in flight)",
                count, ingestionUrl, concurrency);
        MessageFactory factory = new MessageFactory(42L);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong attachments = new AtomicLong();
        AtomicInteger retried = new AtomicInteger();
        Semaphore inFlight = new Semaphore(concurrency);
        Instant start = Instant.now();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int n = i;
                futures.add(executor.submit(() -> {
                    MessageIngestedEvent event = factory.newMessage();
                    if (!event.attachments().isEmpty()) {
                        attachments.incrementAndGet();
                    }
                    inFlight.acquireUninterruptibly();
                    try {
                        if (post(event, n, retried)) {
                            ok.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.debug("Generator task failed", e);
                }
            }
        }

        long seconds = Duration.between(start, Instant.now()).getSeconds();
        log.info("Corpus generation complete: {} accepted, {} failed, {} with attachments, "
                        + "{} retried, in {}s",
                ok.get(), failed.get(), attachments.get(), retried.get(), seconds);

        if (failed.get() > 0) {
            log.warn("{} messages could not be ingested. Lower --concurrency and re-run; "
                    + "ingestion is idempotent, so re-running cannot duplicate anything.", failed.get());
        }
    }

    /** @return true once the message is accepted, false if every attempt failed */
    private boolean post(MessageIngestedEvent event, int index, AtomicInteger retried) {
        Map<String, Object> body = Map.of(
                "sourceMessageId", event.sourceMessageId(),
                "type", event.type().name(),
                "subject", event.subject() == null ? "" : event.subject(),
                "body", event.body(),
                "timestamp", event.timestamp().toString(),
                "sender", event.sender(),
                "participants", event.participants(),
                "threadId", event.threadId(),
                "attachments", event.attachments());

        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            try {
                restClient.post().uri("/api/v1/messages").body(body).retrieve().toBodilessEntity();
                return true;
            } catch (Exception e) {
                if (attempt == RETRY_ATTEMPTS) {
                    log.debug("Message #{} failed after {} attempts: {}", index, attempt, e.getMessage());
                    return false;
                }
                retried.incrementAndGet();
                sleep(RETRY_BACKOFF);
            }
        }
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
