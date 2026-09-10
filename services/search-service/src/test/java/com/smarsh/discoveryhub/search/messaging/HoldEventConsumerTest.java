package com.smarsh.discoveryhub.search.messaging;

import com.smarsh.discoveryhub.events.MessageHoldStateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search index mirrors whatever archive-service says the hold state is
 * (FR-4.4). It must not re-derive it: archive owns the overlapping-hold rule,
 * so this consumer takes {@code held} straight from the event.
 */
class HoldEventConsumerTest {

    private static final IndexCoordinates INDEX = IndexCoordinates.of("discoveryhub-messages");

    private ElasticsearchOperations operations;
    private HoldEventConsumer consumer;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        when(operations.update(any(UpdateQuery.class), eq(INDEX)))
                .thenReturn(mock(UpdateResponse.class));
        consumer = new HoldEventConsumer(operations);
    }

    private MessageHoldStateEvent event(boolean held, String... ids) {
        return new MessageHoldStateEvent("hold-1", "case-1", List.of(ids), held, Instant.now());
    }

    @Test
    void protectedMessagesAreMarkedHeld() {
        consumer.onHoldStateChanged(event(true, "msg-1", "msg-2", "msg-3"));

        ArgumentCaptor<UpdateQuery> captor = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations, times(3)).update(captor.capture(), eq(INDEX));
        assertThat(captor.getAllValues())
                .allSatisfy(q -> assertThat(q.getDocument().get("held")).isEqualTo(true));
    }

    @Test
    void unprotectedMessagesAreMarkedNotHeld() {
        consumer.onHoldStateChanged(event(false, "msg-1", "msg-2"));

        ArgumentCaptor<UpdateQuery> captor = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations, times(2)).update(captor.capture(), eq(INDEX));
        assertThat(captor.getAllValues())
                .allSatisfy(q -> assertThat(q.getDocument().get("held")).isEqualTo(false));
    }

    /**
     * FR-4.5: when a release leaves a message covered by another hold, archive
     * omits it from the "unprotected" announcement — so the index keeps the
     * badge. Nothing in this consumer may second-guess that.
     */
    @Test
    void onlyTheAnnouncedMessagesAreTouched() {
        consumer.onHoldStateChanged(event(false, "msg-2"));

        ArgumentCaptor<UpdateQuery> captor = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations, times(1)).update(captor.capture(), eq(INDEX));
        assertThat(captor.getValue().getId()).isEqualTo("msg-2");
    }

    @Test
    void emptyMessageIdsIsNoOp() {
        consumer.onHoldStateChanged(event(true));

        verify(operations, never()).update(any(UpdateQuery.class), eq(INDEX));
    }

    @Test
    void updateFailureIsSwallowed() {
        when(operations.update(any(UpdateQuery.class), eq(INDEX)))
                .thenThrow(new RuntimeException("document not found"));

        // A message not yet indexed must not poison the whole batch.
        consumer.onHoldStateChanged(event(true, "msg-1"));
    }
}
