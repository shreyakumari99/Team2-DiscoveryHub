package com.smarsh.discoveryhub.common.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the shared S3 client and presigner from {@code aws.s3.*}.
 *
 * <p>Only the client and presigner are defined here. Each service creates its
 * own {@link ObjectStore} bound to the bucket it owns
 * (archive → attachments, export → packages), so neither service is handed a
 * handle to the other's data.
 */
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties(S3Properties.class)
public class S3StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client(S3Properties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                // The URL-connection client keeps the dependency surface small;
                // the SDK's default Apache client is heavier than we need for
                // straightforward put/get traffic.
                .httpClient(UrlConnectionHttpClient.builder().build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        if (properties.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public S3Presigner s3Presigner(S3Properties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        if (properties.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }
}
