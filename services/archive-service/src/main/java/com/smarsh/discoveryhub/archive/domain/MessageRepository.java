package com.smarsh.discoveryhub.archive.domain;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MongoDB repository for {@link ArchivedMessage}. The {@code findBySourceMessageId}
 * lookup is the idempotency check (FR-1.6): if a message with the same source id
 * already exists, the consumer skips it instead of creating a duplicate.
 */
@Repository
public interface MessageRepository extends MongoRepository<ArchivedMessage, String> {

    Optional<ArchivedMessage> findBySourceMessageId(String sourceMessageId);
}
