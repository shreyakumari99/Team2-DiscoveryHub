package com.smarsh.discoveryhub.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.AuditEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes audit entries onto the shared {@link Topics#AUDIT_EVENTS} topic.
 *
 * <p>The {@code service} field is taken from {@code spring.application.name},
 * so each service is identified without five copies of this class each
 * hard-coding their own name — which is what this replaces.
 *
 * <p>Publishing is fire-and-forget and never propagates a failure to the
 * caller: losing an audit entry is bad, but failing a legal hold because the
 * broker hiccuped is worse, and Kafka's producer already retries internally.
 * A send failure is logged at error level so it is visible.
 */
public class KafkaAuditTrail implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditTrail.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public KafkaAuditTrail(KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper,
                           String serviceName) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    @Override
    public void record(AuditRecord entry) {
        try {
            AuditEvent event = new AuditEvent(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    resolveActor(entry),
                    serviceName,
                    entry.actionName(),
                    entry.entityType(),
                    entry.entityId(),
                    entry.caseId(),
                    toJson(entry.beforeDetails()),
                    toJson(entry.afterDetails()));

            kafkaTemplate.send(Topics.AUDIT_EVENTS, entry.entityId(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Failed to publish audit event {} for {} {}",
                                    entry.actionName(), entry.entityType(), entry.entityId(), error);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to build audit event {} for {} {}",
                    entry.actionName(), entry.entityType(), entry.entityId(), e);
        }
    }

    /**
     * An explicit actor wins; otherwise the investigator on the current
     * request; otherwise this service, for genuinely unattended work such as
     * the scheduled disposition job.
     */
    private String resolveActor(AuditRecord entry) {
        if (entry.actor() != null) {
            return entry.actor();
        }
        return CurrentActor.get().orElse(serviceName);
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Audit details were not serializable; storing a placeholder", e);
            return "{\"error\":\"details not serializable\"}";
        }
    }
}
