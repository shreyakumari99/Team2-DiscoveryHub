package com.smarsh.discoveryhub.archive.storage;

import com.smarsh.discoveryhub.common.storage.ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3ObjectStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Stores and retrieves message attachment bytes in S3 (FR-1.5).
 *
 * <p>Only the object key is kept on the Mongo document, so message bodies stay
 * small and attachments of any size are handled by storage built for it.
 * Key layout: {@code attachments/<messageId>/<attachmentName>}, which groups an
 * entire message's files under one prefix so deleting a message can clean up
 * by prefix and so a human can find them in the console.
 *
 * <p>This class is the archive's boundary onto object storage: it is the only
 * thing that knows the key layout, and it is bound to the attachments bucket
 * alone, so no other component can reach into export packages.
 */
@Service
public class AttachmentStore {

    private final ObjectStore objectStore;

    public AttachmentStore(ObjectStore objectStore) {
        this.objectStore = objectStore;
    }

    /** Verifies the bucket on startup; the store decides whether to create it. */
    @PostConstruct
    void ensureBucket() {
        if (objectStore instanceof S3ObjectStore s3) {
            s3.ensureBucket();
        }
    }

    /** Stores a base64-encoded attachment and returns its object key. */
    public String putAttachment(String messageId, String name, String contentType, String contentBase64) {
        String key = objectKey(messageId, name);
        byte[] bytes = Base64.getDecoder().decode(contentBase64);
        return objectStore.put(key, bytes, contentType);
    }

    public byte[] getAttachment(String objectKey) {
        return objectStore.get(objectKey);
    }

    /**
     * Best-effort removal. A stuck cleanup must never block a delete that the
     * retention rules have already authorised.
     */
    public void removeAttachment(String objectKey) {
        objectStore.delete(objectKey);
    }

    public String bucket() {
        return objectStore.bucket();
    }

    static String objectKey(String messageId, String name) {
        return "attachments/" + messageId + "/" + name;
    }
}
