package com.smarsh.discoveryhub.cases;

import com.smarsh.discoveryhub.cases.api.AddEvidenceBulkRequest;
import com.smarsh.discoveryhub.cases.api.AddEvidenceRequest;
import com.smarsh.discoveryhub.cases.api.CaseClosedException;
import com.smarsh.discoveryhub.cases.api.CaseEventPublisher;
import com.smarsh.discoveryhub.cases.api.CaseService;
import com.smarsh.discoveryhub.cases.api.CreateCaseRequest;
import com.smarsh.discoveryhub.cases.api.TransitionRequest;
import com.smarsh.discoveryhub.cases.domain.CaseEntity;
import com.smarsh.discoveryhub.cases.domain.EvidenceItem;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.cases.domain.CaseRepository;
import com.smarsh.discoveryhub.cases.domain.CustodianRepository;
import com.smarsh.discoveryhub.cases.domain.EvidenceRepository;
import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.MatterType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Service-level test against an in-memory H2 database (test profile) so no
 * Postgres is required. Verifies the full lifecycle and the read-only rule for
 * closed cases (FR-2.5). Kafka publishers are mocked so no broker is needed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:case-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Match production: open-in-view disabled so lazy collections must be
        // initialized within the @Transactional service method (see
        // CaseRepository.findAllWithCustodians / findWithCustodiansById).
        "spring.jpa.open-in-view=false",
        // No real broker needed; producer/consumer beans are created but no
        // traffic is sent in this slice.
        "spring.kafka.bootstrap-servers=localhost:0"
})
class CaseServiceLifecycleTest {

    @Autowired
    private CaseService caseService;

    @MockBean
    private CaseEventPublisher caseEventPublisher;

    @MockBean
    private AuditTrail auditTrail;

    @Test
    void caseGoesThroughFullLifecycleAndBecomesReadOnly() {
        CaseEntity created = caseService.createCase(new CreateCaseRequest(
                "Q3 Investigation", "bonus pool leak", MatterType.INVESTIGATION,
                "alice", java.util.List.of()));
        assertEquals(CaseState.DRAFT, created.getState());

        CaseEntity active = caseService.transition(created.getId(), new TransitionRequest(CaseState.ACTIVE));
        assertEquals(CaseState.ACTIVE, active.getState());

        CaseEntity review = caseService.transition(created.getId(), new TransitionRequest(CaseState.UNDER_REVIEW));
        assertEquals(CaseState.UNDER_REVIEW, review.getState());

        CaseEntity closed = caseService.transition(created.getId(), new TransitionRequest(CaseState.CLOSED));
        assertEquals(CaseState.CLOSED, closed.getState());

        // Closed case is read-only (FR-2.5): adding evidence must throw.
        assertThrows(CaseClosedException.class, () ->
                caseService.addEvidence(closed.getId(), new AddEvidenceRequest("msg-1", "manual")));
    }

    /**
     * Regression for the 500 on "Add all results to case" (FR-3.6). Overlapping
     * search result sets are normal, so a bulk add whose payload includes
     * messages already on the case must add the new ones and skip the rest.
     *
     * <p>This fails against the previous implementation: {@code save} only
     * queued the inserts, so the unique-constraint violation was raised by the
     * flush <em>after</em> the loop, escaping the per-item {@code catch} and
     * failing the whole request.
     */
    @Test
    void bulkAddSkipsMessagesAlreadyOnTheCaseInsteadOfFailing() {
        CaseEntity c = caseService.createCase(new CreateCaseRequest(
                "Bulk case", "overlapping searches", MatterType.INVESTIGATION,
                "alice", java.util.List.of()));

        caseService.addEvidenceBulk(c.getId(),
                new AddEvidenceBulkRequest(java.util.List.of("msg-1", "msg-2"), "search"));

        // "msg-1" is already attached, "msg-2" is repeated within the payload.
        java.util.List<EvidenceItem> after = caseService.addEvidenceBulk(c.getId(),
                new AddEvidenceBulkRequest(java.util.List.of("msg-1", "msg-3", "msg-2", "msg-2"), "search"));

        assertEquals(java.util.List.of("msg-1", "msg-2", "msg-3"),
                after.stream().map(EvidenceItem::getMessageId).sorted().toList());
    }

    /** Re-adding a single message is idempotent rather than a constraint violation. */
    @Test
    void addingTheSameMessageTwiceIsANoOp() {
        CaseEntity c = caseService.createCase(new CreateCaseRequest(
                "Single case", "duplicate add", MatterType.INVESTIGATION,
                "alice", java.util.List.of()));

        caseService.addEvidence(c.getId(), new AddEvidenceRequest("msg-9", "manual"));
        java.util.List<EvidenceItem> after =
                caseService.addEvidence(c.getId(), new AddEvidenceRequest("msg-9", "manual"));

        assertEquals(1, after.size());
    }

    /**
     * Regression for the {@code LazyInitializationException} on {@code GET /api/v1/cases}:
     * with {@code open-in-view=false}, {@code listCases()} must initialize the
     * {@code custodianIds} {@code @ElementCollection} within the transaction, or
     * JSON serialization (and any post-transaction access) fails. With
     * {@code open-in-view=true} (the Spring Boot default) this passed by accident,
     * so the test pins the production setting.
     */
    @Test
    void listCasesInitializesCustodianIdsOutsideTheSession() {
        caseService.createCase(new CreateCaseRequest(
                "Custodian case", "scope", MatterType.INVESTIGATION,
                "alice", java.util.List.of("cust-1", "cust-2")));

        // Returning from the @Transactional method closes the session; touching
        // the lazy collection here would throw without the fetch-join fix.
        java.util.List<CaseEntity> cases = caseService.listCases();
        CaseEntity mine = cases.stream()
                .filter(c -> "Custodian case".equals(c.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(java.util.List.of("cust-1", "cust-2"), mine.getCustodianIds());
    }
}
