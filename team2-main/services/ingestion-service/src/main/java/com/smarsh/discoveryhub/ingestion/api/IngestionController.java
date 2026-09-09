package com.smarsh.discoveryhub.ingestion.api;

import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Sample ingestion endpoint. Accepts a message, validates it, publishes a
 * {@link MessageIngestedEvent} to Kafka and returns 202 Accepted immediately
 * — storage happens asynchronously in archive-service.
 *
 * <p>This is the pattern the team extends (multipart attachment upload, more
 * validation, metrics, etc.).
 */
@RestController
@RequestMapping("/api/v1/messages")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditTrail auditTrail;

    public IngestionController(KafkaTemplate<String, Object> kafkaTemplate,
                               AuditTrail auditTrail) {
        this.kafkaTemplate = kafkaTemplate;
        this.auditTrail = auditTrail;
    }

    @PostMapping
    public ResponseEntity<IngestionResponse> ingest(@Valid @RequestBody IngestionRequest request) {
        MessageIngestedEvent event = new MessageIngestedEvent(
                request.sourceMessageId(),
                request.type(),
                request.subject(),
                request.body(),
                request.timestamp() != null ? request.timestamp() : Instant.now(),
                request.sender(),
                request.participants() != null ? request.participants() : List.of(),
                request.threadId(),
                request.attachments() != null ? request.attachments() : List.of()
        );

        // Key on sourceMessageId so duplicates land on the same partition and
        // archive-service can dedup in order (FR-1.6).
        kafkaTemplate.send(Topics.MESSAGE_INGESTED, event.sourceMessageId(), event);
        log.info("Accepted message sourceMessageId={} type={}", event.sourceMessageId(), event.type());

        auditTrail.record(AuditRecord.action("MESSAGE_ACCEPTED")
                .on("MESSAGE", event.sourceMessageId())
                .after("type", event.type().name())
                .after("attachments", event.attachments().size()));

        return ResponseEntity.accepted()
                .body(new IngestionResponse(event.sourceMessageId(), "ACCEPTED",
                        "message accepted for asynchronous archival"));
    }
}
