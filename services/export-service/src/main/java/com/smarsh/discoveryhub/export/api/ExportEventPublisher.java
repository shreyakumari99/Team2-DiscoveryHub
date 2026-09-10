package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.events.ExportEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Publishes export job lifecycle events (FR-6.2) so the UI — and anything else
 * that cares — can follow a job from Queued to Completed or Failed.
 *
 * <p>Audit entries are not published here: those go through the shared
 * {@link com.smarsh.discoveryhub.common.audit.AuditTrail}, so every service
 * emits them the same way.
 */
@Component
public class ExportEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ExportEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishExportEvent(String jobId, String caseId, String type, String status,
                                   Map<String, String> details) {
        kafkaTemplate.send(Topics.EXPORT_EVENTS, jobId, new ExportEvent(
                jobId, caseId, type, status, Instant.now(), details));
    }
}
