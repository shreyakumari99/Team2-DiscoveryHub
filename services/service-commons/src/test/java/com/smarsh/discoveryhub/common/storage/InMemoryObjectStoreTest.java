package com.smarsh.discoveryhub.common.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory {@link ObjectStore} that lets export packaging and attachment
 * handling be tested without S3 or credentials.
 *
 * <p>It is shipped in main rather than test sources, so it is production code
 * by the compiler's reckoning and deserves tests of its own. More importantly,
 * every test that relies on it is only as trustworthy as its fidelity to
 * {@link S3ObjectStore} — if this one returned a live reference to its stored
 * array where S3 returns a copy, a test could mutate "stored" bytes and never
 * notice.
 */
class InMemoryObjectStoreTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void storesAndReturnsAnObject() {
        InMemoryObjectStore store = new InMemoryObjectStore();

        String key = store.put("exports/job-1/package.zip", bytes("zip-content"), "application/zip");

        assertThat(key).isEqualTo("exports/job-1/package.zip");
        assertThat(store.get("exports/job-1/package.zip")).isEqualTo(bytes("zip-content"));
        assertThat(store.contains("exports/job-1/package.zip")).isTrue();
        assertThat(store.size()).isEqualTo(1);
    }

    /** Writing the same key twice replaces it, as overwriting an S3 object does. */
    @Test
    void overwritesAnExistingKey() {
        InMemoryObjectStore store = new InMemoryObjectStore();

        store.put("k", bytes("first"), "text/plain");
        store.put("k", bytes("second"), "text/plain");

        assertThat(store.get("k")).isEqualTo(bytes("second"));
        assertThat(store.size()).isEqualTo(1);
    }

    /**
     * Both directions are defensive copies. Without this, a caller mutating
     * the array it handed in — or the one it got back — would silently change
     * the "stored" object, which real object storage would never do.
     */
    @Test
    void copiesBytesOnTheWayInAndOut() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        byte[] original = bytes("stable");

        store.put("k", original, "text/plain");
        original[0] = 'X';
        assertThat(store.get("k")).isEqualTo(bytes("stable"));

        byte[] fetched = store.get("k");
        fetched[0] = 'Y';
        assertThat(store.get("k")).isEqualTo(bytes("stable"));
    }

    /** A missing key is the same failure S3ObjectStore raises, not a null. */
    @Test
    void aMissingKeyRaisesObjectNotFound() {
        InMemoryObjectStore store = new InMemoryObjectStore("my-bucket");

        assertThatThrownBy(() -> store.get("nope"))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining("my-bucket")
                .hasMessageContaining("nope");
    }

    @Test
    void deletesAnObject() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        store.put("k", bytes("x"), "text/plain");

        store.delete("k");

        assertThat(store.contains("k")).isFalse();
        assertThat(store.size()).isZero();
    }

    /** Deleting something already gone is the desired end state, not an error. */
    @Test
    void deletingAnAbsentObjectIsHarmless() {
        InMemoryObjectStore store = new InMemoryObjectStore();

        store.delete("never-existed");

        assertThat(store.size()).isZero();
    }

    @Test
    void mintsADistinguishableDownloadUrlCarryingTheExpiry() {
        InMemoryObjectStore store = new InMemoryObjectStore("exports-bucket");

        String url = store.presignedDownloadUrl("exports/job-1/package.zip", Duration.ofMinutes(30));

        assertThat(url)
                .contains("exports-bucket")
                .contains("exports/job-1/package.zip")
                // The expiry is visible, so a test can assert the link is
                // time-limited rather than just present.
                .contains("expires=1800");
    }

    @Test
    void reportsItsBucketAndDefaultsToATestOne() {
        assertThat(new InMemoryObjectStore().bucket()).isEqualTo("test-bucket");
        assertThat(new InMemoryObjectStore("named").bucket()).isEqualTo("named");
    }

    @Test
    void tracksSeveralObjectsIndependently() {
        InMemoryObjectStore store = new InMemoryObjectStore();

        store.put("a", bytes("1"), "text/plain");
        store.put("b", bytes("2"), "text/plain");

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("a")).isEqualTo(bytes("1"));
        assertThat(store.get("b")).isEqualTo(bytes("2"));
    }
}
