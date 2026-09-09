package com.smarsh.discoveryhub.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Archive Service entry point.
 *
 * <p>The durable home of every message. It owns a MongoDB database for message
 * bodies and an S3 bucket for attachments, and it is the <strong>only</strong>
 * service permitted to delete a message — which is why the legal-hold deletion
 * block (FR-4.6) is enforced here rather than in the hold service.
 *
 * <p>It also owns the legal-hold ledger: which holds cover which messages, and
 * therefore whether a message is protected (FR-4.5). Keeping that decision in
 * the same service that performs deletions is what makes the guarantee
 * enforceable rather than advisory.
 */
@SpringBootApplication
public class ArchiveServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveServiceApplication.class, args);
    }
}
