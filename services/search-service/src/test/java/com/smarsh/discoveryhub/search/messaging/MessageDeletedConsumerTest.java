package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * FR-5.2: once the archive has deleted a message, the index must stop
 * returning it. Anything left behind is a hit that dead-ends in a 404.
 */
class MessageDeletedConsumerTest {

    private static final IndexCoordinates INDEX = IndexCoordinates.of("discoveryhub-messages");

    private ElasticsearchOperations operations;
    private MessageDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        consumer = new MessageDeletedConsumer(operations);
    }

    private MessageDeletedEvent event(String id, String reason) {
        return new MessageDeletedEvent(id, reason, Instant.now());
    }

    @Test
    void deletedMessageIsRemovedFromTheIndex() {
        consumer.onMessageDeleted(event("msg-1", "disposition"));

        verify(operations).delete("msg-1", INDEX);
    }

    @Test
    void onlyTheDeletedMessageIsTouched() {
        consumer.onMessageDeleted(event("msg-2", "api"));

        verify(operations).delete("msg-2", INDEX);
        verify(operations, never()).delete("msg-1", INDEX);
    }

    @Test
    void eventWithoutAnIdIsIgnored() {
        consumer.onMessageDeleted(event(null, "api"));

        verify(operations, never()).delete(anyString(), eq(INDEX));
    }

    /**
     * The archive has already committed the delete, so a document that is
     * missing here is not a failure — it is a redelivery, or a message that
     * was disposed of before it ever finished indexing. Throwing would park
     * the partition and block every later deletion behind it.
     */
    @Test
    void absentDocumentIsNotAnError() {
        doThrow(new RuntimeException("document_missing_exception"))
                .when(operations).delete(anyString(), eq(INDEX));

        consumer.onMessageDeleted(event("msg-3", "disposition"));
    }
}