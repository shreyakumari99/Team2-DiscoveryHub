package com.smarsh.discoveryhub.holdretention;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Hold &amp; Retention Service entry point.
 *
 * <p>Owns the {@code hold_db} Postgres database: holds and retention policies.
 * Placing a hold resolves its scope asynchronously by calling search-service,
 * then publishes a {@code hold-events} Kafka event so archive-service can flip
 * the per-message held flag (FR-4.3). The scheduled disposition job finds
 * messages past retention and asks archive-service to delete them — archive
 * refuses any that are held, which is the FR-4.6 proof (FR-5).
 *
 * <p>{@code @EnableScheduling} turns on the cron-style disposition job.
 */
@SpringBootApplication
@EnableScheduling
public class HoldRetentionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoldRetentionServiceApplication.class, args);
    }
}
