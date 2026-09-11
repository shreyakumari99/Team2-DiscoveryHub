package com.smarsh.discoveryhub.common.storage;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link ObjectStore} for tests, so export packaging and
 * attachment handling can be verified end to end without S3 or credentials.
 *
 * <p>Shipped in main rather than test sources for the same reason as
 * {@link com.smarsh.discoveryhub.common.audit.RecordingAuditTrail}: each
 * service is an independent Maven project and cannot depend on another's
 * test-jar. It is never wired into a production context.
 */
public class InMemoryObjectStore implements ObjectStore {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final String bucket;

    public InMemoryObjectStore() {
        this("test-bucket");
    }

    public InMemoryObjectStore(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes.clone());
        return key;
    }

    @Override
    public byte[] get(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new ObjectNotFoundException(bucket, key);
        }
        return bytes.clone();
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public String presignedDownloadUrl(String key, Duration expiry) {
        return "https://" + bucket + ".test.local/" + key + "?expires=" + expiry.toSeconds();
    }

    @Override
    public String bucket() {
        return bucket;
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    public int size() {
        return objects.size();
    }
}
