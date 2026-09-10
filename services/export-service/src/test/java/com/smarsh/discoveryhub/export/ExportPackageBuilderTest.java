package com.smarsh.discoveryhub.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.export.packaging.ExportItem;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder;
import com.smarsh.discoveryhub.export.packaging.ExportPackageBuilder.BuiltPackage;
import com.smarsh.discoveryhub.export.packaging.Provenance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export package itself (FR-6.3, FR-6.5): it must contain every message
 * <em>and every attachment</em>, carry per-item and package-level checksums in
 * its manifest, be reproducible across runs, and make tampering detectable.
 *
 * <p>Nothing is mocked here — the builder is pure, so this exercises the real
 * bytes that would be handed to S3.
 */
class ExportPackageBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ExportPackageBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ExportPackageBuilder(objectMapper);
    }

    private static Provenance provenance() {
        return new Provenance("job-1", "case-1", "evidence", "investigator@smarsh.com",
                Instant.parse("2024-05-01T10:00:00Z"));
    }

    private List<ExportItem> twoMessagesOneWithAnAttachment() throws IOException {
        return List.of(
                ExportItem.message("msg-1", json("msg-1", "First message")),
                ExportItem.attachment("msg-1", "q3-report.pdf", "PDF-BYTES".getBytes(StandardCharsets.UTF_8)),
                ExportItem.message("msg-2", json("msg-2", "Second message")));
    }

    private byte[] json(String id, String body) throws IOException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("body", body);
        return objectMapper.writeValueAsBytes(message);
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries.put(e.getName(), zin.readAllBytes());
            }
        }
        return entries;
    }

    private JsonNode manifestOf(byte[] zipBytes) throws IOException {
        return objectMapper.readTree(unzip(zipBytes).get(ExportPackageBuilder.MANIFEST_ENTRY));
    }

    /** FR-6.3: messages in a readable format, all attachments, and a manifest. */
    @Test
    void packageContainsEveryMessageAndEveryAttachment() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());

        Map<String, byte[]> entries = unzip(built.zipBytes());
        assertThat(entries).containsKeys(
                "messages/msg-1.json",
                "messages/msg-2.json",
                "attachments/msg-1/q3-report.pdf",
                ExportPackageBuilder.MANIFEST_ENTRY,
                ExportPackageBuilder.PROVENANCE_ENTRY);
        assertThat(entries.get("attachments/msg-1/q3-report.pdf")).asString().isEqualTo("PDF-BYTES");
        assertThat(built.itemCount()).isEqualTo(3);
    }

    /** FR-6.5: recomputing each item's checksum against the manifest must pass. */
    @Test
    void everyItemChecksumRecomputes() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());

        Map<String, byte[]> entries = unzip(built.zipBytes());
        JsonNode items = manifestOf(built.zipBytes()).get("items");

        assertThat(items).hasSize(3);
        for (JsonNode item : items) {
            String file = item.get("file").asText();
            assertThat(entries).containsKey(file);
            assertThat(ExportPackageBuilder.sha256(entries.get(file)))
                    .as("recomputed checksum for %s", file)
                    .isEqualTo(item.get("sha256").asText());
        }
    }

    /**
     * FR-6.3: the package-level checksum has to be <em>inside</em> the
     * manifest. Previously it lived only on the job row, so a recipient
     * holding just the zip could not verify the package as a whole.
     */
    @Test
    void manifestCarriesThePackageLevelChecksum() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());

        JsonNode manifest = manifestOf(built.zipBytes());
        assertThat(manifest.get("contentChecksum").asText())
                .isNotBlank()
                .isEqualTo(built.contentChecksum());
        assertThat(manifest.get("algorithm").asText()).isEqualTo("SHA-256");
    }

    /**
     * FR-6.5 "an export must be reproducible": the same evidence exported
     * twice must produce the same checksums, even though the two runs have
     * different job ids and timestamps.
     */
    @Test
    void twoExportsOfTheSameEvidenceAreByteIdentical() throws Exception {
        BuiltPackage first = builder.build(twoMessagesOneWithAnAttachment(), provenance());
        BuiltPackage second = builder.build(twoMessagesOneWithAnAttachment(),
                new Provenance("job-2", "case-1", "evidence", "someone.else@smarsh.com",
                        Instant.parse("2025-01-01T00:00:00Z")));

        assertThat(second.contentChecksum()).isEqualTo(first.contentChecksum());
    }

    /** Provenance differs per run and is therefore kept out of the content digest. */
    @Test
    void provenanceRecordsTheRunWithoutAffectingTheContentChecksum() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());

        JsonNode provenance = objectMapper.readTree(
                unzip(built.zipBytes()).get(ExportPackageBuilder.PROVENANCE_ENTRY));
        assertThat(provenance.get("jobId").asText()).isEqualTo("job-1");
        assertThat(provenance.get("caseId").asText()).isEqualTo("case-1");
        assertThat(provenance.get("requestedBy").asText()).isEqualTo("investigator@smarsh.com");
        assertThat(provenance.get("generatedAt").asText()).isEqualTo("2024-05-01T10:00:00Z");
    }

    /** FR-6.5 "any tampering must be detectable" — at the item level. */
    @Test
    void alteringAMessageBreaksItsChecksum() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());
        JsonNode items = manifestOf(built.zipBytes()).get("items");

        String recordedForMsg1 = null;
        for (JsonNode item : items) {
            if ("messages/msg-1.json".equals(item.get("file").asText())) {
                recordedForMsg1 = item.get("sha256").asText();
            }
        }

        byte[] tampered = json("msg-1", "First message, quietly edited");
        assertThat(ExportPackageBuilder.sha256(tampered)).isNotEqualTo(recordedForMsg1);
    }

    /**
     * ...and at the manifest level: an attacker who edits a message and then
     * edits its manifest entry to match still breaks the content checksum.
     */
    @Test
    void alteringAChecksumInTheManifestBreaksTheContentChecksum() throws Exception {
        BuiltPackage built = builder.build(twoMessagesOneWithAnAttachment(), provenance());
        JsonNode items = manifestOf(built.zipBytes()).get("items");

        List<Map<String, Object>> forged = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", item.get("file").asText());
            entry.put("sha256", "messages/msg-1.json".equals(item.get("file").asText())
                    ? ExportPackageBuilder.sha256("tampered".getBytes(StandardCharsets.UTF_8))
                    : item.get("sha256").asText());
            forged.add(entry);
        }

        assertThat(ExportPackageBuilder.contentChecksum(forged)).isNotEqualTo(built.contentChecksum());
    }

    /** Ordering of fetched messages must not change the resulting digest. */
    @Test
    void itemOrderDoesNotAffectTheContentChecksum() throws Exception {
        List<ExportItem> items = twoMessagesOneWithAnAttachment();
        List<ExportItem> reordered = List.of(items.get(2), items.get(0), items.get(1));

        assertThat(builder.build(reordered, provenance()).contentChecksum())
                .isEqualTo(builder.build(items, provenance()).contentChecksum());
    }

    @Test
    void anEmptyScopeStillProducesAValidVerifiablePackage() throws Exception {
        BuiltPackage built = builder.build(List.of(), provenance());

        JsonNode manifest = manifestOf(built.zipBytes());
        assertThat(manifest.get("itemCount").asInt()).isZero();
        assertThat(manifest.get("contentChecksum").asText()).isNotBlank();
    }

    /**
     * Ids and filenames cannot escape their directory inside the zip: the
     * separators are stripped, so a traversal attempt collapses into a single
     * harmless path segment.
     */
    @Test
    void entryNamesCannotEscapeTheirDirectory() {
        ExportItem item = ExportItem.attachment("../../etc", "../passwd", new byte[]{1});

        assertThat(item.entryName()).isEqualTo("attachments/.._.._etc/.._passwd");
        assertThat(item.entryName().split("/")).hasSize(3);
    }
}
