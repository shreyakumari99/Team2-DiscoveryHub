package com.smarsh.discoveryhub.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.ingestion.api.IngestionController;
import com.smarsh.discoveryhub.ingestion.api.IngestionRequest;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the ingestion endpoint: no Kafka or Spring context is started.
 * KafkaTemplate and the audit publisher are mocked.
 */
@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockBean
    private AuditTrail auditTrail;

    @Test
    void acceptsValidMessage() throws Exception {
        IngestionRequest request = new IngestionRequest(
                "src-1", MessageType.EMAIL, "Quarterly review",
                "Please review the attached numbers.", Instant.now(),
                "alice@smarsh.com", List.of("alice@smarsh.com", "bob@smarsh.com"),
                "thread-1", List.of());

        when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceMessageId").value("src-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        verify(kafkaTemplate, times(1)).send(eq("message-ingested"), eq("src-1"), any());

        ArgumentCaptor<AuditRecord> audit = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditTrail, times(1)).record(audit.capture());
        assertThat(audit.getValue().actionName()).isEqualTo("MESSAGE_ACCEPTED");
        assertThat(audit.getValue().entityId()).isEqualTo("src-1");
    }

    /**
     * FR-1.4: ingestion is the component that <em>accepts</em>, not the one
     * that stores. It must answer 202 without waiting on anything downstream,
     * which is also what keeps ingestion working while archive is down
     * (NFR-2) — Kafka holds the backlog.
     */
    @Test
    void acceptanceDoesNotDependOnAnyDownstreamService() throws Exception {
        IngestionRequest request = new IngestionRequest(
                "src-async", MessageType.CHAT, null, "ping", Instant.now(),
                "alice@smarsh.com", List.of("alice@smarsh.com"), "thread-2", List.of());

        when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // The only collaborator touched is the broker; nothing is stored here.
        verify(kafkaTemplate, times(1)).send(eq("message-ingested"), eq("src-async"), any());
    }

    /** FR-1.6: keying on sourceMessageId keeps duplicates on one partition. */
    @Test
    void messagesAreKeyedByTheirIdempotencyKey() throws Exception {
        IngestionRequest request = new IngestionRequest(
                "src-dup", MessageType.EMAIL, "Subject", "body", Instant.now(),
                "alice@smarsh.com", List.of("alice@smarsh.com"), "thread-3", List.of());
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(null);

        String json = objectMapper.writeValueAsString(request);
        mockMvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(json));
        mockMvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(json));

        verify(kafkaTemplate, times(2)).send(eq("message-ingested"), eq("src-dup"), any());
    }

    @Test
    void rejectsBlankSourceMessageId() throws Exception {
        // sourceMessageId is blank -> validation should return 400
        String body = """
                {
                  "sourceMessageId": "",
                  "type": "EMAIL",
                  "body": "hello",
                  "sender": "alice@smarsh.com"
                }
                """;
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}
