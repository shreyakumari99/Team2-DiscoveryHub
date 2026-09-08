package com.smarsh.discoveryhub.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.ingestion.api.IngestionController;
import com.smarsh.discoveryhub.ingestion.api.IngestionRequest;
import com.smarsh.discoveryhub.ingestion.messaging.AuditEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

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
    private AuditEventPublisher auditPublisher;

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
        verify(auditPublisher, times(1)).publish(eq("MESSAGE_ACCEPTED"), eq("MESSAGE"), eq("src-1"), any());
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
