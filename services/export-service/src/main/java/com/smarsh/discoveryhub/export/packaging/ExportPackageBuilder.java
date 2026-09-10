package com.smarsh.discoveryhub.export.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles the evidence package: a zip containing every message, every
 * attachment, a {@code manifest.json} of checksums, and a
 * {@code provenance.json} describing the run (FR-6.3).
 *
 * <p><b>Why the checksums are split in two.</b> The package must be both
 * <em>reproducible</em> and <em>verifiable</em> (FR-6.5), and those pull in
 * opposite directions: anything that records when the export ran makes the
 * bytes differ between two exports of identical evidence. So:
 * <ul>
 *   <li>{@code manifest.json} holds only deterministic data — one SHA-256 per
 *       item plus a {@code contentChecksum} over all of them. Export the same
 *       evidence twice and these are byte-identical, which is what
 *       "reproducible" has to mean for evidence.</li>
 *   <li>{@code provenance.json} holds the per-run facts (job id, when, who,
 *       what scope). It is excluded from the content checksum.</li>
 * </ul>
 * Zip entry timestamps are pinned for the same reason — the zip's own metadata
 * would otherwise change on every run.
 *
 * <p>Tampering is detectable at both levels: altering a message changes its
 * per-item digest, and altering a digest in the manifest changes the
 * content checksum.
 */
@Component
public class ExportPackageBuilder {

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String PROVENANCE_ENTRY = "provenance.json";
    public static final String ALGORITHM = "SHA-256";

    /**
     * Fixed timestamp for every zip entry, so identical content produces
     * identical bytes. The value is arbitrary; only its constancy matters.
     */
    private static final long FIXED_ENTRY_TIME = 0L;

    private final ObjectMapper objectMapper;

    public ExportPackageBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param zipBytes        the finished package
     * @param contentChecksum deterministic digest over the packaged items
     * @param packageChecksum digest of these exact zip bytes
     * @param itemCount       number of packaged items (messages + attachments)
     */
    public record BuiltPackage(byte[] zipBytes, String contentChecksum, String packageChecksum, int itemCount) {
    }

    public BuiltPackage build(List<ExportItem> items, Provenance provenance) throws IOException {
        List<Map<String, Object>> manifestItems = new ArrayList<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (ExportItem item : items) {
                writeEntry(zip, item.entryName(), item.content());

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", item.kind().name().toLowerCase());
                entry.put("messageId", item.messageId());
                entry.put("file", item.entryName());
                entry.put("sizeBytes", item.content().length);
                entry.put("sha256", sha256(item.content()));
                manifestItems.add(entry);
            }

            String contentChecksum = contentChecksum(manifestItems);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("algorithm", ALGORITHM);
            manifest.put("itemCount", manifestItems.size());
            manifest.put("items", manifestItems);
            // The package-level checksum lives inside the package, so a
            // recipient holding only the zip can verify it without asking the
            // API for anything.
            manifest.put("contentChecksum", contentChecksum);
            writeEntry(zip, MANIFEST_ENTRY, toPrettyJson(manifest));

            writeEntry(zip, PROVENANCE_ENTRY, toPrettyJson(provenance.asMap()));

            zip.finish();
            byte[] zipBytes = baos.toByteArray();
            return new BuiltPackage(zipBytes, contentChecksum, sha256(zipBytes), manifestItems.size());
        }
    }

    /**
     * Digest over the manifest's item list, in a canonical
     * {@code <file>:<sha256>} form sorted by entry name. Sorting means the
     * order in which messages happened to be fetched cannot change the result.
     */
    @SuppressWarnings("unchecked")
    public static String contentChecksum(List<Map<String, Object>> manifestItems) {
        String canonical = manifestItems.stream()
                .map(item -> item.get("file") + ":" + item.get("sha256"))
                .sorted()
                .reduce("", (a, b) -> a + b + "\n");
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private byte[] toPrettyJson(Object value) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }
}
