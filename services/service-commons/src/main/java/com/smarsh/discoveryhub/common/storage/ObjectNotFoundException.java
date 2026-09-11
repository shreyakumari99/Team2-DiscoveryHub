package com.smarsh.discoveryhub.common.storage;

/**
 * The requested object does not exist. Distinct from
 * {@link ObjectStoreException} so callers can tell "this attachment is gone"
 * apart from "S3 is unreachable" — an export must fail loudly on the second
 * but can report the first per item.
 */
public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String bucket, String key) {
        super("Object not found: " + bucket + "/" + key);
    }
}
