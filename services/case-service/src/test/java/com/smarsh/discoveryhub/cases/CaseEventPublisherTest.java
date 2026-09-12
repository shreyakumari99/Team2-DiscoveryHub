package com.smarsh.discoveryhub.cases;

import com.smarsh.discoveryhub.cases.api.CaseEventPublisher;
import com.smarsh.discoveryhub.events.CaseEvent;
import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The publisher every case mutation announces through. Mocked out in the
 * service tests, so this is the only place the event contract itself is
 * checked.
 *
 * <p>What matters is not that a message is sent, but <em>what</em> is sent: the
 * topic other services subscribe to, and the key. The key is the case id, which
 * is what keeps a case's lifecycle events on one partition and therefore in
 * order — if a CLOSED event overtook the TRANSITIONED event that preceded it,
 * hold-retention-service could release a case's holds before it had processed
 * the transition that closed it.
 */
class CaseEventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesToTheCaseTopicKeyedByCaseId() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CaseEventPublisher publisher = new CaseEventPublisher(kafkaTemplate);

        publisher.publish("case-1", "TRANSITIONED", "Q3 Investigation",
                CaseState.UNDER_REVIEW, Map.of("from", "ACTIVE", "to", "UNDER_REVIEW"));

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(kafkaTemplate).send(eq(Topics.CASE_EVENTS), eq("case-1"), event.capture());

        CaseEvent sent = event.getValue();
        assertThat(sent.caseId()).isEqualTo("case-1");
        assertThat(sent.type()).isEqualTo("TRANSITIONED");
        assertThat(sent.caseName()).isEqualTo("Q3 Investigation");
        assertThat(sent.state()).isEqualTo(CaseState.UNDER_REVIEW);
        assertThat(sent.details()).containsEntry("from", "ACTIVE").containsEntry("to", "UNDER_REVIEW");
        // Stamped by the publisher rather than the caller, so every event is
        // timed consistently however it was triggered.
        assertThat(sent.occurredAt()).isNotNull();
    }

    /**
     * A CLOSED event is what makes hold-retention-service release the case's
     * holds, so it must carry the closed state and reach the same topic.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishesClosureSoHoldsCanBeReleased() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CaseEventPublisher publisher = new CaseEventPublisher(kafkaTemplate);

        publisher.publish("case-9", "CLOSED", "Closed matter", CaseState.CLOSED, Map.of());

        ArgumentCaptor<CaseEvent> event = ArgumentCaptor.forClass(CaseEvent.class);
        verify(kafkaTemplate).send(eq(Topics.CASE_EVENTS), eq("case-9"), event.capture());
        assertThat(event.getValue().type()).isEqualTo("CLOSED");
        assertThat(event.getValue().state()).isEqualTo(CaseState.CLOSED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toleratesEmptyDetails() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CaseEventPublisher publisher = new CaseEventPublisher(kafkaTemplate);

        publisher.publish("case-2", "CREATED", "New matter", CaseState.DRAFT, Map.of());

        verify(kafkaTemplate).send(eq(Topics.CASE_EVENTS), eq("case-2"), any(CaseEvent.class));
    }
}
