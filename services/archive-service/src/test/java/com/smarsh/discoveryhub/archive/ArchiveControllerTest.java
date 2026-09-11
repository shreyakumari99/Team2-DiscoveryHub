package com.smarsh.discoveryhub.archive;

import com.smarsh.discoveryhub.archive.api.ArchiveController;
import com.smarsh.discoveryhub.archive.api.GlobalExceptionHandler;
import com.smarsh.discoveryhub.archive.domain.ArchivedMessage;
import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger;
import com.smarsh.discoveryhub.archive.domain.MessageRepository;
import com.smarsh.discoveryhub.archive.storage.AttachmentStore;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.RecordingAuditTrail;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the archive REST API, using a standalone MockMvc setup so the
 * controller's dependencies are plain mocks injected directly — no Spring
 * {@code @MockBean} proxy layer involved. This keeps the FR-4.6 assertion
 * (held deletion blocked, message not deleted) deterministic.
 */
class ArchiveControllerTest {

    private MockMvc mockMvc;

    private MessageRepository repository;
    private LegalHoldLedger holdLedger;
    private AttachmentStore storage;
    private RecordingAuditTrail auditTrail;
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(MessageRepository.class);
        holdLedger = mock(LegalHoldLedger.class);
        storage = mock(AttachmentStore.class);
        auditTrail = new RecordingAuditTrail();
        kafkaTemplate = mock(KafkaTemplate.class);
        ArchiveController controller =
                new ArchiveController(repository, holdLedger, storage, auditTrail, kafkaTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ArchivedMessage message(String id, String... holdIds) {
        return new ArchivedMessage(id, "src-" + id, MessageType.EMAIL, "subject", "body",
                Instant.now(), "alice@smarsh.com", List.of("alice@smarsh.com"),
                "thread-1", List.of("attachments/" + id + "/report.pdf"), true,
                holdIds.length > 0, Set.of(holdIds), Instant.now());
    }

    @Test
    void returnsMessageById() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("m1")));

        mockMvc.perform(get("/api/v1/messages/m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.threadId").value("thread-1"));

        verify(repository).findById("m1");
    }

    @Test
    void missingMessageReturns404() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/messages/nope"))
                .andExpect(status().isNotFound());
    }

    /**
     * Ingestion only ever hands back the source id, so a caller that has just
     * submitted a message can name it no other way until this lookup resolves.
     */
    @Test
    void findsMessageByItsSourceId() throws Exception {
        when(repository.findBySourceMessageId("src-m1")).thenReturn(Optional.of(message("m1")));

        mockMvc.perform(get("/api/v1/messages/by-source/src-m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"))
                .andExpect(jsonPath("$.sourceMessageId").value("src-m1"));
    }

    /** Not archived yet, or archived and since disposed of — both are 404. */
    @Test
    void unknownSourceIdReturns404() throws Exception {
        when(repository.findBySourceMessageId(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/messages/by-source/src-nope"))
                .andExpect(status().isNotFound());
    }

    /** FR-6.3: export fetches thousands of messages, so bulk retrieval exists. */
    @Test
    void fetchesMessagesInBulk() throws Exception {
        when(repository.findAllById(List.of("m1", "m2")))
                .thenReturn(List.of(message("m1"), message("m2")));

        mockMvc.perform(get("/api/v1/messages").param("ids", "m1", "m2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** FR-6.3: export streams attachment bytes through the archive, not from the bucket directly. */
    @Test
    void streamsAttachmentBytes() throws Exception {
        when(repository.findById("m1")).thenReturn(Optional.of(message("m1")));
        when(storage.getAttachment("attachments/m1/report.pdf")).thenReturn("PDFDATA".getBytes());

        mockMvc.perform(get("/api/v1/messages/m1/attachments/0"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("PDFDATA".getBytes()));
    }

    @Test
    void attachmentIndexOutOfRangeReturns404() throws Exception {
        when(repository.findById("m1")).thenReturn(Optional.of(message("m1")));

        mockMvc.perform(get("/api/v1/messages/m1/attachments/7"))
                .andExpect(status().isNotFound());
    }

    /** FR-4.6 — the single most important guarantee in the platform. */
    @Test
    void deletingHeldMessageIsBlocked() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("held-1", "hold-a")));

        mockMvc.perform(delete("/api/v1/messages/held-1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("LEGAL_HOLD"));

        verify(repository, never()).deleteById(any());
        // A refused delete must not tell search the message is gone, or the
        // index would drop a message the archive still holds.
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    /**
     * A refused deletion is itself evidence. If someone tries to destroy a
     * message that is under legal hold, the trail has to show the attempt —
     * a silent 409 leaves no record that it ever happened.
     */
    @Test
    void blockedDeleteAttemptIsAudited() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("held-2", "hold-a")));

        mockMvc.perform(delete("/api/v1/messages/held-2").param("reason", "rogue-admin"))
                .andExpect(status().isConflict());

        AuditRecord entry = auditTrail.firstWithAction("MESSAGE_DELETE_BLOCKED").orElseThrow();
        assertThat(entry.entityType()).isEqualTo("MESSAGE");
        assertThat(entry.entityId()).isEqualTo("held-2");
        assertThat(entry.actor()).isEqualTo("rogue-admin");
    }

    @Test
    void deletingNonHeldMessageSucceeds() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("free-1")));

        mockMvc.perform(delete("/api/v1/messages/free-1").param("reason", "disposition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(repository).deleteById("free-1");
        verify(storage).removeAttachment("attachments/free-1/report.pdf");
    }

    @Test
    void deleteIsAuditedWithItsReason() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("free-2")));

        mockMvc.perform(delete("/api/v1/messages/free-2").param("reason", "disposition"))
                .andExpect(status().isOk());

        AuditRecord entry = auditTrail.firstWithAction("MESSAGE_DELETED").orElseThrow();
        assertThat(entry.entityType()).isEqualTo("MESSAGE");
        assertThat(entry.entityId()).isEqualTo("free-2");
        assertThat(entry.actor()).isEqualTo("disposition");
    }

    /**
     * FR-5.2: deleting a message has to reach the search index too. Without
     * this announcement the archive and the index diverge permanently — the
     * message stays searchable and every hit on it 404s, so a disposition run
     * looks as though it deleted nothing.
     */
    @Test
    void deletedMessageIsAnnouncedToSearch() throws Exception {
        when(repository.findById(anyString())).thenReturn(Optional.of(message("free-3")));

        mockMvc.perform(delete("/api/v1/messages/free-3").param("reason", "disposition"))
                .andExpect(status().isOk());

        ArgumentCaptor<MessageDeletedEvent> captor = ArgumentCaptor.forClass(MessageDeletedEvent.class);
        verify(kafkaTemplate).send(eq(Topics.MESSAGE_DELETED), eq("free-3"), captor.capture());
        assertThat(captor.getValue().messageId()).isEqualTo("free-3");
        assertThat(captor.getValue().reason()).isEqualTo("disposition");
        assertThat(captor.getValue().deletedAt()).isNotNull();
    }

    /** FR-8.2 dashboard counts come from the archive, the system of record. */
    @Test
    void reportsCorpusStats() throws Exception {
        when(repository.count()).thenReturn(10_000L);
        when(holdLedger.heldCount()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/messages/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMessages").value(10_000))
                .andExpect(jsonPath("$.heldMessages").value(42));
    }

    /**
     * FR-4.4: the held count for a case must not double-count a message that
     * two overlapping holds both cover.
     */
    @Test
    void heldCountDeduplicatesOverlappingHolds() throws Exception {
        when(holdLedger.messagesCoveredBy("hold-a")).thenReturn(List.of("m1", "m2"));
        when(holdLedger.messagesCoveredBy("hold-b")).thenReturn(List.of("m2", "m3"));

        mockMvc.perform(post("/api/v1/messages/held-count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdIds\":[\"hold-a\",\"hold-b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldMessages").value(3));
    }
}
