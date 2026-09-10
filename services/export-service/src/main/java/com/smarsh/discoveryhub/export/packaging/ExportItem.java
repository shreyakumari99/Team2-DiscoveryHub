package com.smarsh.discoveryhub.export.packaging;

/**
 * One file destined for the export package: either a message rendered as JSON
 * or an attachment's raw bytes (FR-6.3).
 *
 * @param kind      what this file is, so the manifest distinguishes a message
 *                  from its attachments
 * @param messageId the message this file belongs to
 * @param entryName path inside the zip
 * @param content   the bytes to write
 */
public record ExportItem(Kind kind, String messageId, String entryName, byte[] content) {

    public enum Kind {
        MESSAGE, ATTACHMENT
    }

    public static ExportItem message(String messageId, byte[] json) {
        return new ExportItem(Kind.MESSAGE, messageId, "messages/" + sanitize(messageId) + ".json", json);
    }

    public static ExportItem attachment(String messageId, String fileName, byte[] bytes) {
        return new ExportItem(Kind.ATTACHMENT, messageId,
                "attachments/" + sanitize(messageId) + "/" + sanitize(fileName), bytes);
    }

    /**
     * Keeps ids and filenames from escaping their directory or producing an
     * entry name a zip reader would reject.
     */
    static String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
