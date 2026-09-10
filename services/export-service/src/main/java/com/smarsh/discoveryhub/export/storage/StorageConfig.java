package com.smarsh.discoveryhub.export.storage;

import com.smarsh.discoveryhub.common.storage.ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3ObjectStore;
import com.smarsh.discoveryhub.common.storage.S3Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Binds export-service to the exports bucket, and only that bucket. Attachment
 * bytes are fetched from archive-service over its API rather than by reaching
 * into the archive's bucket, so the archive stays the sole owner of message
 * content (NFR-1).
 */
@Configuration
public class StorageConfig {

    @Bean
    public ObjectStore exportObjectStore(S3Client s3Client, S3Presigner presigner, S3Properties properties) {
        return new S3ObjectStore(s3Client, presigner,
                properties.getExportsBucket(), properties.isAutoCreateBuckets());
    }
}
