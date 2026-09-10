package com.smarsh.discoveryhub.export;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Export Service entry point.
 *
 * <p>Owns the {@code export_db} Postgres database (job records) and the export
 * package bucket in S3. An export runs as an async background job: the caller
 * gets a job id immediately and watches progress Queued→Running→Completed/
 * Failed (FR-6.2). The package contains every message, every attachment, and a
 * manifest with per-item SHA-256 checksums plus a package-level checksum
 * (FR-6.3), downloadable via an expiring link (FR-6.4) and re-verifiable on
 * demand (FR-6.5).
 *
 * <p>Scheduling is enabled for {@link com.smarsh.discoveryhub.export.job.StuckExportReaper},
 * which fails exports left RUNNING by a restart so they become retryable again
 * (FR-6.6). Without it such a job would spin in the UI forever and could never
 * be retried, because retry only accepts a FAILED job.
 */
@SpringBootApplication
@EnableScheduling
public class ExportServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportServiceApplication.class, args);
    }
}
