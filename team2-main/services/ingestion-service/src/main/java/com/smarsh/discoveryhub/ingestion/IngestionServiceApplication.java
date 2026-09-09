package com.smarsh.discoveryhub.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ingestion Service entry point.
 *
 * <p>Deliberately stateless: it owns no database. It only accepts messages over
 * REST, validates them, and publishes a {@link com.smarsh.discoveryhub.events.MessageIngestedEvent}
 * to Kafka. The actual storage is done downstream by archive-service. This split
 * is what satisfies FR-1.4 ("the component that accepts must not be the component
 * that stores") and gives us the buffering that satisfies NFR-2 (ingestion keeps
 * working even when archive-service is down — Kafka holds the backlog).
 */
@SpringBootApplication
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
