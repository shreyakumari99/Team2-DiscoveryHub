package com.smarsh.discoveryhub.export.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.common.audit.CurrentActor;
import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportJobRepository;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import com.smarsh.discoveryhub.export.packaging.ExportItem;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder.BuiltPackage;
import com.smarsh.discoveryhub.export.packaging.Provenance;
import com.smarsh.discoveryhub.export.scope.ExportScopeResolver;
import com.smarsh.discoveryhub.export.scope.ExportScopeResolvers;
import com.smarsh.discoveryhub.export.storage.ExportStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Runs evidence exports (FR-6).
 *
 * <p>{@link #requestExport} records a QUEUED job and hands back its id
 * immediately, then does the work on a virtual thread so a package of
 * thousands of messages never blocks the caller (FR-6.2). The worker resolves
 * the scope, pulls each message and every one of its attachments, and hands
 * the bytes to {@link ExportPackageBuilder}.
 *
 * <p>This class deliberately does no packaging and no scope logic of its own —
 * those live in {@code packaging} and {@code scope}, where they are testable
 * without a database, a broker, or S3. What is left here is the job
 * lifecycle: status transitions, events, audit, and failure handling.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportJobRepository jobRepository;
    private final CaseServiceClient caseClient;
    private final ArchiveServiceClient archiveClient;
    private final ExportScopeResolvers scopeResolvers;
    private final ExportPackageBuilder packageBuilder;
    private final ExportStorageService storage;
    private final ExportEventPublisher eventPublisher;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Executor exportExecutor;

    public ExportService(ExportJobRepository jobRepository,
                         CaseServiceClient caseClient,
                         ArchiveServiceClient archiveClient,
                         ExportScopeResolvers scopeResolvers,
                         ExportPackageBuilder packageBuilder,
                         ExportStorageService storage,
                         ExportEventPublisher eventPublisher,
                         AuditTrail auditTrail,
                         ObjectMapper objectMapper,
                         @Qualifier(ExportAsyncConfig.EXPORT_EXECUTOR) Executor exportExecutor) {
        this.jobRepository = jobRepository;
        this.caseClient = caseClient;
        this.archiveClient = archiveClient;
        this.scopeResolvers = scopeResolvers;
        this.packageBuilder = packageBuilder;
        this.storage = storage;
        this.eventPublisher = eventPublisher;
        this.auditTrail = auditTrail;
        this.objectMapper = objectMapper;
        this.exportExecutor = exportExecutor;
    }

    // ---- job lifecycle ---------------------------------------------------

    @Transactional
    public ExportJob requestExport(String caseId, String scope, String requestedBy) {
        // FR-2.5: a closed case is read-only, exports included.
        if (caseClient.isClosed(caseId)) {
            throw new CaseClosedException(caseId);
        }
        // Fail a bad scope now, while the caller is still listening, rather
        // than as a mysterious FAILED job thirty seconds later.
        scopeResolvers.forScope(scope);

        ExportJob job = new ExportJob();
        job.setCaseId(caseId);
        job.setScope(scope != null && !scope.isBlank() ? scope : "evidence");
        job.setStatus(ExportStatus.QUEUED);
        job.setRequestedBy(actorFor(requestedBy));
        job.setRequestedAt(Instant.now());
        ExportJob saved = jobRepository.save(job);

        eventPublisher.publishExportEvent(saved.getId(), caseId, "REQUESTED", ExportStatus.QUEUED.name(), Map.of());
        auditTrail.record(AuditRecord.action("EXPORT_REQUESTED")
                .on("EXPORT", saved.getId())
                .by(saved.getRequestedBy())
                .inCase(caseId)
                .after("scope", saved.getScope()));

        scheduleRun(saved.getId(), saved.getRequestedBy());
        return saved;
    }

    /**
     * Start the export worker, but only once the transaction that created the
     * job has committed.
     *
     * <p>Dispatching immediately is a race: the worker begins while
     * {@link #requestExport} is still inside its transaction, its own
     * connection cannot see the new row, and {@link #run} finds nothing to do.
     * The job then sits at QUEUED forever — and cannot even be retried, since
     * retry only accepts a FAILED job. It fails intermittently, which is worse
     * than failing always: it works for small exports and strands large ones.
     */
    private void scheduleRun(String jobId, String actor) {
        Runnable dispatch = () -> exportExecutor.execute(() -> CurrentActor.runAs(actor, () -> run(jobId)));

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
     * Execute one job. Public so the recovery scheduler can re-drive a job
     * that was interrupted mid-flight.
     */
    public void run(String jobId) {
        ExportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            // Loud, not silent: a job that is never picked up stays QUEUED and
            // cannot be retried, so this must be visible in the logs.
            log.error("Export job {} was not found when its worker started; it will stay QUEUED", jobId);
            return;
        }
        try {
            markRunning(job);

            ExportScopeResolver resolver = scopeResolvers.forScope(job.getScope());
            List<String> messageIds = resolver.resolve(job.getCaseId(), job.getScope());
            List<ExportItem> items = collectItems(messageIds);

            BuiltPackage built = packageBuilder.build(items, new Provenance(
                    job.getId(), job.getCaseId(), resolver.describe(job.getScope()),
                    job.getRequestedBy(), Instant.now()));

            String objectKey = storage.putPackage(job.getId(), built.zipBytes());
            markCompleted(job, messageIds.size(), built, objectKey);
        } catch (Exception e) {
            markFailed(jobId, job, e);
        }
    }

    private void markRunning(ExportJob job) {
        job.setStatus(ExportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);
        eventPublisher.publishExportEvent(job.getId(), job.getCaseId(), "STARTED",
                ExportStatus.RUNNING.name(), Map.of());
    }

    private void markCompleted(ExportJob job, int messageCount, BuiltPackage built, String objectKey) {
        job.setItemCount(built.itemCount());
        job.setMessageCount(messageCount);
        job.setPackageObjectKey(objectKey);
        job.setPackageChecksum(built.packageChecksum());
        job.setContentChecksum(built.contentChecksum());
        job.setStatus(ExportStatus.COMPLETED);
        job.setFailureReason(null);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);

        eventPublisher.publishExportEvent(job.getId(), job.getCaseId(), "COMPLETED",
                ExportStatus.COMPLETED.name(),
                Map.of("items", String.valueOf(built.itemCount()),
                        "checksum", built.packageChecksum()));
        auditTrail.record(AuditRecord.action("EXPORT_COMPLETED")
                .on("EXPORT", job.getId())
                .by(job.getRequestedBy())
                .inCase(job.getCaseId())
                .after("messages", messageCount)
                .after("files", built.itemCount())
                .after("contentChecksum", built.contentChecksum()));
        log.info("Export {} completed: {} messages, {} files, contentChecksum={}",
                job.getId(), messageCount, built.itemCount(), built.contentChecksum());
    }

    private void markFailed(String jobId, ExportJob job, Exception cause) {
        log.error("Export {} failed", jobId, cause);
        ExportJob failed = jobRepository.findById(jobId).orElse(job);
        failed.setStatus(ExportStatus.FAILED);
        failed.setFailureReason(String.valueOf(cause.getMessage()));
        failed.setCompletedAt(Instant.now());
        jobRepository.save(failed);

        eventPublisher.publishExportEvent(jobId, failed.getCaseId(), "FAILED", ExportStatus.FAILED.name(),
                Map.of("reason", String.valueOf(cause.getMessage())));
        auditTrail.record(AuditRecord.action("EXPORT_FAILED")
                .on("EXPORT", jobId)
                .by(failed.getRequestedBy())
                .inCase(failed.getCaseId())
                .after("reason", cause.getMessage()));
    }

    /**
     * Fetch every message in scope plus all of its attachments (FR-6.3).
     *
     * <p>A message whose attachment has since been disposed of still exports:
     * the missing file is simply absent from the package and from the
     * manifest, rather than aborting an export of thousands of other items.
     */
    private List<ExportItem> collectItems(List<String> messageIds) throws Exception {
        List<ExportItem> items = new ArrayList<>();
        for (Map<String, Object> message : archiveClient.getMessages(messageIds)) {
            String messageId = String.valueOf(message.get("id"));
            items.add(ExportItem.message(messageId, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(message)));

            List<String> keys = attachmentKeys(message);
            for (int i = 0; i < keys.size(); i++) {
                byte[] bytes = archiveClient.getAttachment(messageId, i);
                if (bytes != null) {
                    items.add(ExportItem.attachment(messageId, fileNameOf(keys.get(i)), bytes));
                }
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static List<String> attachmentKeys(Map<String, Object> message) {
        Object keys = message.get("attachmentObjectKeys");
        return keys instanceof List<?> list
                ? list.stream().map(String::valueOf).filter(Objects::nonNull).toList()
                : List.of();
    }

    private static String fileNameOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
    }

    // ---- queries and actions --------------------------------------------

    public ExportJob getJob(String id) {
        return jobRepository.findById(id).orElseThrow(() -> new ExportJobNotFoundException(id));
    }

    public List<ExportJob> listJobs(String caseId) {
        return caseId != null
                ? jobRepository.findByCaseIdOrderByRequestedAtDesc(caseId)
                : jobRepository.findAll();
    }

    /** An expiring download URL for a completed job (FR-6.4). */
    public String downloadUrl(String jobId) {
        ExportJob job = getJob(jobId);
        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new ExportNotDownloadableException(jobId, job.getStatus());
        }
        auditTrail.record(AuditRecord.action("EXPORT_DOWNLOADED")
                .on("EXPORT", jobId)
                .inCase(job.getCaseId())
                .after("linkExpiresInMinutes", storage.linkExpiry().toMinutes()));
        return storage.presignedDownloadUrl(job.getPackageObjectKey());
    }

    /**
     * Retry a failed export (FR-6.6).
     *
     * <p>Only a FAILED job can be retried — "retrying" a completed one would
     * quietly produce a second package for the same evidence, and two
     * differently-named packages of the same material is exactly the ambiguity
     * a chain of custody must not have. The retry is a new job with a new id,
     * so it writes to a new object key and can neither overwrite nor be
     * confused with the partial output of the failed run.
     */
    @Transactional
    public ExportJob retry(String jobId) {
        ExportJob job = getJob(jobId);
        if (job.getStatus() != ExportStatus.FAILED) {
            throw new ExportNotRetryableException(jobId, job.getStatus());
        }
        return requestExport(job.getCaseId(), job.getScope(), job.getRequestedBy());
    }

    private static String actorFor(String requestedBy) {
        if (requestedBy != null && !requestedBy.isBlank()) {
            return requestedBy;
        }
        return CurrentActor.get().orElse("export-api");
    }
}
