package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.common.audit.CurrentActor;
import com.smarsh.discoveryhub.events.HoldEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.holdretention.domain.HoldEntity;
import com.smarsh.discoveryhub.holdretention.domain.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Places and releases legal holds (FR-4).
 *
 * <p>Placing a hold is asynchronous: {@link #placeHold} persists the hold and
 * returns its id immediately, then resolves the scope on a background thread
 * (FR-4.3). A hold over the whole corpus therefore returns in milliseconds
 * instead of making the investigator wait for a search over 10,000 messages.
 * Once resolved, a PLACED {@link HoldEvent} carries the concrete message ids
 * so archive-service can freeze them.
 *
 * <p>Releasing publishes a RELEASED event carrying <em>no</em> message ids.
 * Archive records which messages each hold covers, so it derives the release
 * scope itself and drops protection only where no other hold remains — which
 * is what makes overlapping holds correct (FR-4.5).
 */
@Service
@Transactional
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holdRepository;
    private final SearchServiceClient searchClient;
    private final CaseServiceClient caseClient;
    private final ArchiveServiceClient archiveClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditTrail auditTrail;
    private final Executor scopeExecutor;
    private final TransactionTemplate transactionTemplate;

    public HoldService(HoldRepository holdRepository,
                       SearchServiceClient searchClient,
                       CaseServiceClient caseClient,
                       ArchiveServiceClient archiveClient,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       AuditTrail auditTrail,
                       PlatformTransactionManager transactionManager,
                       @Qualifier(HoldAsyncConfig.SCOPE_EXECUTOR) Executor scopeExecutor) {
        this.holdRepository = holdRepository;
        this.searchClient = searchClient;
        this.caseClient = caseClient;
        this.archiveClient = archiveClient;
        this.kafkaTemplate = kafkaTemplate;
        this.auditTrail = auditTrail;
        this.scopeExecutor = scopeExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public HoldEntity placeHold(PlaceHoldRequest request) {
        // FR-2.5: a closed case is read-only, and that includes new holds.
        if (caseClient.isClosed(request.caseId())) {
            throw new CaseClosedException(request.caseId());
        }

        HoldEntity hold = new HoldEntity();
        hold.setCaseId(request.caseId());
        hold.setActive(true);
        hold.setCustodians(request.custodians() != null ? request.custodians() : List.of());
        hold.setDateFrom(request.dateFrom());
        hold.setDateTo(request.dateTo());
        hold.setSearchTerms(request.searchTerms());
        hold.setPlacedAt(Instant.now());
        hold.setMessageCount(0);
        hold.setScopeResolved(false);
        HoldEntity saved = holdRepository.save(hold);

        auditTrail.record(AuditRecord.action("HOLD_PLACED")
                .on("HOLD", saved.getId())
                .inCase(request.caseId())
                .after("custodians", saved.getCustodians())
                .after("dateFrom", String.valueOf(saved.getDateFrom()))
                .after("dateTo", String.valueOf(saved.getDateTo()))
                .after("searchTerms", saved.getSearchTerms()));

        scheduleScopeResolution(saved.getId());
        return saved;
    }

    /**
     * Kick off scope resolution on a background thread, but only once the
     * transaction that created the hold has committed.
     *
     * <p>This ordering is essential and easy to get wrong. Dispatching
     * immediately starts the worker while {@link #placeHold} is still inside
     * its transaction, so the worker's own connection cannot see the new row,
     * finds nothing, and gives up — leaving a hold that is active but protects
     * nothing. Being a race, it fails intermittently: the first hold usually
     * works and a second one placed moments later does not.
     */
    private void scheduleScopeResolution(String holdId) {
        // Captured explicitly: the worker runs after the request thread has
        // gone, so it cannot read the actor from the request scope.
        String actor = CurrentActor.get().orElse(null);
        Runnable dispatch = () ->
                scopeExecutor.execute(() -> CurrentActor.runAs(actor, () -> resolveScopeAndPublish(holdId)));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    /**
     * Resolve a hold's scope and announce it. Public so it can be re-driven
     * for a hold whose resolution failed while search-service was down —
     * without this, such a hold would sit permanently at zero messages while
     * appearing active, which is the worst possible failure mode for a legal
     * hold.
     */
    public void resolveScopeAndPublish(String holdId) {
        HoldEntity hold = inOwnTransaction(() -> holdRepository.findWithCustodiansById(holdId).orElse(null));
        if (hold == null) {
            // Loud, not silent: a hold that exists but never resolves protects
            // nothing while looking active, which is the worst outcome here.
            log.error("Hold {} could not be found for scope resolution; it will protect nothing "
                    + "until resolution is re-run", holdId);
            return;
        }
        if (!hold.isActive()) {
            log.info("Hold {} was released before its scope resolved; nothing to do", holdId);
            return;
        }
        try {
            // Deliberately outside a transaction: this can page through the
            // whole corpus, and holding a database connection open for the
            // duration would tie up the pool for no reason.
            List<String> ids = searchClient.resolveScope(
                    hold.getCustodians(), hold.getDateFrom(), hold.getDateTo(), hold.getSearchTerms());

            inOwnTransaction(() -> {
                HoldEntity current = holdRepository.findWithCustodiansById(holdId).orElseThrow();
                current.setMessageCount(ids.size());
                current.setScopeResolved(true);
                current.setScopeResolvedAt(Instant.now());
                current.setScopeFailureReason(null);
                return holdRepository.save(current);
            });

            kafkaTemplate.send(Topics.HOLD_EVENTS, hold.getId(), new HoldEvent(
                    hold.getId(), hold.getCaseId(), "PLACED",
                    hold.getCustodians(), hold.getDateFrom(), hold.getDateTo(),
                    hold.getSearchTerms(), ids, Instant.now()));
            log.info("Hold {} scope resolved: {} messages", hold.getId(), ids.size());
        } catch (Exception e) {
            // The hold stays active and unresolved rather than silently
            // claiming a scope of zero; the flag is what the UI warns on and
            // what a retry looks for.
            inOwnTransaction(() -> {
                HoldEntity current = holdRepository.findWithCustodiansById(holdId).orElseThrow();
                current.setScopeResolved(false);
                current.setScopeFailureReason(String.valueOf(e.getMessage()));
                return holdRepository.save(current);
            });

            auditTrail.record(AuditRecord.action("HOLD_SCOPE_RESOLUTION_FAILED")
                    .on("HOLD", hold.getId())
                    .inCase(hold.getCaseId())
                    .after("error", e.getMessage()));
            log.warn("Hold {} scope resolution failed: {}", hold.getId(), e.getMessage());
        }
    }

    /**
     * Run a unit of persistence in its own transaction.
     *
     * <p>Needed because this work runs outside any inbound request: on a
     * background thread, and potentially from a transaction-synchronization
     * callback. Neither inherits {@code @Transactional} — the method is
     * reached by self-invocation, so the proxy is bypassed — and writes made
     * during an {@code afterCommit} callback are otherwise discarded without
     * error, which is exactly how a hold ends up permanently unresolved.
     */
    private <T> T inOwnTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    public HoldEntity releaseHold(String holdId, String reason) {
        HoldEntity hold = holdRepository.findWithCustodiansById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
        if (!hold.isActive()) {
            return hold;
        }
        hold.setActive(false);
        hold.setReleasedAt(Instant.now());
        HoldEntity saved = holdRepository.save(hold);

        // No message ids: archive knows which messages this hold covers, and
        // re-deriving them here could release a different set than was frozen.
        kafkaTemplate.send(Topics.HOLD_EVENTS, hold.getId(), new HoldEvent(
                hold.getId(), hold.getCaseId(), "RELEASED",
                hold.getCustodians(), hold.getDateFrom(), hold.getDateTo(),
                hold.getSearchTerms(), List.of(), Instant.now()));

        auditTrail.record(AuditRecord.action("HOLD_RELEASED")
                .on("HOLD", hold.getId())
                .inCase(hold.getCaseId())
                .before("active", true)
                .after("active", false)
                .after("reason", reason != null ? reason : "manual"));
        return saved;
    }

    public int releaseHoldsForCase(String caseId, String reason) {
        List<HoldEntity> active = holdRepository.findByCaseIdAndActiveTrue(caseId);
        for (HoldEntity hold : active) {
            releaseHold(hold.getId(), reason);
        }
        return active.size();
    }

    public List<HoldEntity> listHolds(String caseId) {
        return caseId != null
                ? holdRepository.findByCaseIdWithCustodians(caseId)
                : holdRepository.findAllWithCustodians();
    }

    public HoldEntity getHold(String holdId) {
        return holdRepository.findWithCustodiansById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    public long activeHoldCount(String caseId) {
        return holdRepository.findByCaseIdAndActiveTrue(caseId).size();
    }

    /**
     * Distinct messages frozen by a case's active holds (FR-4.4 "the total
     * count of held items per case").
     *
     * <p>Asks the archive rather than summing each hold's own count: two holds
     * on the same case routinely overlap, and adding their counts would report
     * more held messages than exist.
     */
    public long heldMessageCount(String caseId) {
        List<String> activeHoldIds = holdRepository.findByCaseIdAndActiveTrue(caseId).stream()
                .map(HoldEntity::getId)
                .toList();
        return activeHoldIds.isEmpty() ? 0 : archiveClient.heldMessageCount(activeHoldIds);
    }
}
