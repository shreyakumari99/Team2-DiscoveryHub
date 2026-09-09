package com.smarsh.discoveryhub.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Case Service entry point.
 *
 * <p>Owns the {@code case_db} Postgres database: cases, custodians and evidence
 * items. Implements the case lifecycle state machine (Draft→Active→Under
 * Review→Closed) and rejects invalid transitions (FR-2.2). Emits case events to
 * Kafka so hold-retention-service and export-service can react without direct
 * calls.
 */
@SpringBootApplication
public class CaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseServiceApplication.class, args);
    }
}
