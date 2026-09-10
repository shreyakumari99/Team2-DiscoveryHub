package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Drops a document from the Elasticsearch index once archive-service has
 * permanently deleted the message it describes (FR-5.2).
 *
 * <p>Every other lifecycle step can be inferred from an event that carries the
 * message, but deletion cannot: the archive no longer has anything to hand
 * over. Without this consumer a disposed-of message stays searchable for ever
 * and every hit on it dead-ends in a 404, which makes the retention job look
 * like it did nothing.
 *
 * <p>Deliberately keyed off the deletion having already happened. The archive
 * is the system of record and has committed the removal, so this side cannot
 * refuse it — the only choices are to mirror it now or to mirror it late.
 */
@Component
public class MessageDeletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletedConsumer.class);
    private static final IndexCoordinates INDEX = IndexCoordinates.of("discoveryhub-messages");

    private final ElasticsearchOperations operations;

    public MessageDeletedConsumer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @KafkaListener(topics = Topics.MESSAGE_DELETED, groupId = "search-service")
    public void onMessageDeleted(MessageDeletedEvent event) {
        if (event.messageId() == null || event.messageId().isBlank()) {
            log.warn("Ignoring message-deleted event carrying no message id");
            return;
        }

        try {
            operations.delete(event.messageId(), INDEX);
            log.info("Message {} removed from the search index (reason={})",
                    event.messageId(), event.reason());
        } catch (Exception e) {
            // An already-absent document is the *expected* outcome of a
            // redelivery, and of a message deleted before it was ever indexed.
            // Rethrowing would park the partition and block every later event
            // behind a document that is already gone, which is strictly worse
            // than the no-op we want anyway.
            log.debug("Removing {} from the index was a no-op: {}",
                    event.messageId(), e.getMessage());
        }
    }
}
