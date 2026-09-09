package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.CaseEvent;
import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Publishes {@link CaseEvent}s onto {@link Topics#CASE_EVENTS} so other services
 * (hold-retention, export) can react to case lifecycle changes asynchronously.
 */
@Component
public class CaseEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CaseEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String caseId, String type, String caseName, CaseState state, Map<String, String> details) {
        kafkaTemplate.send(Topics.CASE_EVENTS, caseId, new CaseEvent(
                caseId, type, caseName, state, Instant.now(), details));
    }
}
