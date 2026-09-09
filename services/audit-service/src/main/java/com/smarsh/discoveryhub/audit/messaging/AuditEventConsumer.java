package com.smarsh.discoveryhub.audit.messaging;

import com.smarsh.discoveryhub.audit.domain.AuditLogEntry;
import com.smarsh.discoveryhub.audit.domain.AuditLogRepository;
import com.smarsh.discoveryhub.events.AuditEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The single consumer of the shared {@link Topics#AUDIT_EVENTS} topic. Every
 * other service emits audit events; this is the only one that persists them.
 *
 * <p>Idempotent on {@code eventId}: if the same event is redelivered, the unique
 * constraint on {@code audit_log.event_id} will reject the duplicate (the team
 * should add a unique index on that column). For the skeleton we log and skip
 * the duplicate rather than crashing.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditLogRepository repository;

    public AuditEventConsumer(AuditLogRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = Topics.AUDIT_EVENTS, groupId = "audit-service")
    public void onAuditEvent(AuditEvent event) {
        try {
            repository.save(new AuditLogEntry(
                    event.eventId(),
                    event.timestamp(),
                    event.actor(),
                    event.service(),
                    event.action(),
                    event.entityType(),
                    event.entityId(),
                    event.caseId(),
                    event.beforeJson(),
                    event.afterJson()
            ));
            log.debug("Recorded audit event {} action={} entity={}",
                    event.eventId(), event.action(), event.entityType());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Duplicate eventId (redelivery) — idempotent skip.
            log.debug("Duplicate audit event {} ignored", event.eventId());
        }
    }
}
