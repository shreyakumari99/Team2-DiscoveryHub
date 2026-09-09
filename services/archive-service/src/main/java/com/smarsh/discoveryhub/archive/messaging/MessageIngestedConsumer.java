package com.smarsh.discoveryhub.archive.messaging;

import com.smarsh.discoveryhub.events.AttachmentPayload;
import com.smarsh.discoveryhub.events.MessageArchivedEvent;
import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.archive.domain.ArchivedMessage;
import com.smarsh.discoveryhub.archive.domain.MessageRepository;
import com.smarsh.discoveryhub.archive.storage.AttachmentStore;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Consumes {@link Topics#MESSAGE_INGESTED} and durably stores each message.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Idempotency (FR-1.6):</b> look up by {@code sourceMessageId}; if it
 *       already exists, skip — never create a duplicate.</li>
 *   <li><b>Immutable id (FR-1.5):</b> assign a UUID that never changes.</li>
 *   <li><b>Attachments:</b> persist attachment bytes to S3, keeping only the
 *       object keys in the message document.</li>
 *   <li><b>Notify downstream:</b> publish {@link MessageArchivedEvent} so
 *       search-service can index it (FR-1.7 — searchable within ~30s).</li>
 * </ul>
 */
@Component
public class MessageIngestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestedConsumer.class);

    private final MessageRepository repository;
    private final AttachmentStore storage;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditTrail auditTrail;

    public MessageIngestedConsumer(MessageRepository repository,
                                   AttachmentStore storage,
                                   KafkaTemplate<String, Object> kafkaTemplate,
                                   AuditTrail auditTrail) {
        this.repository = repository;
        this.storage = storage;
        this.kafkaTemplate = kafkaTemplate;
        this.auditTrail = auditTrail;
    }

    @KafkaListener(topics = Topics.MESSAGE_INGESTED, groupId = "archive-service")
    public void onMessageIngested(MessageIngestedEvent event) {
        // Idempotency: a duplicate source id is a no-op.
        if (repository.findBySourceMessageId(event.sourceMessageId()).isPresent()) {
            log.debug("Duplicate sourceMessageId={} ignored", event.sourceMessageId());
            return;
        }

        String messageId = UUID.randomUUID().toString();

        List<String> attachmentKeys = new ArrayList<>();
        if (event.attachments() != null) {
            for (AttachmentPayload a : event.attachments()) {
                attachmentKeys.add(storage.putAttachment(messageId, a.name(), a.contentType(), a.contentBase64()));
            }
        }

        ArchivedMessage archived = ArchivedMessage.newlyArchived(
                messageId,
                event.sourceMessageId(),
                event.type(),
                event.subject(),
                event.body(),
                event.timestamp(),
                event.sender(),
                event.participants(),
                event.threadId(),
                attachmentKeys,
                Instant.now()
        );
        repository.save(archived);
        log.info("Archived messageId={} sourceMessageId={} type={}", messageId, event.sourceMessageId(), event.type());

        kafkaTemplate.send(Topics.MESSAGE_ARCHIVED, messageId, new MessageArchivedEvent(
                messageId,
                event.sourceMessageId(),
                event.type(),
                event.subject(),
                event.body(),
                event.timestamp(),
                event.sender(),
                event.participants(),
                event.threadId(),
                attachmentKeys,
                !attachmentKeys.isEmpty(),
                false,
                Instant.now()
        ));

        auditTrail.record(AuditRecord.action("MESSAGE_ARCHIVED")
                .on("MESSAGE", messageId)
                .by("archive-service")
                .after("sourceMessageId", event.sourceMessageId()));
    }
}
