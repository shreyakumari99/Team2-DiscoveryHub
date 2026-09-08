package com.smarsh.discoveryhub.ingestion.messaging;

import com.smarsh.discoveryhub.events.AuditEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Emits {@link AuditEvent}s onto the shared {@link Topics#AUDIT_EVENTS} topic.
 * Every significant action in every service goes through an equivalent publisher
 * — that is how the audit trail is built without any service writing directly
 * into the audit database (FR-7, NFR-1 "no shared database schemas").
 */
@Component
public class AuditEventPublisher {

    private static final String SOURCE = "ingestion-service";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AuditEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String action, String entityType, String entityId, String actor,
                        String caseId, String beforeJson, String afterJson) {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                actor,
                SOURCE,
                action,
                entityType,
                entityId,
                caseId,
                beforeJson,
                afterJson
        );
        kafkaTemplate.send(Topics.AUDIT_EVENTS, entityId, event);
    }

    public void publish(String action, String entityType, String entityId, String actor) {
        publish(action, entityType, entityId, actor, null, null, null);
    }
}
