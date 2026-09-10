package com.smarsh.discoveryhub.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Search Service entry point.
 *
 * <p>Consumes {@link com.smarsh.discoveryhub.events.MessageArchivedEvent} from
 * Kafka, indexes each message into Elasticsearch, and exposes a full-text
 * search API with filters, highlighting and pagination (FR-3). It also owns a
 * tiny embedded store (H2) for saved searches (FR-3.5).
 */
@SpringBootApplication
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
