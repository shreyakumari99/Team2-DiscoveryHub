package com.smarsh.discoveryhub.archive.api;

/**
 * Thrown when an attempt is made to delete a message that is on legal hold.
 * Mapped to HTTP 409 Conflict by {@link GlobalExceptionHandler}. This is the
 * concrete enforcement point for FR-4.6 ("deletion of a held item is blocked").
 */
public class MessageHeldException extends RuntimeException {

    public MessageHeldException(String messageId) {
        super("Message " + messageId + " is on legal hold and cannot be deleted");
    }
}
