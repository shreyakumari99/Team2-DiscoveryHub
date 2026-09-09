package com.smarsh.discoveryhub.archive.api;

/**
 * Thrown when a requested message is not in the archive. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Exists as its own type so that "not found" is distinguishable from a
 * genuinely malformed request: previously every {@link IllegalArgumentException}
 * became a 404, which would have reported a programming error as a missing
 * message and made the disposition job count it as already-deleted.
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String messageId) {
        super("Message not found: " + messageId);
    }
}
