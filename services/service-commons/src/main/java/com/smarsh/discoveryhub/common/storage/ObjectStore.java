package com.smarsh.discoveryhub.common.storage;

import java.time.Duration;

/**
 * Durable object storage for one bucket — message attachments in
 * archive-service, export packages in export-service.
 *
 * <p>An interface rather than a concrete S3 client so the services depend on
 * "somewhere to put bytes" and not on Amazon: the export package builder and
 * the attachment writer are then unit-testable with an in-memory
 * {@link InMemoryObjectStore}, with no network and no credentials, and a
 * future move to a different provider touches one class.
 */
public interface ObjectStore {

    /**
     * Store {@code bytes} under {@code key}, overwriting any existing object.
     *
     * @return the key, for convenience when the caller records it
     */
    String put(String key, byte[] bytes, String contentType);

    /** Read an object's bytes. */
    byte[] get(String key);

    /** Remove an object. Implementations should not fail if it is already gone. */
    void delete(String key);

    /**
     * A time-limited URL that lets a browser download the object directly
     * (FR-6.4 "downloadable from the UI via a link that expires").
     */
    String presignedDownloadUrl(String key, Duration expiry);

    /** The bucket this store writes to; useful for logging and diagnostics. */
    String bucket();
}
