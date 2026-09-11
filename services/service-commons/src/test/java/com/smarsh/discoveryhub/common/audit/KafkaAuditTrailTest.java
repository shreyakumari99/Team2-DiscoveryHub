package com.smarsh.discoveryhub.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.AuditEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the audit publisher that every service now shares (FR-7.2), including
 * the JSON-escaping bug that hand-built detail strings used to have.
 */
class KafkaAuditTrailTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaAuditTrail auditTrail =
            new KafkaAuditTrail(kafkaTemplate, new ObjectMapper(), "test-service");

    @SuppressWarnings("unchecked")
    private AuditEvent publish(AuditRecord record) {
        when(kafkaTemplate.send(any(String.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        auditTrail.record(record);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(Topics.AUDIT_EVENTS), any(), captor.capture());
        return (AuditEvent) captor.getValue();
    }

    @Test
    void populatesEveryRequiredAuditField() {
        AuditEvent event = publish(AuditRecord.action("CASE_TRANSITIONED")
                .on("CASE", "case-1")
                .by("investigator@smarsh.com")
                .inCase("case-1")
                .before("state", "ACTIVE")
                .after("state", "CLOSED"));

        assertThat(event.eventId()).isNotBlank();
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.actor()).isEqualTo("investigator@smarsh.com");
        assertThat(event.service()).isEqualTo("test-service");
        assertThat(event.action()).isEqualTo("CASE_TRANSITIONED");
        assertThat(event.entityType()).isEqualTo("CASE");
        assertThat(event.entityId()).isEqualTo("case-1");
        assertThat(event.caseId()).isEqualTo("case-1");
        assertThat(event.beforeJson()).isEqualTo("{\"state\":\"ACTIVE\"}");
        assertThat(event.afterJson()).isEqualTo("{\"state\":\"CLOSED\"}");
    }

    @Test
    void detailsContainingQuotesProduceValidJson() {
        AuditEvent event = publish(AuditRecord.action("EXPORT_FAILED")
                .on("EXPORT", "job-1")
                .after("reason", "archive said \"not found\" for id \\ 7"));

        // Hand-concatenated JSON used to emit a broken document here.
        assertThat(event.afterJson()).isEqualTo(
                "{\"reason\":\"archive said \\\"not found\\\" for id \\\\ 7\"}");
    }

    @Test
    void omittedDetailsAreNullRatherThanEmptyJson() {
        AuditEvent event = publish(AuditRecord.action("SEARCH_EXECUTED").on("SEARCH", "s-1"));

        assertThat(event.beforeJson()).isNull();
        assertThat(event.afterJson()).isNull();
    }

    /**
     * FR-7.2: unattended work is attributed to the service, not to whoever
     * happened to use the thread last.
     */
    @Test
    void backgroundWorkIsAttributedToTheService() {
        CurrentActor.clear();

        AuditEvent event = publish(AuditRecord.action("DISPOSITION_RUN").on("DISPOSITION_RUN", "r-1"));

        assertThat(event.actor()).isEqualTo("test-service");
    }

    /** An action taken during a request is attributed to the investigator. */
    @Test
    void requestScopedWorkIsAttributedToTheInvestigator() {
        CurrentActor.set("investigator@smarsh.com");
        try {
            AuditEvent event = publish(AuditRecord.action("CASE_CREATED").on("CASE", "case-1"));

            assertThat(event.actor()).isEqualTo("investigator@smarsh.com");
        } finally {
            CurrentActor.clear();
        }
    }

    @Test
    void anExplicitActorAlwaysWins() {
        CurrentActor.set("investigator@smarsh.com");
        try {
            AuditEvent event = publish(AuditRecord.action("MESSAGE_DELETED")
                    .on("MESSAGE", "m-1").by("disposition"));

            assertThat(event.actor()).isEqualTo("disposition");
        } finally {
            CurrentActor.clear();
        }
    }

    @Test
    void aBrokerFailureNeverPropagatesToTheCaller() {
        when(kafkaTemplate.send(any(String.class), any(), any()))
                .thenThrow(new IllegalStateException("broker down"));

        // NFR-2: failing to audit must not fail the business operation.
        auditTrail.record(AuditRecord.action("HOLD_PLACED").on("HOLD", "h-1"));
    }
}
