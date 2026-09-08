package com.smarsh.discoveryhub.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Data generator entry point.
 *
 * <p>A Spring Boot app whose only job is to run {@link CorpusGenerator} once on
 * startup and exit. It calls the ingestion-service REST API repeatedly, exactly
 * like a real client, to build the 10,000+ message corpus (FR-1.2).
 *
 * <p>Run with:
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.url=http://localhost:8081 --count=10000"
 * </pre>
 */
@SpringBootApplication
public class DataGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataGeneratorApplication.class, args);
    }
}
