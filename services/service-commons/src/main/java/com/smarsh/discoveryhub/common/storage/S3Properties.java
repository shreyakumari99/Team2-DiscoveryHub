package com.smarsh.discoveryhub.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 settings, bound from {@code aws.s3.*}.
 *
 * <p>Note what is <em>not</em> here: access key and secret. Those are resolved
 * by the AWS SDK's default credentials provider chain (environment variables,
 * the shared credentials file, or an instance/task role), so no secret has to
 * appear in application config, in an image, or in the repository.
 */
@ConfigurationProperties(prefix = "aws.s3")
public class S3Properties {

    /** AWS region, e.g. {@code eu-west-1}. */
    private String region = "us-east-1";

    /**
     * Override the S3 endpoint. Left empty for real AWS. Set it to point the
     * same code at any S3-compatible endpoint (LocalStack, MinIO, a VPC
     * endpoint) without changing a line of Java.
     */
    private String endpoint;

    /**
     * Use path-style addressing ({@code host/bucket/key}) instead of
     * virtual-host style. Required by most S3-compatible servers; harmless but
     * unnecessary against real AWS.
     */
    private boolean pathStyleAccess;

    /** Bucket holding message attachments (archive-service). */
    private String attachmentsBucket = "discoveryhub-attachments";

    /** Bucket holding completed export packages (export-service). */
    private String exportsBucket = "discoveryhub-exports";

    /** Create buckets on startup if absent. Turn off in AWS where infra owns them. */
    private boolean autoCreateBuckets = true;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getAttachmentsBucket() {
        return attachmentsBucket;
    }

    public void setAttachmentsBucket(String attachmentsBucket) {
        this.attachmentsBucket = attachmentsBucket;
    }

    public String getExportsBucket() {
        return exportsBucket;
    }

    public void setExportsBucket(String exportsBucket) {
        this.exportsBucket = exportsBucket;
    }

    public boolean isAutoCreateBuckets() {
        return autoCreateBuckets;
    }

    public void setAutoCreateBuckets(boolean autoCreateBuckets) {
        this.autoCreateBuckets = autoCreateBuckets;
    }

    public boolean hasEndpointOverride() {
        return endpoint != null && !endpoint.isBlank();
    }
}
