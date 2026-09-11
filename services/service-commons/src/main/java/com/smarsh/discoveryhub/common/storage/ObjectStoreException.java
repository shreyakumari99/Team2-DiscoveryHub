package com.smarsh.discoveryhub.common.storage;

/** An object-storage operation failed for a reason other than a missing key. */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
