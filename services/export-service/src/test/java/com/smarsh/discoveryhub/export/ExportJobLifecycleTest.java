package com.smarsh.discoveryhub.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.RecordingAuditTrail;
import com.smarsh.discoveryhub.common.storage.InMemoryObjectStore;
import com.smarsh.discoveryhub.export.api.ArchiveServiceClient;
import com.smarsh.discoveryhub.export.api.CaseServiceClient;
import com.smarsh.discoveryhub.export.api.ExportEventPublisher;
import com.smarsh.discoveryhub.export.api.ExportService;
import com.smarsh.discoveryhub.export.api.ExportVerificationService;
import com.smarsh.discoveryhub.export.api.ExportVerificationService.VerificationResult;
import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportJobRepository;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder;
import com.smarsh.discoveryhub.export.scope.CaseEvidenceScopeResolver;
import com.smarsh.discoveryhub.export.scope.ExportScopeResolvers;
import com.smarsh.discoveryhub.export.scope.HoldScopeResolver;
import com.smarsh.discoveryhub.export.storage.ExportStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export job lifecycle (FR-6.1, FR-6.2, FR-6.6) and the read-only rule for
 * closed cases (FR-2.5).
 *
 * <p>Uses a real package builder and an in-memory object store, so a job runs
 * end to end and the resulting package is a genuine artifact — only the other
 * services and the database are mocked.
 */
class ExportJobLifecycleTest {

    private ExportJobRepository jobRepository;
    private CaseServiceClient caseClient;
    private ArchiveServiceClient archiveClient;
    private InMemoryObjectStore objectStore;
    private ExportStorageService storage;
    private RecordingAuditTrail auditTrail;
    private ExportService service;
    private ExportVerificationService verification;

    private final Map<String, ExportJob> saved = new HashMap<>();
    private final List<Runnable> queued = new java.util.ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        jobRepository = mock(ExportJobRepository.class);
        when(jobRepository.save(any(ExportJob.class))).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            if (job.getId() == null) {
                job.setId(UUID.randomUUID().toString());
            }
            saved.put(job.getId(), job);
            return job;
        });
        when(jobRepository.findById(anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(saved.get(inv.getArgument(0))));

        caseClient = mock(CaseServiceClient.class);
        when(caseClient.isClosed(anyString())).thenReturn(false);
        when(caseClient.getEvidenceMessageIds("case-1")).thenReturn(List.of("msg-1", "msg-2"));

        archiveClient = mock(ArchiveServiceClient.class);
        when(archiveClient.getMessages(any())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().map(ExportJobLifecycleTest::message).toList();
        });
        when(archiveClient.getAttachment(anyString(), anyInt()))
                .thenReturn("PDF-BYTES".getBytes(StandardCharsets.UTF_8));

        objectStore = new InMemoryObjectStore("discoveryhub-exports");
        storage = new ExportStorageService(objectStore, 30);
        auditTrail = new RecordingAuditTrail();

        ExportScopeResolvers resolvers = new ExportScopeResolvers(List.of(
                new CaseEvidenceScopeResolver(caseClient), new HoldScopeResolver(archiveClient)));

        // A deliberately inert executor: requestExport queues the work, and
        // each test drives run() itself. Racing a real background thread would
        // make these assertions timing-dependent.
        service = new ExportService(jobRepository, caseClient, archiveClient, resolvers,
                new ExportPackageBuilder(objectMapper), storage,
                mock(ExportEventPublisher.class), auditTrail, objectMapper, task -> queued.add(task));
        verification = new ExportVerificationService(storage, objectMapper, auditTrail);
    }

    private static Map<String, Object> message(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("body", "Body of " + id);
        m.put("attachmentObjectKeys", List.of("attachments/" + id + "/report.pdf"));
        return m;
    }

    /** FR-6.2: the caller gets a job id immediately, and the work completes behind it. */
    @Test
    void anExportRunsToCompletionAndProducesAStoredPackage() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        assertThat(job.getId()).isNotBlank();

        service.run(job.getId());

        ExportJob finished = saved.get(job.getId());
        assertThat(finished.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(finished.getMessageCount()).isEqualTo(2);
        // Two messages plus one attachment each.
        assertThat(finished.getItemCount()).isEqualTo(4);
        assertThat(finished.getPackageChecksum()).isNotBlank();
        assertThat(finished.getContentChecksum()).isNotBlank();
        assertThat(objectStore.contains(finished.getPackageObjectKey())).isTrue();
    }

    /** FR-6.1: a hold-scoped export packages what the hold actually froze. */
    @Test
    void aHoldScopedExportUsesTheHoldsFrozenMessages() {
        when(archiveClient.getMessageIdsForHold("hold-9")).thenReturn(List.of("msg-7", "msg-8", "msg-9"));

        ExportJob job = service.requestExport("case-1", "hold:hold-9", "investigator@smarsh.com");
        service.run(job.getId());

        ExportJob finished = saved.get(job.getId());
        assertThat(finished.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        // The hold's three messages, not the case's two evidence items.
        assertThat(finished.getMessageCount()).isEqualTo(3);
    }

    @Test
    void anUnknownScopeIsRejectedUpFrontRatherThanFailingLater() {
        assertThatThrownBy(() -> service.requestExport("case-1", "wishful-thinking", "someone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported export scope");
    }

    /** FR-2.5: a closed case is read-only, and that includes exports. */
    @Test
    void aClosedCaseCannotBeExported() {
        when(caseClient.isClosed("case-closed")).thenReturn(true);

        assertThatThrownBy(() -> service.requestExport("case-closed", "evidence", "investigator@smarsh.com"))
                .hasMessageContaining("read-only");
    }

    /** FR-6.6: a failed export is retryable, as a fresh job with a fresh key. */
    @Test
    void aFailedExportIsRetryableAsANewJobWithANewPackageKey() {
        ExportJob original = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        original.setStatus(ExportStatus.FAILED);
        original.setFailureReason("archive-service unavailable");

        ExportJob retried = service.retry(original.getId());
        service.run(retried.getId());

        assertThat(retried.getId()).isNotEqualTo(original.getId());
        assertThat(saved.get(retried.getId()).getStatus()).isEqualTo(ExportStatus.COMPLETED);
        // The failed job is untouched; the retry cannot corrupt or overwrite it.
        assertThat(saved.get(original.getId()).getStatus()).isEqualTo(ExportStatus.FAILED);
    }

    /**
     * FR-6.6 says a <em>failed</em> job is retryable. Retrying a completed one
     * would produce a second package of identical evidence under a different
     * name, which is exactly the ambiguity a chain of custody must not have.
     */
    @Test
    void aCompletedExportCannotBeRetried() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());

        assertThatThrownBy(() -> service.retry(job.getId()))
                .hasMessageContaining("only a FAILED export can be retried");
    }

    @Test
    void anIncompleteExportCannotBeDownloaded() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");

        assertThatThrownBy(() -> service.downloadUrl(job.getId()))
                .hasMessageContaining("only a COMPLETED export can be downloaded");
    }

    /** FR-6.4: the download link expires. */
    @Test
    void aCompletedExportYieldsAnExpiringDownloadLink() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());

        assertThat(service.downloadUrl(job.getId())).contains("expires=1800");
        assertThat(auditTrail.recorded("EXPORT_DOWNLOADED")).isTrue();
    }

    /** FR-6.5: a freshly produced package verifies against its own manifest. */
    @Test
    void aStoredPackageVerifiesAgainstItsManifest() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());

        VerificationResult result = verification.verify(saved.get(job.getId()));

        assertThat(result.verified()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.itemsChecked()).isEqualTo(4);
        assertThat(result.contentChecksumMatches()).isTrue();
        assertThat(result.packageChecksumMatches()).isTrue();
    }

    /** FR-6.5: "any tampering must be detectable". */
    @Test
    void verificationDetectsATamperedPackage() throws Exception {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());
        ExportJob finished = saved.get(job.getId());

        // Swap the stored package for one with an altered message.
        byte[] tampered = tamperedCopy(objectStore.get(finished.getPackageObjectKey()));
        objectStore.put(finished.getPackageObjectKey(), tampered, "application/zip");

        VerificationResult result = verification.verify(finished);

        assertThat(result.verified()).isFalse();
        assertThat(result.problems()).isNotEmpty();
        assertThat(result.packageChecksumMatches()).isFalse();
    }

    /** Rewrites one message inside the zip, leaving the manifest untouched. */
    private byte[] tamperedCopy(byte[] zipBytes) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
             var zout = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] content = zin.readAllBytes();
                if ("messages/msg-1.json".equals(entry.getName())) {
                    content = "{\"id\":\"msg-1\",\"body\":\"evidence quietly altered\"}"
                            .getBytes(StandardCharsets.UTF_8);
                }
                var copy = new java.util.zip.ZipEntry(entry.getName());
                copy.setTime(0L);
                zout.putNextEntry(copy);
                zout.write(content);
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** FR-7.1: the significant export actions all reach the audit trail. */
    @Test
    void theExportLifecycleIsAudited() {
        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());

        assertThat(auditTrail.actions()).contains("EXPORT_REQUESTED", "EXPORT_COMPLETED");
        assertThat(auditTrail.firstWithAction("EXPORT_REQUESTED").orElseThrow().actor())
                .isEqualTo("investigator@smarsh.com");
    }

    @Test
    void aFailureIsRecordedWithItsReasonAndAudited() {
        // doThrow, not when(...).thenThrow: re-stubbing with when() would call
        // the existing answer with a null argument first.
        org.mockito.Mockito.doThrow(new IllegalStateException("archive unreachable"))
                .when(archiveClient).getMessages(any());

        ExportJob job = service.requestExport("case-1", "evidence", "investigator@smarsh.com");
        service.run(job.getId());

        ExportJob failed = saved.get(job.getId());
        assertThat(failed.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("archive unreachable");
        assertThat(auditTrail.recorded("EXPORT_FAILED")).isTrue();
        assertThat(failed.getCompletedAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));
    }
}
