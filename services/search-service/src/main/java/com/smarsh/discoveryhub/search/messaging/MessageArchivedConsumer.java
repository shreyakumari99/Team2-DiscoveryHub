package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageArchivedEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.search.domain.MessageDocument;
import com.smarsh.discoveryhub.search.domain.MessageSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link Topics#MESSAGE_ARCHIVED} and indexes each message into
 * Elasticsearch. This is what makes a message searchable "within a short delay
 * of ingestion" (FR-1.7 — target under 30s).
 *
 * <p>Idempotent: re-indexing the same id simply overwrites the document, so
 * redelivery is safe.
 */
@Component
public class MessageArchivedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageArchivedConsumer.class);

    private final MessageSearchRepository repository;

    public MessageArchivedConsumer(MessageSearchRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = Topics.MESSAGE_ARCHIVED, groupId = "search-service")
    public void onMessageArchived(MessageArchivedEvent event) {
        MessageDocument doc = new MessageDocument(
                event.messageId(),
                event.sourceMessageId(),
                event.type(),
                event.subject(),
                event.body(),
                event.timestamp(),
                event.sender(),
                event.participants(),
                event.threadId(),
                event.hasAttachment(),
                event.held(),
                event.archivedAt()
        );
        repository.save(doc);
        log.info("Indexed message id={} type={}", event.messageId(), event.type());
    }
}
