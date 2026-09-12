package com.smarsh.discoveryhub.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The S3-backed {@link ObjectStore}, with the AWS client mocked so no network
 * or credentials are involved.
 *
 * <p>What is worth testing here is not "does it call the SDK" but the decisions
 * it makes around the SDK: which failures are translated into which of our own
 * exceptions, which failures are deliberately swallowed, and whether it will
 * create a bucket it finds missing. Those choices are what the rest of the
 * platform depends on — archive-service treats a missing attachment as
 * absent-but-fine, and a service must still start when S3 is briefly
 * unreachable.
 */
class S3ObjectStoreTest {

    private static final String BUCKET = "discoveryhub-attachments";

    private S3Client s3;
    private S3Presigner presigner;
    private S3ObjectStore store;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        store = new S3ObjectStore(s3, presigner, BUCKET);
    }

    // ---- writing ---------------------------------------------------------

    @Test
    void putsAnObjectWithItsBucketKeyAndContentType() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = store.put("attachments/m1/report.pdf",
                "PDFDATA".getBytes(StandardCharsets.UTF_8), "application/pdf");

        assertThat(key).isEqualTo("attachments/m1/report.pdf");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("attachments/m1/report.pdf");
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
    }

    /** A null content type must not reach S3 as null. */
    @Test
    void fallsBackToABinaryContentTypeWhenNoneIsGiven() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        store.put("k", "x".getBytes(StandardCharsets.UTF_8), null);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    /** A failed write is fatal — the caller must not believe bytes were stored. */
    @Test
    void aFailedPutRaisesObjectStoreException() {
        doThrow(S3Exception.builder().message("access denied").build())
                .when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        assertThatThrownBy(() -> store.put("k", new byte[]{1}, "text/plain"))
                .isInstanceOf(ObjectStoreException.class)
                .hasMessageContaining("k")
                .hasMessageContaining(BUCKET);
    }

    // ---- reading ---------------------------------------------------------

    @Test
    void readsAnObjectsBytes() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                        "PDFDATA".getBytes(StandardCharsets.UTF_8)));

        assertThat(store.get("attachments/m1/report.pdf"))
                .isEqualTo("PDFDATA".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("attachments/m1/report.pdf");
    }

    /**
     * A missing key is translated into our own exception, distinct from a
     * general failure. Archive-service relies on the distinction: an attachment
     * that has since been disposed of is absent-but-fine, whereas S3 being
     * unreachable is not.
     */
    @Test
    void aMissingKeyRaisesObjectNotFound() {
        doThrow(NoSuchKeyException.builder().build())
                .when(s3).getObjectAsBytes(any(GetObjectRequest.class));

        assertThatThrownBy(() -> store.get("gone"))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void anyOtherReadFailureRaisesObjectStoreException() {
        doThrow(S3Exception.builder().message("timeout").build())
                .when(s3).getObjectAsBytes(any(GetObjectRequest.class));

        assertThatThrownBy(() -> store.get("k"))
                .isInstanceOf(ObjectStoreException.class)
                .isNotInstanceOf(ObjectNotFoundException.class);
    }

    // ---- deleting --------------------------------------------------------

    @Test
    void deletesAnObject() {
        store.delete("attachments/m1/report.pdf");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("attachments/m1/report.pdf");
    }

    /**
     * Deletion is best-effort on purpose. Attachment cleanup runs after a
     * message has already been removed from the archive, so a failure here must
     * never propagate — the deletion the retention rules authorised has
     * happened, and a stuck cleanup must not undo or block it.
     */
    @Test
    void aFailedDeleteIsSwallowed() {
        doThrow(S3Exception.builder().message("access denied").build())
                .when(s3).deleteObject(any(DeleteObjectRequest.class));

        assertThatCode(() -> store.delete("k")).doesNotThrowAnyException();
    }

    // ---- presigned links -------------------------------------------------

    @Test
    void mintsAPresignedUrlCarryingTheRequestedExpiry() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(
                "https://" + BUCKET + ".s3.amazonaws.com/exports/job-1/package.zip?X-Amz-Expires=1800").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = store.presignedDownloadUrl("exports/job-1/package.zip", Duration.ofMinutes(30));

        assertThat(url).contains("exports/job-1/package.zip");
        ArgumentCaptor<GetObjectPresignRequest> request =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aFailedPresignRaisesObjectStoreException() {
        doThrow(S3Exception.builder().message("no credentials").build())
                .when(presigner).presignGetObject(any(GetObjectPresignRequest.class));

        assertThatThrownBy(() -> store.presignedDownloadUrl("k", Duration.ofMinutes(1)))
                .isInstanceOf(ObjectStoreException.class);
    }

    @Test
    void reportsItsBucket() {
        assertThat(store.bucket()).isEqualTo(BUCKET);
    }

    // ---- bucket verification on startup ---------------------------------

    @Test
    void anExistingBucketIsNotCreated() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        store.ensureBucket();

        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    /**
     * Against real AWS the bucket is provisioned by infrastructure and the
     * application's IAM user has no {@code s3:CreateBucket}, so asking would
     * log a misleading access-denied error on every startup.
     */
    @Test
    void aMissingBucketIsNotCreatedWhenAutoCreationIsOff() {
        doThrow(NoSuchBucketException.builder().build())
                .when(s3).headBucket(any(HeadBucketRequest.class));

        store.ensureBucket();

        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void aMissingBucketIsCreatedWhenAutoCreationIsOn() {
        S3ObjectStore autoCreating = new S3ObjectStore(s3, presigner, BUCKET, true);
        doThrow(NoSuchBucketException.builder().build())
                .when(s3).headBucket(any(HeadBucketRequest.class));

        autoCreating.ensureBucket();

        ArgumentCaptor<CreateBucketRequest> request = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(s3).createBucket(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    }

    /** A failed creation is logged, not thrown — the service must still start. */
    @Test
    void aFailedBucketCreationDoesNotPreventStartup() {
        S3ObjectStore autoCreating = new S3ObjectStore(s3, presigner, BUCKET, true);
        doThrow(NoSuchBucketException.builder().build())
                .when(s3).headBucket(any(HeadBucketRequest.class));
        doThrow(S3Exception.builder().message("access denied").build())
                .when(s3).createBucket(any(CreateBucketRequest.class));

        assertThatCode(autoCreating::ensureBucket).doesNotThrowAnyException();
    }

    /**
     * S3 being briefly unreachable at startup must not stop a service booting.
     * Refusing to start would turn a transient storage blip into an outage of
     * ingestion or export.
     */
    @Test
    void anUnreachableS3DoesNotPreventStartup() {
        doThrow(new RuntimeException("connection reset"))
                .when(s3).headBucket(any(HeadBucketRequest.class));

        assertThatCode(store::ensureBucket).doesNotThrowAnyException();
    }
}
