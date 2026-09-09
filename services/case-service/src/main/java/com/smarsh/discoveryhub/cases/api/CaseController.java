package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.cases.domain.CaseEntity;
import com.smarsh.discoveryhub.cases.domain.CustodianEntity;
import com.smarsh.discoveryhub.cases.domain.EvidenceItem;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Case-service REST API (FR-2).
 *
 * <pre>
 *   POST   /api/v1/cases                          create a case (DRAFT)
 *   GET    /api/v1/cases                          list cases
 *   GET    /api/v1/cases/{id}                     get a case
 *   PATCH  /api/v1/cases/{id}                     update name/description/owner
 *   POST   /api/v1/cases/{id}/transition          advance lifecycle (FR-2.2)
 *   POST   /api/v1/cases/{id}/custodians          attach custodians (FR-2.3)
 *   POST   /api/v1/cases/{id}/evidence            add single evidence item (FR-2.4)
 *   POST   /api/v1/cases/{id}/evidence/bulk       add many at once (FR-3.6)
 *   GET    /api/v1/cases/{id}/evidence            list evidence
 *   DELETE /api/v1/cases/{caseId}/evidence/{eid}  remove evidence
 *   POST   /api/v1/custodians                     create a custodian
 *   GET    /api/v1/custodians                     list custodians
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseEntity> createCase(@Valid @RequestBody CreateCaseRequest request) {
        CaseEntity created = caseService.createCase(request);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/cases")
    public List<CaseEntity> listCases() {
        return caseService.listCases();
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<CaseEntity> getCase(@PathVariable String id) {
        return ResponseEntity.ok(caseService.getCase(id));
    }

    @PostMapping("/cases/{id}")
    public ResponseEntity<CaseEntity> updateCase(@PathVariable String id,
                                                 @Valid @RequestBody UpdateCaseRequest request) {
        return ResponseEntity.ok(caseService.updateCase(id, request));
    }

    @PostMapping("/cases/{id}/transition")
    public ResponseEntity<CaseEntity> transition(@PathVariable String id,
                                                 @Valid @RequestBody TransitionRequest request) {
        return ResponseEntity.ok(caseService.transition(id, request));
    }

    @PostMapping("/cases/{id}/custodians")
    public ResponseEntity<CaseEntity> addCustodians(@PathVariable String id,
                                                    @Valid @RequestBody AddCustodiansRequest request) {
        return ResponseEntity.ok(caseService.addCustodians(id, request));
    }

    @PostMapping("/cases/{id}/evidence")
    public ResponseEntity<List<EvidenceItem>> addEvidence(@PathVariable String id,
                                                          @Valid @RequestBody AddEvidenceRequest request) {
        return ResponseEntity.ok(caseService.addEvidence(id, request));
    }

    @PostMapping("/cases/{id}/evidence/bulk")
    public ResponseEntity<List<EvidenceItem>> addEvidenceBulk(@PathVariable String id,
                                                              @Valid @RequestBody AddEvidenceBulkRequest request) {
        return ResponseEntity.ok(caseService.addEvidenceBulk(id, request));
    }

    @GetMapping("/cases/{id}/evidence")
    public List<EvidenceItem> listEvidence(@PathVariable String id) {
        return caseService.listEvidence(id);
    }

    @DeleteMapping("/cases/{caseId}/evidence/{evidenceId}")
    public ResponseEntity<Void> removeEvidence(@PathVariable String caseId,
                                               @PathVariable String evidenceId) {
        caseService.removeEvidence(caseId, evidenceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/custodians")
    public ResponseEntity<CustodianEntity> createCustodian(@RequestBody CustodianEntity custodian) {
        return ResponseEntity.ok(caseService.createCustodian(custodian));
    }

    @GetMapping("/custodians")
    public List<CustodianEntity> listCustodians() {
        return caseService.listCustodians();
    }
}
