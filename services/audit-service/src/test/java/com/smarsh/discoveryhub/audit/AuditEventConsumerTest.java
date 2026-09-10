package com.smarsh.discoveryhub.audit;

import com.smarsh.discoveryhub.audit.domain.AuditLogEntry;
import com.smarsh.discoveryhub.audit.domain.AuditLogRepository;
import com.smarsh.discoveryhub.audit.messaging.AuditEventConsumer;
import com.smarsh.discoveryhub.events.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.smarsh.discoveryhub.audit.domain.AuditLogSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit consumer persisting into the append-only log (FR-7.1, FR-7.2) and
 * the filtered read API (FR-7.4), against an in-memory H2 database. No broker
 * is needed — {@code onAuditEvent} is called directly.
 *
 * <p>{@code SQL_INIT_PLATFORM} is set to h2 so the PL/pgSQL append-only
 * triggers (which only Postgres understands) are skipped here. Those are
 * exercised against the real database when the stack runs.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:0",
        "spring.sql.init.platform=h2"
})
class AuditEventConsumerTest {

    @Autowired
    private AuditEventConsumer consumer;

    @Autowired
    private AuditLogRepository repository;

    private String consume(String action, String actor, String caseId) {
        String eventId = UUID.randomUUID().toString();
        consumer.onAuditEvent(new AuditEvent(
                eventId, Instant.now(), actor, "case-service",
                action, "CASE", "case-1", caseId, null, null));
        return eventId;
    }

    private AuditLogEntry find(String eventId) {
        return repository.findAll().stream()
                .filter(e -> eventId.equals(e.getEventId()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void consumerPersistsAuditEvent() {
        long before = repository.count();

        String eventId = consume("CASE_CREATED", "alice", "case-1");

        AuditLogEntry saved = find(eventId);
        assertThat(saved).isNotNull();
        assertThat(saved.getAction()).isEqualTo("CASE_CREATED");
        assertThat(saved.getService()).isEqualTo("case-service");
        assertThat(saved.getActor()).isEqualTo("alice");
        assertThat(saved.getTimestamp()).isNotNull();
        assertThat(repository.count()).isEqualTo(before + 1);
    }

    /** A redelivered Kafka event must not produce a second entry. */
    @Test
    void redeliveryOfTheSameEventDoesNotDuplicate() {
        String eventId = UUID.randomUUID().toString();
        AuditEvent event = new AuditEvent(eventId, Instant.now(), "alice", "case-service",
                "CASE_CREATED", "CASE", "case-dup", "case-dup", null, null);

        consumer.onAuditEvent(event);
        long after = repository.count();
        consumer.onAuditEvent(event);

        assertThat(repository.count()).isEqualTo(after);
    }

    /** FR-7.2: before/after details survive intact. */
    @Test
    void beforeAndAfterDetailsArePersisted() {
        String eventId = UUID.randomUUID().toString();
        consumer.onAuditEvent(new AuditEvent(eventId, Instant.now(), "alice", "case-service",
                "CASE_TRANSITIONED", "CASE", "case-2", "case-2",
                "{\"state\":\"ACTIVE\"}", "{\"state\":\"CLOSED\"}"));

        AuditLogEntry saved = find(eventId);
        assertThat(saved.getBeforeJson()).isEqualTo("{\"state\":\"ACTIVE\"}");
        assertThat(saved.getAfterJson()).isEqualTo("{\"state\":\"CLOSED\"}");
    }

    /** FR-7.4: filterable per case. */
    @Test
    void entriesAreFilterableByCase() {
        consume("HOLD_PLACED", "alice", "case-filter-a");
        consume("HOLD_PLACED", "alice", "case-filter-b");

        var page = repository.findAll(AuditLogSpecifications.matching("case-filter-a", null, null, null, null, null, null),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).allSatisfy(e ->
                assertThat(e.getCaseId()).isEqualTo("case-filter-a"));
    }

    /** FR-7.4: and filterable across the system, by action and by actor. */
    @Test
    void entriesAreFilterableByActionAndActorAcrossCases() {
        consume("EXPORT_REQUESTED", "bob@smarsh.com", "case-x");
        consume("SEARCH_EXECUTED", "carol@smarsh.com", "case-y");

        var byAction = repository.findAll(AuditLogSpecifications.matching(null, "EXPORT_REQUESTED", null, null, null, null, null),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp")));
        assertThat(byAction.getContent()).isNotEmpty();
        assertThat(byAction.getContent()).allSatisfy(e ->
                assertThat(e.getAction()).isEqualTo("EXPORT_REQUESTED"));

        var byActor = repository.findAll(AuditLogSpecifications.matching(null, null, "carol@smarsh.com", null, null, null, null),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp")));
        assertThat(byActor.getContent()).isNotEmpty();
        assertThat(byActor.getContent()).allSatisfy(e ->
                assertThat(e.getActor()).isEqualTo("carol@smarsh.com"));
    }

    /** The trail is the fastest-growing table in the system, so reads are paginated. */
    @Test
    void resultsArePaginated() {
        for (int i = 0; i < 5; i++) {
            consume("SEARCH_EXECUTED", "alice", "case-page");
        }

        var firstPage = repository.findAll(AuditLogSpecifications.matching("case-page", null, null, null, null, null, null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    void newestEntriesComeFirst() {
        consume("CASE_CREATED", "alice", "case-order");
        consume("CASE_TRANSITIONED", "alice", "case-order");

        var page = repository.findAll(AuditLogSpecifications.matching("case-order", null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(page.getContent().get(0).getTimestamp())
                .isAfterOrEqualTo(page.getContent().get(1).getTimestamp());
    }

    /**
     * FR-7.3: there is no write path in the API, and none in the service
     * either — the consumer is the only thing that ever inserts. This pins
     * that the read side cannot be used to mutate.
     */
    @Test
    void theRepositoryIsOnlyEverUsedToInsertAndRead() {
        String eventId = consume("CASE_CREATED", "alice", "case-immutable");
        AuditLogEntry saved = find(eventId);
        long countBefore = repository.count();

        // Re-consuming the same event id is rejected by the unique constraint
        // rather than overwriting the existing row.
        consumer.onAuditEvent(new AuditEvent(eventId, Instant.now(), "mallory", "case-service",
                "CASE_DELETED", "CASE", "case-immutable", "case-immutable", null, null));

        assertThat(repository.count()).isEqualTo(countBefore);
        assertThat(find(eventId).getActor()).isEqualTo(saved.getActor());
        assertThat(find(eventId).getAction()).isEqualTo("CASE_CREATED");
    }
}
