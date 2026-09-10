package com.smarsh.discoveryhub.export.packaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chain-of-custody facts about one export run, written to
 * {@code provenance.json} inside the package.
 *
 * <p>Kept out of the content checksum deliberately: these values change on
 * every run, and folding them into the digest would make two exports of
 * identical evidence produce different checksums (FR-6.5 "reproducible").
 */
public record Provenance(String jobId,
                         String caseId,
                         String scope,
                         String requestedBy,
                         Instant generatedAt) {

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("caseId", caseId);
        map.put("scope", scope);
        map.put("requestedBy", requestedBy);
        map.put("generatedAt", generatedAt.toString());
        map.put("producer", "DiscoveryHub export-service");
        return map;
    }
}
