package com.smarsh.discoveryhub.search.api;

/** Thrown when a saved search id does not exist. Mapped to HTTP 404. */
public class SavedSearchNotFoundException extends RuntimeException {

    public SavedSearchNotFoundException(String id) {
        super("Saved search not found: " + id);
    }
}
