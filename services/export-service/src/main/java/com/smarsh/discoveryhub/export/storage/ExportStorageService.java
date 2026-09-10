package com.smarsh.discoveryhub.export.storage;

import com.smarsh.discoveryhub.common.storage.ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3ObjectStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Stores completed export packages in S3 and mints expiring download links
 * (FR-6.4).
 *
 * <p>The key includes the job id ({@code exports/<jobId>/package.zip}), which
 * is what makes a retry safe (FR-6.6): a retried export is a new job with a
 * new id, so it writes to a new key and can never overwrite or be confused
 * with the partial output of the run that failed.
 */
@Service
public class ExportStorageService {

    private static final String CONTENT_TYPE = "application/zip";

    private final ObjectStore objectStore;
    private final Duration linkExpiry;

    public ExportStorageService(ObjectStore objectStore,
                                @Value("${export.link-expiry-minutes:30}") int linkExpiryMinutes) {
        this.objectStore = objectStore;
        this.linkExpiry = Duration.ofMinutes(linkExpiryMinutes);
    }

    /** Verifies the bucket on startup; the store decides whether to create it. */
    @PostConstruct
    void ensureBucket() {
        if (objectStore instanceof S3ObjectStore s3) {
            s3.ensureBucket();
        }
    }

    /** Store a finished package zip; returns the object key to record on the job. */
    public String putPackage(String jobId, byte[] zipBytes) {
        return objectStore.put(objectKey(jobId), zipBytes, CONTENT_TYPE);
    }

    /** Read a stored package back, e.g. to re-verify its checksums (FR-6.5). */
    public byte[] getPackage(String objectKey) {
        return objectStore.get(objectKey);
    }

    /** An expiring pre-signed GET URL (FR-6.4). */
    public String presignedDownloadUrl(String objectKey) {
        return objectStore.presignedDownloadUrl(objectKey, linkExpiry);
    }

    public Duration linkExpiry() {
        return linkExpiry;
    }

    static String objectKey(String jobId) {
        return "exports/" + jobId + "/package.zip";
    }
}
