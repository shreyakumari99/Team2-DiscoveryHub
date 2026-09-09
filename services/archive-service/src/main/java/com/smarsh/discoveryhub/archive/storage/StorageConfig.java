package com.smarsh.discoveryhub.archive.storage;

import com.smarsh.discoveryhub.common.storage.ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Binds archive-service to the attachments bucket, and only that bucket
 * (NFR-1: each service owns its own data).
 */
@Configuration
public class StorageConfig {

    @Bean
    public ObjectStore attachmentObjectStore(S3Client s3Client, S3Presigner presigner, S3Properties properties) {
        return new S3ObjectStore(s3Client, presigner,
                properties.getAttachmentsBucket(), properties.isAutoCreateBuckets());
    }
}
