package com.smarsh.discoveryhub.cases;

import com.smarsh.discoveryhub.cases.api.AddCustodiansRequest;
import com.smarsh.discoveryhub.cases.api.AddEvidenceBulkRequest;
import com.smarsh.discoveryhub.cases.api.AddEvidenceRequest;
import com.smarsh.discoveryhub.cases.api.CaseClosedException;
import com.smarsh.discoveryhub.cases.api.CaseEventPublisher;
import com.smarsh.discoveryhub.cases.api.CaseService;
import com.smarsh.discoveryhub.cases.api.CreateCaseRequest;
import com.smarsh.discoveryhub.cases.api.TransitionRequest;
import com.smarsh.discoveryhub.cases.api.UpdateCaseRequest;
import com.smarsh.discoveryhub.cases.domain.CaseEntity;
import com.smarsh.discoveryhub.cases.domain.CustodianEntity;
import com.smarsh.discoveryhub.cases.domain.EvidenceItem;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.MatterType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutating paths {@link CaseServiceLifecycleTest} does not reach: updating a
 * case, attaching custodians, removing evidence, and the custodian registry.
 *
 * <p>Deliberately declares the same {@code @SpringBootTest} properties and the
 * same mocked beans as that class, so Spring's context cache treats them as one
 * configuration and boots a single application context for both.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:case-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.kafka.bootstrap-servers=localhost:0"
})
class CaseServiceMutationsTest {

    @Autowired
    private CaseService caseService;

    @MockBean
    private CaseEventPublisher caseEventPublisher;

    @MockBean
    private AuditTrail auditTrail;

    private CaseEntity newCase(String name) {
        return caseService.createCase(new CreateCaseRequest(
                name, "description", MatterType.LITIGATION, "alice@smarsh.com", List.of()));
    }

    /** FR-2.1: name, description and owner are editable while a case is open. */
    @Test
    void updatesTheEditableFieldsOfACase() {
        CaseEntity c = newCase("Update me");

        CaseEntity updated = caseService.updateCase(c.getId(),
                new UpdateCaseRequest("Renamed matter", "revised scope", "bob@smarsh.com"));

        assertEquals("Renamed matter", updated.getName());
        assertEquals("revised scope", updated.getDescription());
        assertEquals("bob@smarsh.com", updated.getOwner());
    }

    /**
     * A null field means "leave this alone" rather than "set it to null" — the
     * UI sends only what the investigator actually edited.
     */
    @Test
    void nullFieldsInAnUpdateLeaveTheExistingValueIntact() {
        CaseEntity c = newCase("Partial update");

        CaseEntity updated = caseService.updateCase(c.getId(),
                new UpdateCaseRequest(null, null, "carol@smarsh.com"));

        assertEquals("Partial update", updated.getName());
        assertEquals("description", updated.getDescription());
        assertEquals("carol@smarsh.com", updated.getOwner());
    }

    /** FR-2.3: custodians accumulate, and attaching the same one twice is a no-op. */
    @Test
    void attachesCustodiansWithoutDuplicating() {
        CaseEntity c = newCase("Custodian scope");

        caseService.addCustodians(c.getId(), new AddCustodiansRequest(List.of("alice@smarsh.com")));
        CaseEntity after = caseService.addCustodians(c.getId(),
                new AddCustodiansRequest(List.of("alice@smarsh.com", "bob@smarsh.com")));

        assertEquals(List.of("alice@smarsh.com", "bob@smarsh.com"),
                after.getCustodianIds().stream().sorted().toList());
    }

    @Test
    void removesAnEvidenceItem() {
        CaseEntity c = newCase("Remove evidence");
        List<EvidenceItem> added = caseService.addEvidence(c.getId(),
                new AddEvidenceRequest("msg-remove-1", "manual"));
        String evidenceId = added.get(0).getId();

        caseService.removeEvidence(c.getId(), evidenceId);

        assertTrue(caseService.listEvidence(c.getId()).isEmpty());
    }

    /**
     * Removal is scoped to the case in the URL. Passing another case's evidence
     * id must not delete it — otherwise one case could quietly strip evidence
     * from another.
     */
    @Test
    void doesNotRemoveEvidenceBelongingToADifferentCase() {
        CaseEntity mine = newCase("Owner case");
        CaseEntity other = newCase("Other case");
        String otherEvidenceId = caseService.addEvidence(other.getId(),
                new AddEvidenceRequest("msg-other-1", "manual")).get(0).getId();

        // Asking the wrong case to remove it is silently ignored, not an error.
        caseService.removeEvidence(mine.getId(), otherEvidenceId);

        assertEquals(1, caseService.listEvidence(other.getId()).size());
    }

    /** Removing something that was never there is harmless. */
    @Test
    void removingAnUnknownEvidenceItemIsHarmless() {
        CaseEntity c = newCase("No such evidence");

        caseService.removeEvidence(c.getId(), "does-not-exist");

        assertTrue(caseService.listEvidence(c.getId()).isEmpty());
    }

    /** FR-2.3: the custodian registry the UI pickers read from. */
    @Test
    void registersAndListsCustodians() {
        CustodianEntity saved = caseService.createCustodian(
                new CustodianEntity("Viktor Petrov", "viktor.petrov@smarsh.com", "Research"));

        assertNotNull(saved.getId());
        assertEquals("Viktor Petrov", saved.getName());
        assertEquals("viktor.petrov@smarsh.com", saved.getEmail());
        assertEquals("Research", saved.getDepartment());

        assertTrue(caseService.listCustodians().stream()
                .anyMatch(c -> "viktor.petrov@smarsh.com".equals(c.getEmail())));
    }

    /**
     * FR-2.5 in full: a closed case refuses <em>every</em> mutation, not only
     * the evidence path covered by the lifecycle test. Each of these is a
     * separate guard in the service, and a newly added mutation that forgets
     * the check would slip through unnoticed.
     */
    @Test
    void aClosedCaseRefusesEveryMutation() {
        CaseEntity c = newCase("To be closed");
        String id = c.getId();
        caseService.transition(id, new TransitionRequest(CaseState.ACTIVE));
        caseService.transition(id, new TransitionRequest(CaseState.UNDER_REVIEW));
        caseService.transition(id, new TransitionRequest(CaseState.CLOSED));

        assertThrows(CaseClosedException.class, () ->
                caseService.updateCase(id, new UpdateCaseRequest("nope", null, null)));
        assertThrows(CaseClosedException.class, () ->
                caseService.addCustodians(id, new AddCustodiansRequest(List.of("alice@smarsh.com"))));
        assertThrows(CaseClosedException.class, () ->
                caseService.addEvidence(id, new AddEvidenceRequest("msg-x", "manual")));
        assertThrows(CaseClosedException.class, () ->
                caseService.addEvidenceBulk(id, new AddEvidenceBulkRequest(List.of("msg-y"), "search")));
        assertThrows(CaseClosedException.class, () ->
                caseService.removeEvidence(id, "any-evidence-id"));
    }

    /** An unknown case id is reported, not silently treated as empty. */
    @Test
    void fetchingAnUnknownCaseFails() {
        assertThrows(IllegalArgumentException.class, () -> caseService.getCase("no-such-case"));
    }
}
