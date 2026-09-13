package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageArchivedEvent;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.search.domain.MessageDocument;
import com.smarsh.discoveryhub.search.domain.MessageSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Indexing side of the pipeline (FR-1.7): archive-service announces a stored
 * message and this consumer makes it searchable.
 *
 * <p>The mapping is asserted field by field on purpose. Every filter and
 * column in the UI reads one of these fields, so a component silently dropped
 * from the document does not fail anything here — it just makes that filter
 * quietly match nothing once the corpus is indexed.
 */
class MessageArchivedConsumerTest {

    private MessageSearchRepository repository;
    private MessageArchivedConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = mock(MessageSearchRepository.class);
        consumer = new MessageArchivedConsumer(repository);
    }

    private MessageArchivedEvent event(String messageId, boolean held) {
        return new MessageArchivedEvent(
                messageId,
                "src-" + messageId,
                MessageType.EMAIL,
                "Q3 forecast",
                "the numbers are attached",
                Instant.parse("2025-03-04T10:15:30Z"),
                "alice.chen@smarsh.com",
                List.of("alice.chen@smarsh.com", "bob.patel@smarsh.com"),
                "thread-7",
                List.of("attachments/" + messageId + "/forecast.xlsx"),
                true,
                held,
                Instant.parse("2025-03-04T10:15:31Z"));
    }

    private MessageDocument indexed(MessageArchivedEvent event) {
        consumer.onMessageArchived(event);
        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void everySearchableFieldIsCopiedOntoTheDocument() {
        MessageDocument doc = indexed(event("msg-1", false));

        assertThat(doc.id()).isEqualTo("msg-1");
        assertThat(doc.sourceMessageId()).isEqualTo("src-msg-1");
        assertThat(doc.type()).isEqualTo(MessageType.EMAIL);
        assertThat(doc.subject()).isEqualTo("Q3 forecast");
        assertThat(doc.body()).isEqualTo("the numbers are attached");
        assertThat(doc.timestamp()).isEqualTo(Instant.parse("2025-03-04T10:15:30Z"));
        assertThat(doc.sender()).isEqualTo("alice.chen@smarsh.com");
        assertThat(doc.participants())
                .containsExactly("alice.chen@smarsh.com", "bob.patel@smarsh.com");
        assertThat(doc.threadId()).isEqualTo("thread-7");
        assertThat(doc.hasAttachment()).isTrue();
        assertThat(doc.archivedAt()).isEqualTo(Instant.parse("2025-03-04T10:15:31Z"));
        // The S3 keys stay in the archive: indexing them would put a storage
        // path into a full-text field, where a custodian search could match
        // the key instead of the message.
        assertThat(doc.toString()).doesNotContain("forecast.xlsx");
    }

    /**
     * FR-4.4: {@code held} is denormalized into the index so the on-hold
     * filter and the hold badge need no call to archive-service. It is taken
     * from the event — this consumer never derives it, because archive owns
     * the overlapping-hold rule.
     */
    @Test
    void holdStateIsTakenFromTheEvent() {
        assertThat(indexed(event("msg-held", true)).held()).isTrue();
    }
}
