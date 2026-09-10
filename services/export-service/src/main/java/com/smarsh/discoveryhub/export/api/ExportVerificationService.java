package com.smarsh.discoveryhub.export.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.export.domain.ExportJob;
import com.smarsh.discoveryhub.export.domain.ExportStatus;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder;
import com.smarsh.discoveryhub.export.storage.ExportStorageService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Re-verifies a stored export package against its own manifest (FR-6.5:
 * "re-computing checksums against the manifest must pass; any tampering must
 * be detectable").
 *
 * <p>Verification is done by downloading the package and recomputing
 * everything from its bytes — it does not trust the job row. Three independent
 * checks have to agree:
 * <ol>
 *   <li>every file's SHA-256 matches its manifest entry (a modified message is
 *       caught);</li>
 *   <li>the manifest's own {@code contentChecksum} matches a digest recomputed
 *       over the entries (editing a checksum in the manifest to match a
 *       modified file is caught);</li>
 *   <li>the whole-zip digest matches the one recorded when the package was
 *       produced (replacing the package wholesale is caught).</li>
 * </ol>
 */
@Service
public class ExportVerificationService {

    private final ExportStorageService storage;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;

    public ExportVerificationService(ExportStorageService storage,
                                     ObjectMapper objectMapper,
                                     AuditTrail auditTrail) {
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.auditTrail = auditTrail;
    }

    /**
     * @param verified          true only if every check passed
     * @param itemsChecked      number of files verified against the manifest
     * @param problems          human-readable description of each mismatch
     */
    public record VerificationResult(boolean verified,
                                     int itemsChecked,
                                     boolean contentChecksumMatches,
                                     boolean packageChecksumMatches,
                                     List<String> problems) {
    }

    public VerificationResult verify(ExportJob job) {
        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new ExportNotDownloadableException(job.getId(), job.getStatus());
        }

        byte[] zipBytes = storage.getPackage(job.getPackageObjectKey());
        List<String> problems = new ArrayList<>();

        Map<String, byte[]> entries = readEntries(zipBytes);
        byte[] manifestBytes = entries.get(ExportPackageBuilder.MANIFEST_ENTRY);
        if (manifestBytes == null) {
            return new VerificationResult(false, 0, false, false,
                    List.of("Package has no " + ExportPackageBuilder.MANIFEST_ENTRY));
        }

        JsonNode manifest = readManifest(manifestBytes);
        JsonNode items = manifest.path("items");
        List<Map<String, Object>> recomputed = new ArrayList<>();

        for (JsonNode item : items) {
            String file = item.path("file").asText();
            String expected = item.path("sha256").asText();
            byte[] content = entries.get(file);

            if (content == null) {
                problems.add("Missing from package: " + file);
                continue;
            }
            String actual = ExportPackageBuilder.sha256(content);
            if (!actual.equals(expected)) {
                problems.add("Checksum mismatch for " + file + ": manifest=" + expected + " actual=" + actual);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", file);
            entry.put("sha256", actual);
            recomputed.add(entry);
        }

        // Catches a manifest edited to match tampered content.
        String recomputedContent = ExportPackageBuilder.contentChecksum(recomputed);
        boolean contentMatches = recomputedContent.equals(manifest.path("contentChecksum").asText());
        if (!contentMatches) {
            problems.add("Manifest contentChecksum does not match the packaged items");
        }

        // Catches the package being swapped for a different one.
        boolean packageMatches = ExportPackageBuilder.sha256(zipBytes).equals(job.getPackageChecksum());
        if (!packageMatches) {
            problems.add("Package checksum does not match the value recorded when the export was produced");
        }

        boolean verified = problems.isEmpty();
        auditTrail.record(AuditRecord.action("EXPORT_VERIFIED")
                .on("EXPORT", job.getId())
                .inCase(job.getCaseId())
                .after("verified", verified)
                .after("itemsChecked", items.size())
                .after("problems", problems.size()));

        return new VerificationResult(verified, items.size(), contentMatches, packageMatches, problems);
    }

    private JsonNode readManifest(byte[] manifestBytes) {
        try {
            return objectMapper.readTree(manifestBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Export manifest is not readable JSON", e);
        }
    }

    private static Map<String, byte[]> readEntries(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Export package could not be read", e);
        }
        return entries;
    }
}
