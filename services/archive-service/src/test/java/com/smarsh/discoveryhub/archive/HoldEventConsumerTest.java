package com.smarsh.discoveryhub.archive;

import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger;
import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger.HoldChange;
import com.smarsh.discoveryhub.archive.messaging.HoldEventConsumer;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.RecordingAuditTrail;
import com.smarsh.discoveryhub.events.HoldEvent;
import com.smarsh.discoveryhub.events.MessageHoldStateEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adapter between {@code hold-events} and the ledger (FR-4.2, FR-4.4).
 *
 * <p>The overlapping-hold rule itself is tested in {@link LegalHoldLedgerTest};
 * what matters here is that this consumer stays thin and translates correctly.
 * Three properties are worth pinning:
 *
 * <ul>
 *   <li>a PLACED event applies the resolved scope, a RELEASED event does not —
 *       release derives its scope from the ledger instead;</li>
 *   <li>only messages whose protection <em>actually changed</em> are announced,
 *       so search-service never clears a badge on a message a second hold still
 *       covers;</li>
 *   <li>a large scope is announced in batches, so one corpus-wide hold cannot
 *       produce a Kafka message beyond the broker's size limit.</li>
 * </ul>
 */
class HoldEventConsumerTest {

    private LegalHoldLedger ledger;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private RecordingAuditTrail auditTrail;
    private HoldEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ledger = mock(LegalHoldLedger.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        auditTrail = new RecordingAuditTrail();
        consumer = new HoldEventConsumer(ledger, kafkaTemplate, auditTrail);
    }

    private HoldEvent event(String type, List<String> messageIds) {
        return new HoldEvent("hold-a", "case-1", type, List.of("alice@smarsh.com"),
                null, null, null, messageIds, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private List<MessageHoldStateEvent> announcements() {
        ArgumentCaptor<MessageHoldStateEvent> captor = ArgumentCaptor.forClass(MessageHoldStateEvent.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeast(0))
                .send(eq(Topics.MESSAGE_HOLD_STATE), anyString(), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void placedAppliesTheResolvedScopeAndAnnouncesTheOutcome() {
        when(ledger.apply("hold-a", List.of("m1", "m2")))
                .thenReturn(new HoldChange(List.of("m1", "m2"), List.of(), List.of()));

        consumer.onHoldEvent(event("PLACED", List.of("m1", "m2")));

        verify(ledger).apply("hold-a", List.of("m1", "m2"));
        verify(ledger, never()).release(anyString());

        List<MessageHoldStateEvent> sent = announcements();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).held()).isTrue();
        assertThat(sent.get(0).messageIds()).containsExactly("m1", "m2");
        assertThat(sent.get(0).holdId()).isEqualTo("hold-a");
        assertThat(sent.get(0).caseId()).isEqualTo("case-1");
    }

    /**
     * A placement whose scope resolved to nothing must not reach the ledger at
     * all. Treating it as a release would be catastrophic — an empty id list is
     * exactly what a RELEASED event carries.
     */
    @Test
    void placedWithAnEmptyScopeTouchesNothing() {
        consumer.onHoldEvent(event("PLACED", List.of()));

        verify(ledger, never()).apply(anyString(), any());
        verify(ledger, never()).release(anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        assertThat(auditTrail.entries()).isEmpty();
    }

    /** Null is treated the same way as an empty list, not dereferenced. */
    @Test
    void placedWithANullScopeTouchesNothing() {
        consumer.onHoldEvent(event("PLACED", null));

        verify(ledger, never()).apply(anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    /**
     * FR-4.5: the release scope comes from the ledger, never from the event.
     * The producer deliberately sends no ids, so a consumer that passed the
     * event's list to {@code apply} would silently protect nothing.
     */
    @Test
    void releasedDerivesItsScopeFromTheLedger() {
        when(ledger.release("hold-a")).thenReturn(new HoldChange(List.of(), List.of("m2"), List.of("m1")));

        consumer.onHoldEvent(event("RELEASED", List.of()));

        verify(ledger).release("hold-a");
        verify(ledger, never()).apply(anyString(), any());
    }

    /**
     * The heart of it: releasing one of two overlapping holds must announce
     * only the message that genuinely lost protection. Announcing {@code m1}
     * would make search-service clear a badge on a message the archive still
     * refuses to delete.
     */
    @Test
    void releaseAnnouncesOnlyMessagesThatActuallyLostProtection() {
        when(ledger.release("hold-a")).thenReturn(new HoldChange(List.of(), List.of("m2"), List.of("m1")));

        consumer.onHoldEvent(event("RELEASED", List.of()));

        List<MessageHoldStateEvent> sent = announcements();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).held()).isFalse();
        assertThat(sent.get(0).messageIds()).containsExactly("m2").doesNotContain("m1");
    }

    /** Nothing changed means nothing is published — no empty events on the topic. */
    @Test
    void aReleaseThatChangedNothingAnnouncesNothing() {
        when(ledger.release("hold-a")).thenReturn(new HoldChange(List.of(), List.of(), List.of()));

        consumer.onHoldEvent(event("RELEASED", List.of()));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        // The action is still audited, because "released, covered nothing" is
        // itself a fact the trail should carry.
        assertThat(auditTrail.recorded("HOLD_RELEASED")).isTrue();
    }

    /**
     * A corpus-wide hold resolves to thousands of ids. They are announced in
     * batches of 1,000 so no single Kafka message approaches the broker's
     * maximum size.
     */
    @Test
    void aLargeScopeIsAnnouncedInBatches() {
        List<String> ids = IntStream.range(0, 2_500).mapToObj(i -> "m" + i).toList();
        when(ledger.apply(eq("hold-a"), any())).thenReturn(new HoldChange(ids, List.of(), List.of()));

        consumer.onHoldEvent(event("PLACED", ids));

        // 2,500 ids at 1,000 per announcement.
        verify(kafkaTemplate, times(3)).send(eq(Topics.MESSAGE_HOLD_STATE), anyString(), any());
        List<MessageHoldStateEvent> sent = announcements();
        assertThat(sent.stream().mapToInt(e -> e.messageIds().size()).sum()).isEqualTo(2_500);
        assertThat(sent).allSatisfy(e -> assertThat(e.messageIds().size()).isLessThanOrEqualTo(1_000));
    }

    /** The type check is on the event's own wording, and is case-insensitive. */
    @Test
    void theTypeIsMatchedCaseInsensitively() {
        when(ledger.apply(eq("hold-a"), any())).thenReturn(new HoldChange(List.of("m1"), List.of(), List.of()));

        consumer.onHoldEvent(event("placed", List.of("m1")));

        verify(ledger).apply("hold-a", List.of("m1"));
    }

    /**
     * FR-7: the applied hold is attributed to the service that placed it, not
     * to archive-service, and carries the counts a reviewer needs — including
     * how many messages stayed protected because another hold covered them.
     */
    @Test
    void theOutcomeIsAuditedWithItsCounts() {
        when(ledger.release("hold-a")).thenReturn(new HoldChange(List.of(), List.of("m2"), List.of("m1")));

        consumer.onHoldEvent(event("RELEASED", List.of()));

        AuditRecord entry = auditTrail.firstWithAction("HOLD_RELEASED").orElseThrow();
        assertThat(entry.entityType()).isEqualTo("HOLD");
        assertThat(entry.entityId()).isEqualTo("hold-a");
        assertThat(entry.caseId()).isEqualTo("case-1");
        assertThat(entry.actor()).isEqualTo("hold-retention-service");
        assertThat(entry.afterDetails())
                .containsEntry("unprotected", 1)
                .containsEntry("stillHeldByOtherHold", 1);
    }

    @Test
    void anAppliedHoldIsAuditedAsApplied() {
        when(ledger.apply(eq("hold-a"), any())).thenReturn(new HoldChange(List.of("m1"), List.of(), List.of()));

        consumer.onHoldEvent(event("PLACED", List.of("m1")));

        assertThat(auditTrail.actions()).containsExactly("HOLD_APPLIED");
        assertThat(auditTrail.firstWithAction("HOLD_APPLIED").orElseThrow().afterDetails())
                .containsEntry("protected", 1);
    }
}
