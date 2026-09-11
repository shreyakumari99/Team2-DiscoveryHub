package com.smarsh.discoveryhub.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * {@link ObjectStore} backed by Amazon S3 (AWS SDK v2).
 *
 * <p>Credentials come from the SDK's default provider chain — environment
 * variables, the shared credentials file, or the instance/task role when
 * deployed — so no secret is ever read from application config or committed.
 */
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean autoCreate;

    public S3ObjectStore(S3Client s3, S3Presigner presigner, String bucket) {
        this(s3, presigner, bucket, false);
    }

    /**
     * @param autoCreate create the bucket on startup if it is missing. Leave
     *                   off against real AWS, where infrastructure provisions
     *                   the bucket and the application's IAM user has no
     *                   {@code s3:CreateBucket} permission — asking would log a
     *                   misleading access-denied error on every startup.
     */
    public S3ObjectStore(S3Client s3, S3Presigner presigner, String bucket, boolean autoCreate) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.autoCreate = autoCreate;
    }

    /**
     * Verify the bucket exists, creating it only when {@code autoCreate} is on.
     *
     * <p>Deliberately non-fatal: a service must still start when S3 is briefly
     * unreachable, so a failure here is logged rather than thrown.
     */
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            if (!autoCreate) {
                log.warn("S3 bucket '{}' does not exist and auto-creation is off", bucket);
                return;
            }
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created S3 bucket '{}'", bucket);
            } catch (S3Exception create) {
                log.warn("Could not create S3 bucket '{}': {}", bucket, create.getMessage());
            }
        } catch (Exception e) {
            log.warn("Could not verify S3 bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType != null ? contentType : DEFAULT_CONTENT_TYPE)
                            .build(),
                    RequestBody.fromBytes(bytes));
            return key;
        } catch (Exception e) {
            throw new ObjectStoreException("Failed to store object " + key + " in " + bucket, e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(bucket, key);
        } catch (Exception e) {
            throw new ObjectStoreException("Failed to read object " + key + " from " + bucket, e);
        }
    }

    /** Best-effort: a missing object is already in the desired state. */
    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("Best-effort removal of {} from {} failed: {}", key, bucket, e.getMessage());
        }
    }

    @Override
    public String presignedDownloadUrl(String key, Duration expiry) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(expiry)
                            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                            .build())
                    .url()
                    .toString();
        } catch (Exception e) {
            throw new ObjectStoreException("Failed to presign " + key + " in " + bucket, e);
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }
}
