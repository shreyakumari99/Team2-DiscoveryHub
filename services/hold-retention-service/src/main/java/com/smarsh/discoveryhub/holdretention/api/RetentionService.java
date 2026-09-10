package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.MessageType;
import com.smarsh.discoveryhub.holdretention.api.ArchiveServiceClient.DeleteResult;
import com.smarsh.discoveryhub.holdretention.domain.DispositionOutcome;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRun;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItem;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItemRepository;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunRepository;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicy;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Retention policies and the disposition process (FR-5).
 *
 * <p>For each communication type with a configured retention, the run asks
 * search-service for messages past the cut-off, then asks archive-service to
 * delete each one. Archive refuses anything on legal hold with a 409 — that is
 * the FR-4.6 proof — and the refusal is recorded as a skip.
 *
 * <p>Every message's outcome is written to {@link DispositionRunItem}, not
 * just tallied (FR-5.3). "We deleted 412 and skipped 38" is not a defensible
 * disposition record; "here are the 38 message ids we preserved because hold
 * X covered them" is.
 */
@Service
@Transactional
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int SECONDS_PER_MINUTE = 60;

    private final RetentionPolicyRepository policyRepository;
    private final DispositionRunRepository runRepository;
    private final DispositionRunItemRepository itemRepository;
    private final SearchServiceClient searchClient;
    private final ArchiveServiceClient archiveClient;
    private final AuditTrail auditTrail;

    public RetentionService(RetentionPolicyRepository policyRepository,
                            DispositionRunRepository runRepository,
                            DispositionRunItemRepository itemRepository,
                            SearchServiceClient searchClient,
                            ArchiveServiceClient archiveClient,
                            AuditTrail auditTrail) {
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.searchClient = searchClient;
        this.archiveClient = archiveClient;
        this.auditTrail = auditTrail;
    }

    public RetentionPolicy savePolicy(RetentionPolicy policy) {
        RetentionPolicy saved = policyRepository.save(policy);
        auditTrail.record(AuditRecord.action("RETENTION_POLICY_UPDATED")
                .on("RETENTION_POLICY", String.valueOf(saved.getType()))
                .after("retentionMinutes", saved.getRetentionMinutes()));
        return saved;
    }

    public List<RetentionPolicy> listPolicies() {
        return policyRepository.findAll();
    }

    /**
     * Run one disposition pass.
     *
     * @param trigger how the run was started — "schedule" or "manual" — so the
     *                record shows whether a deletion was routine or requested
     */
    public DispositionRun runDisposition(String trigger) {
        DispositionRun run = runRepository.save(new DispositionRun(Instant.now(), trigger));
        Map<DispositionOutcome, Long> tally = new EnumMap<>(DispositionOutcome.class);
        List<DispositionRunItem> items = new ArrayList<>();

        for (RetentionPolicy policy : policyRepository.findAll()) {
            MessageType type = policy.getType();
            Instant cutoff = Instant.now().minusSeconds(policy.getRetentionMinutes() * SECONDS_PER_MINUTE);
            List<String> expiredIds = searchClient.findExpiredMessageIds(type, cutoff);
            log.info("Disposition {}: {} messages of type {} are past the {} cut-off",
                    run.getId(), expiredIds.size(), type, cutoff);

            for (String messageId : expiredIds) {
                DispositionOutcome outcome = toOutcome(archiveClient.deleteMessage(messageId, "disposition"));
                tally.merge(outcome, 1L, Long::sum);
                items.add(new DispositionRunItem(run.getId(), messageId, type, outcome, cutoff));
            }
        }

        itemRepository.saveAll(items);

        run.setDeletedCount(tally.getOrDefault(DispositionOutcome.DELETED, 0L));
        run.setSkippedHeldCount(tally.getOrDefault(DispositionOutcome.SKIPPED_HELD, 0L));
        run.setNotFoundCount(tally.getOrDefault(DispositionOutcome.NOT_FOUND, 0L));
        run.setErrorCount(tally.getOrDefault(DispositionOutcome.ERROR, 0L));
        run.setFinishedAt(Instant.now());
        DispositionRun saved = runRepository.save(run);

        auditTrail.record(AuditRecord.action("DISPOSITION_RUN")
                .on("DISPOSITION_RUN", saved.getId())
                .after("trigger", trigger)
                .after("deleted", saved.getDeletedCount())
                .after("skippedHeld", saved.getSkippedHeldCount())
                .after("notFound", saved.getNotFoundCount())
                .after("errors", saved.getErrorCount()));

        log.info("Disposition run {} complete: deleted={}, skippedHeld={}, notFound={}, errors={}",
                saved.getId(), saved.getDeletedCount(), saved.getSkippedHeldCount(),
                saved.getNotFoundCount(), saved.getErrorCount());
        return saved;
    }

    public List<DispositionRun> listRuns() {
        return runRepository.findAll();
    }

    /** The per-message record for one run (FR-5.3). */
    public List<DispositionRunItem> runItems(String runId, DispositionOutcome outcome) {
        return outcome == null
                ? itemRepository.findByRunId(runId)
                : itemRepository.findByRunIdAndOutcome(runId, outcome);
    }

    private static DispositionOutcome toOutcome(DeleteResult result) {
        return switch (result) {
            case DELETED -> DispositionOutcome.DELETED;
            case SKIPPED_HELD -> DispositionOutcome.SKIPPED_HELD;
            case NOT_FOUND -> DispositionOutcome.NOT_FOUND;
            case ERROR -> DispositionOutcome.ERROR;
        };
    }
}
