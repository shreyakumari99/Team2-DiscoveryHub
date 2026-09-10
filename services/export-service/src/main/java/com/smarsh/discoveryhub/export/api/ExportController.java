package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.export.api.ExportVerificationService.VerificationResult;
import com.smarsh.discoveryhub.export.domain.ExportJob;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Export REST API (FR-6).
 *
 * <pre>
 *   POST   /api/v1/exports                request an export -&gt; 202 with a QUEUED job (FR-6.2)
 *   GET    /api/v1/exports?caseId=        list jobs, newest first
 *   GET    /api/v1/exports/{id}           job status (QUEUED/RUNNING/COMPLETED/FAILED)
 *   GET    /api/v1/exports/{id}/download  expiring download URL (FR-6.4)
 *   GET    /api/v1/exports/{id}/verify    re-verify checksums against the manifest (FR-6.5)
 *   POST   /api/v1/exports/{id}/retry     retry a FAILED job (FR-6.6)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;
    private final ExportVerificationService verificationService;

    public ExportController(ExportService exportService, ExportVerificationService verificationService) {
        this.exportService = exportService;
        this.verificationService = verificationService;
    }

    /**
     * @param scope {@code evidence} (default) or {@code hold:<holdId>} for a
     *              hold's full scope (FR-6.1)
     */
    public record CreateExportRequest(@NotBlank String caseId, String scope, String requestedBy) {
    }

    @PostMapping
    public ResponseEntity<ExportJob> requestExport(@Valid @RequestBody CreateExportRequest request) {
        return ResponseEntity.accepted().body(
                exportService.requestExport(request.caseId(), request.scope(), request.requestedBy()));
    }

    @GetMapping
    public List<ExportJob> listJobs(@RequestParam(required = false) String caseId) {
        return exportService.listJobs(caseId);
    }

    @GetMapping("/{id}")
    public ExportJob getJob(@PathVariable String id) {
        return exportService.getJob(id);
    }

    @GetMapping("/{id}/download")
    public Map<String, String> download(@PathVariable String id) {
        return Map.of("url", exportService.downloadUrl(id));
    }

    /**
     * Re-verify a stored package (FR-6.5). Answers 200 with the findings
     * either way — a failed verification is a legitimate, reportable answer,
     * not an error — so the UI can show exactly which item does not match.
     */
    @GetMapping("/{id}/verify")
    public VerificationResult verify(@PathVariable String id) {
        return verificationService.verify(exportService.getJob(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ExportJob> retry(@PathVariable String id) {
        return ResponseEntity.accepted().body(exportService.retry(id));
    }
}
