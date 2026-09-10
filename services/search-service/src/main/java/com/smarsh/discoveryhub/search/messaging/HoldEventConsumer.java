package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageHoldStateEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mirrors the authoritative hold state announced by archive-service on
 * {@link Topics#MESSAGE_HOLD_STATE} into the Elasticsearch index, so the
 * per-message hold badge and the on-hold filter work without a call to
 * archive-service (FR-4.4).
 *
 * <p>Deliberately consumes the <em>outcome</em> topic rather than raw
 * {@code hold-events}. Archive owns the overlapping-hold rule (FR-4.5); if
 * this consumer re-derived {@code held} from a RELEASED event it would clear
 * the badge on messages that are still protected by a second hold, and the
 * index would contradict the archive.
 *
 * <p>Uses a partial document update (not a full re-index) so only the
 * {@code held} field changes — efficient for holds covering thousands of
 * messages. If a message has not been indexed yet (race with initial
 * indexing), the update is skipped; the subsequent index write carries the
 * correct value.
 */
@Component
public class HoldEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HoldEventConsumer.class);
    private static final IndexCoordinates INDEX = IndexCoordinates.of("discoveryhub-messages");

    private final ElasticsearchOperations operations;

    public HoldEventConsumer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @KafkaListener(topics = Topics.MESSAGE_HOLD_STATE, groupId = "search-service")
    public void onHoldStateChanged(MessageHoldStateEvent event) {
        if (event.messageIds() == null || event.messageIds().isEmpty()) {
            log.debug("Hold state event for {} had no message ids; nothing to update", event.holdId());
            return;
        }

        for (String messageId : event.messageIds()) {
            UpdateQuery query = UpdateQuery.builder(messageId)
                    .withDocument(Document.from(Map.of("held", event.held())))
                    .build();
            try {
                operations.update(query, INDEX);
            } catch (Exception e) {
                // Document may not exist yet (race with initial indexing) — skip.
                log.debug("Partial update of held flag for {} skipped: {}", messageId, e.getMessage());
            }
        }

        log.info("Hold {} state mirrored: {} documents set held={}",
                event.holdId(), event.messageIds().size(), event.held());
    }
}
