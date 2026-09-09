package com.smarsh.discoveryhub.holdretention;

import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.RecordingAuditTrail;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.holdretention.api.ArchiveServiceClient;
import com.smarsh.discoveryhub.holdretention.api.ArchiveServiceClient.DeleteResult;
import com.smarsh.discoveryhub.holdretention.api.RetentionService;
import com.smarsh.discoveryhub.holdretention.api.SearchServiceClient;
import com.smarsh.discoveryhub.holdretention.domain.DispositionOutcome;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRun;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItem;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItemRepository;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunRepository;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicy;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The disposition process (FR-5), with in-memory fakes for the repositories.
 *
 * <p>The assertion that matters: a held message is skipped, not deleted — the
 * FR-4.6 proof seen from the retention side — and FR-5.3, that the run records
 * <em>which</em> messages those were, not merely how many.
 */
class DispositionLogicTest {

    private SearchServiceClient searchClient;
    private ArchiveServiceClient archiveClient;
    private RecordingAuditTrail auditTrail;
    private RetentionService retentionService;

    private final List<DispositionRunItem> storedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Map<MessageType, RetentionPolicy> policies = new ConcurrentHashMap<>();
        RetentionPolicyRepository policyRepository = mock(RetentionPolicyRepository.class);
        when(policyRepository.save(any())).thenAnswer(inv -> {
            RetentionPolicy p = inv.getArgument(0);
            policies.put(p.getType(), p);
            return p;
        });
        when(policyRepository.findAll()).thenAnswer(inv -> List.copyOf(policies.values()));

        DispositionRunRepository runRepository = mock(DispositionRunRepository.class);
        when(runRepository.save(any(DispositionRun.class))).thenAnswer(inv -> {
            DispositionRun run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID().toString());
            }
            return run;
        });

        DispositionRunItemRepository itemRepository = mock(DispositionRunItemRepository.class);
        when(itemRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<DispositionRunItem> items = inv.getArgument(0);
            items.forEach(storedItems::add);
            return storedItems;
        });

        searchClient = mock(SearchServiceClient.class);
        archiveClient = mock(ArchiveServiceClient.class);
        auditTrail = new RecordingAuditTrail();

        retentionService = new RetentionService(policyRepository, runRepository, itemRepository,
                searchClient, archiveClient, auditTrail);

        // Emails retained 0 minutes, so "now" is the cut-off and everything is expired.
        retentionService.savePolicy(new RetentionPolicy(MessageType.EMAIL, 0));
        auditTrail.clear();
    }

    private void expire(String... ids) {
        when(searchClient.findExpiredMessageIds(any(), any())).thenReturn(List.of(ids));
    }

    private void archiveAnswers(String messageId, DeleteResult result) {
        when(archiveClient.deleteMessage(messageId, "disposition")).thenReturn(result);
    }

    /** FR-4.6 / FR-5.2: expired-but-held messages survive disposition. */
    @Test
    void heldMessagesAreSkippedNotDeleted() {
        expire("free-1", "held-1", "free-2", "held-2");
        archiveAnswers("free-1", DeleteResult.DELETED);
        archiveAnswers("free-2", DeleteResult.DELETED);
        archiveAnswers("held-1", DeleteResult.SKIPPED_HELD);
        archiveAnswers("held-2", DeleteResult.SKIPPED_HELD);

        DispositionRun run = retentionService.runDisposition("manual");

        assertThat(run.getDeletedCount()).isEqualTo(2);
        assertThat(run.getSkippedHeldCount()).isEqualTo(2);
    }

    /**
     * FR-5.3: "every disposition run must record what was deleted, what was
     * skipped due to hold, and when". Counts alone would not let anyone answer
     * which communications were preserved.
     */
    @Test
    void theRunRecordsWhichMessagesWereDeletedAndWhichWerePreserved() {
        expire("free-1", "held-1");
        archiveAnswers("free-1", DeleteResult.DELETED);
        archiveAnswers("held-1", DeleteResult.SKIPPED_HELD);

        DispositionRun run = retentionService.runDisposition("manual");

        assertThat(storedItems).hasSize(2);
        assertThat(storedItems).allSatisfy(item -> {
            assertThat(item.getRunId()).isEqualTo(run.getId());
            assertThat(item.getMessageType()).isEqualTo(MessageType.EMAIL);
            assertThat(item.getRetentionCutoff()).isNotNull();
            assertThat(item.getDecidedAt()).isBefore(Instant.now().plusSeconds(1));
        });

        assertThat(itemsWith(DispositionOutcome.DELETED)).containsExactly("free-1");
        assertThat(itemsWith(DispositionOutcome.SKIPPED_HELD)).containsExactly("held-1");
    }

    private List<String> itemsWith(DispositionOutcome outcome) {
        return storedItems.stream()
                .filter(i -> i.getOutcome() == outcome)
                .map(DispositionRunItem::getMessageId)
                .toList();
    }

    /** An unreachable archive is recorded, not silently counted as a deletion. */
    @Test
    void anUnreachableArchiveIsRecordedAsAnErrorNotADeletion() {
        expire("msg-1");
        archiveAnswers("msg-1", DeleteResult.ERROR);

        DispositionRun run = retentionService.runDisposition("schedule");

        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(itemsWith(DispositionOutcome.ERROR)).containsExactly("msg-1");
    }

    @Test
    void alreadyDeletedMessagesAreNotCountedAsDeletions() {
        expire("gone-1");
        archiveAnswers("gone-1", DeleteResult.NOT_FOUND);

        DispositionRun run = retentionService.runDisposition("schedule");

        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getNotFoundCount()).isEqualTo(1);
    }

    @Test
    void emptyExpiredSetProducesAZeroRunAndDeletesNothing() {
        expire();

        DispositionRun run = retentionService.runDisposition("schedule");

        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHeldCount()).isZero();
        assertThat(run.getFinishedAt()).isNotNull();
        verify(archiveClient, never()).deleteMessage(any(), any());
    }

    /** FR-7.1: disposition runs are part of the audit trail. */
    @Test
    void theRunIsAudited() {
        expire("free-1", "held-1");
        archiveAnswers("free-1", DeleteResult.DELETED);
        archiveAnswers("held-1", DeleteResult.SKIPPED_HELD);

        retentionService.runDisposition("schedule");

        AuditRecord entry = auditTrail.firstWithAction("DISPOSITION_RUN").orElseThrow();
        assertThat(entry.entityType()).isEqualTo("DISPOSITION_RUN");
        assertThat(entry.afterDetails())
                .containsEntry("deleted", 1L)
                .containsEntry("skippedHeld", 1L)
                .containsEntry("trigger", "schedule");
    }

    /** FR-5.1: retention is configurable in minutes so a demo can observe it. */
    @Test
    void retentionIsConfigurablePerCommunicationType() {
        retentionService.savePolicy(new RetentionPolicy(MessageType.CHAT, 5));

        assertThat(retentionService.listPolicies())
                .extracting(RetentionPolicy::getType)
                .containsExactlyInAnyOrder(MessageType.EMAIL, MessageType.CHAT);
        assertThat(auditTrail.recorded("RETENTION_POLICY_UPDATED")).isTrue();
    }
}
