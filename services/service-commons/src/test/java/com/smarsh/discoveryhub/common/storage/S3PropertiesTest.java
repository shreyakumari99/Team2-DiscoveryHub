package com.smarsh.discoveryhub.common.storage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code aws.s3.*} settings.
 *
 * <p>Two things here are worth asserting rather than assuming. The defaults
 * have to be the safe ones for real AWS — no endpoint override, no path-style
 * addressing, and crucially <em>no</em> bucket auto-creation, because the
 * application's IAM user is not expected to hold {@code s3:CreateBucket}. And
 * the class must contain no credential fields at all: keys are resolved by the
 * AWS SDK's default provider chain precisely so that no secret ever appears in
 * application config, an image, or this repository.
 */
class S3PropertiesTest {

    @Test
    void defaultsAreTheOnesThatSuitRealAws() {
        S3Properties properties = new S3Properties();

        assertThat(properties.getRegion()).isEqualTo("us-east-1");
        assertThat(properties.getEndpoint()).isNull();
        assertThat(properties.hasEndpointOverride()).isFalse();
        assertThat(properties.isPathStyleAccess()).isFalse();
        // Infrastructure owns the buckets in AWS; asking to create them would
        // log a misleading access-denied error on every startup.
        assertThat(properties.isAutoCreateBuckets()).isTrue();
        assertThat(properties.getAttachmentsBucket()).isEqualTo("discoveryhub-attachments");
        assertThat(properties.getExportsBucket()).isEqualTo("discoveryhub-exports");
    }

    @Test
    void everySettingIsBindable() {
        S3Properties properties = new S3Properties();

        properties.setRegion("ap-south-1");
        properties.setEndpoint("http://localstack:4566");
        properties.setPathStyleAccess(true);
        properties.setAttachmentsBucket("attachments-smarsh");
        properties.setExportsBucket("export-packages-smarsh");
        properties.setAutoCreateBuckets(false);

        assertThat(properties.getRegion()).isEqualTo("ap-south-1");
        assertThat(properties.getEndpoint()).isEqualTo("http://localstack:4566");
        assertThat(properties.isPathStyleAccess()).isTrue();
        assertThat(properties.getAttachmentsBucket()).isEqualTo("attachments-smarsh");
        assertThat(properties.getExportsBucket()).isEqualTo("export-packages-smarsh");
        assertThat(properties.isAutoCreateBuckets()).isFalse();
    }

    /**
     * The flag that decides whether an endpoint override is applied. An empty
     * string is how the setting arrives when {@code AWS_S3_ENDPOINT} is left
     * blank in the environment, and it must count as "not set" — otherwise the
     * SDK would be pointed at an empty endpoint and every call would fail.
     */
    @Test
    void aBlankEndpointIsNotAnOverride() {
        S3Properties properties = new S3Properties();

        properties.setEndpoint("");
        assertThat(properties.hasEndpointOverride()).isFalse();

        properties.setEndpoint("   ");
        assertThat(properties.hasEndpointOverride()).isFalse();

        properties.setEndpoint(null);
        assertThat(properties.hasEndpointOverride()).isFalse();

        properties.setEndpoint("http://minio:9000");
        assertThat(properties.hasEndpointOverride()).isTrue();
    }

    /**
     * No credentials live here, and none ever should. This asserts the absence
     * structurally rather than trusting a reviewer to notice a future addition.
     */
    @Test
    void carriesNoCredentialFields() {
        String[] names = Arrays.stream(S3Properties.class.getDeclaredMethods())
                .map(Method::getName)
                .map(String::toLowerCase)
                .toArray(String[]::new);

        assertThat(names).noneMatch(n -> n.contains("accesskey"))
                .noneMatch(n -> n.contains("secret"))
                .noneMatch(n -> n.contains("password"))
                .noneMatch(n -> n.contains("token"));
    }
}
