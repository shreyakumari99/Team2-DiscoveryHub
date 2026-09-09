package com.smarsh.discoveryhub.archive;

import com.smarsh.discoveryhub.archive.domain.ArchivedMessage;
import com.smarsh.discoveryhub.archive.domain.MessageRepository;
import com.smarsh.discoveryhub.common.audit.RecordingAuditTrail;
import com.smarsh.discoveryhub.archive.messaging.MessageIngestedConsumer;
import com.smarsh.discoveryhub.archive.storage.AttachmentStore;
import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the idempotency check in {@link MessageIngestedConsumer} (FR-1.6):
 * re-submitting a message with the same {@code sourceMessageId} must not create a
 * duplicate — the consumer looks it up and skips if it already exists.
 *
 * <p>This covers the application-level idempotency guard. The database-level
 * guard is the unique index on {@code sourceMessageId} (see {@link ArchivedMessage}),
 * which is created automatically via {@code spring.data.mongodb.auto-index-creation: true}.
 */
class MessageIngestedConsumerIdempotencyTest {

    private MessageRepository repository;
    private AttachmentStore storage;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private RecordingAuditTrail auditTrail;
    private MessageIngestedConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(MessageRepository.class);
        storage = mock(AttachmentStore.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        auditTrail = new RecordingAuditTrail();
        consumer = new MessageIngestedConsumer(repository, storage, kafkaTemplate, auditTrail);
    }

    private MessageIngestedEvent event(String sourceMessageId) {
        return new MessageIngestedEvent(
                sourceMessageId, MessageType.EMAIL, "subject", "body",
                Instant.now(), "alice@smarsh.com", List.of("alice@smarsh.com"),
                "thread-1", List.of());
    }

    /**
     * FR-1.6: a re-submitted message (same sourceMessageId) is skipped — no
     * save, no Kafka publish, no audit event, no attachment storage.
     */
    @Test
    void duplicateSourceMessageIdIsSkipped() {
        // First submission: sourceMessageId not yet seen → repository returns empty.
        when(repository.findBySourceMessageId("src-1"))
                .thenReturn(Optional.empty());

        consumer.onMessageIngested(event("src-1"));

        // The first message IS saved.
        verify(repository).save(any(ArchivedMessage.class));

        // Second submission (same sourceMessageId): now repository returns the
        // existing message → consumer must skip.
        ArchivedMessage existing = ArchivedMessage.newlyArchived(
                "msg-1", "src-1", MessageType.EMAIL, "subject", "body",
                Instant.now(), "alice@smarsh.com", List.of("alice@smarsh.com"),
                "thread-1", List.of(), Instant.now());
        when(repository.findBySourceMessageId("src-1"))
                .thenReturn(Optional.of(existing));

        consumer.onMessageIngested(event("src-1"));

        // Still only ONE save call (from the first submission), no second save.
        verify(repository, times(1)).save(any(ArchivedMessage.class));
        // Exactly ONE Kafka publish (from the first submission), no second publish.
        verify(kafkaTemplate, times(1)).send(eq(Topics.MESSAGE_ARCHIVED), any(), any());
        // ...and exactly one audit entry, not two.
        assertThat(auditTrail.actions()).containsExactly("MESSAGE_ARCHIVED");
    }

    /**
     * FR-1.6: a new message with a different sourceMessageId is always archived.
     */
    @Test
    void newSourceMessageIdIsArchived() {
        when(repository.findBySourceMessageId("src-new")).thenReturn(Optional.empty());

        consumer.onMessageIngested(event("src-new"));

        verify(repository).save(any(ArchivedMessage.class));
    }
}
