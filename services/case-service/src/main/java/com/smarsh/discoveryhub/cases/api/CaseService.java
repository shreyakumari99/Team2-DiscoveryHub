package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.cases.domain.CaseEntity;
import com.smarsh.discoveryhub.cases.domain.CaseRepository;
import com.smarsh.discoveryhub.cases.domain.CustodianEntity;
import com.smarsh.discoveryhub.cases.domain.CustodianRepository;
import com.smarsh.discoveryhub.cases.domain.EvidenceItem;
import com.smarsh.discoveryhub.cases.domain.EvidenceRepository;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.CaseState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for cases, custodians and evidence (FR-2).
 *
 * <p>Enforces the lifecycle state machine (FR-2.2) and the read-only rule for
 * closed cases (FR-2.5). Every mutation emits a case event (for downstream
 * services) and an audit event (for the audit trail).
 */
@Service
@Transactional
public class CaseService {

    private final CaseRepository caseRepository;
    private final CustodianRepository custodianRepository;
    private final EvidenceRepository evidenceRepository;
    private final CaseEventPublisher caseEventPublisher;
    private final AuditTrail auditTrail;

    public CaseService(CaseRepository caseRepository,
                       CustodianRepository custodianRepository,
                       EvidenceRepository evidenceRepository,
                       CaseEventPublisher caseEventPublisher,
                       AuditTrail auditTrail) {
        this.caseRepository = caseRepository;
        this.custodianRepository = custodianRepository;
        this.evidenceRepository = evidenceRepository;
        this.caseEventPublisher = caseEventPublisher;
        this.auditTrail = auditTrail;
    }

    public CaseEntity createCase(CreateCaseRequest request) {
        CaseEntity c = new CaseEntity();
        c.setName(request.name());
        c.setDescription(request.description());
        c.setMatterType(request.matterType());
        c.setOwner(request.owner());
        c.setState(CaseState.DRAFT);
        c.setCreatedAt(Instant.now());
        if (request.custodianIds() != null) {
            c.getCustodianIds().addAll(request.custodianIds());
        }
        CaseEntity saved = caseRepository.save(c);

        caseEventPublisher.publish(saved.getId(), "CREATED", saved.getName(), saved.getState(),
                Map.of("owner", saved.getOwner()));
        auditTrail.record(AuditRecord.action("CASE_CREATED").on("CASE", saved.getId()).inCase(saved.getId())
                .after("name", saved.getName())
                .after("matterType", String.valueOf(saved.getMatterType()))
                .after("owner", saved.getOwner())
                .after("state", saved.getState().name()));
        return saved;
    }

    public CaseEntity getCase(String id) {
        return caseRepository.findWithCustodiansById(id)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + id));
    }

    public List<CaseEntity> listCases() {
        return caseRepository.findAllWithCustodians();
    }

    public CaseEntity updateCase(String id, UpdateCaseRequest request) {
        CaseEntity c = requireWritable(id);
        Map<String, Object> before = Map.of(
                "name", String.valueOf(c.getName()),
                "description", String.valueOf(c.getDescription()),
                "owner", String.valueOf(c.getOwner()));
        if (request.name() != null) {
            c.setName(request.name());
        }
        if (request.description() != null) {
            c.setDescription(request.description());
        }
        if (request.owner() != null) {
            c.setOwner(request.owner());
        }
        CaseEntity saved = caseRepository.save(c);
        caseEventPublisher.publish(saved.getId(), "UPDATED", saved.getName(), saved.getState(), Map.of());
        auditTrail.record(AuditRecord.action("CASE_UPDATED").on("CASE", saved.getId()).inCase(saved.getId())
                .before(before)
                .after("name", saved.getName())
                .after("description", saved.getDescription())
                .after("owner", saved.getOwner()));
        return saved;
    }

    public CaseEntity transition(String id, TransitionRequest request) {
        CaseEntity c = getCase(id);
        CaseState from = c.getState();
        CaseState to = request.to();
        if (!CaseStateMachine.canTransition(from, to)) {
            throw new IllegalCaseTransitionException(id, from, to);
        }
        c.setState(to);
        CaseEntity saved = caseRepository.save(c);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        caseEventPublisher.publish(saved.getId(), "TRANSITIONED", saved.getName(), saved.getState(), details);
        auditTrail.record(AuditRecord.action("CASE_TRANSITIONED").on("CASE", saved.getId()).inCase(saved.getId())
                .before("state", from.name())
                .after("state", to.name()));

        if (to == CaseState.CLOSED) {
            caseEventPublisher.publish(saved.getId(), "CLOSED", saved.getName(), saved.getState(), Map.of());
        }
        return saved;
    }

    public CaseEntity addCustodians(String id, AddCustodiansRequest request) {
        CaseEntity c = requireWritable(id);
        for (String cid : request.custodianIds()) {
            if (!c.getCustodianIds().contains(cid)) {
                c.getCustodianIds().add(cid);
                auditTrail.record(AuditRecord.action("CUSTODIAN_ADDED").on("CUSTODIAN", cid).inCase(id));
            }
        }
        return caseRepository.save(c);
    }

    public List<EvidenceItem> addEvidence(String id, AddEvidenceRequest request) {
        CaseEntity c = requireWritable(id);
        EvidenceItem item = new EvidenceItem(id, request.messageId(),
                request.source() != null ? request.source() : "manual", Instant.now());
        evidenceRepository.save(item);
        auditTrail.record(AuditRecord.action("EVIDENCE_ADDED").on("EVIDENCE", item.getId()).inCase(id)
                .after("messageId", request.messageId())
                .after("source", item.getSource()));
        return evidenceRepository.findByCaseId(id);
    }

    public List<EvidenceItem> addEvidenceBulk(String id, AddEvidenceBulkRequest request) {
        CaseEntity c = requireWritable(id);
        for (String mid : request.messageIds()) {
            try {
                evidenceRepository.save(new EvidenceItem(id, mid,
                        request.source() != null ? request.source() : "search", Instant.now()));
                auditTrail.record(AuditRecord.action("EVIDENCE_ADDED").on("EVIDENCE", mid).inCase(id)
                        .after("messageId", mid).after("source", "search"));
            } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                // (caseId, messageId) already present — skip duplicates.
            }
        }
        return evidenceRepository.findByCaseId(id);
    }

    public void removeEvidence(String caseId, String evidenceId) {
        requireWritable(caseId);
        evidenceRepository.findById(evidenceId)
                .filter(e -> caseId.equals(e.getCaseId()))
                .ifPresent(e -> {
                    evidenceRepository.delete(e);
                    auditTrail.record(AuditRecord.action("EVIDENCE_REMOVED").on("EVIDENCE", evidenceId).inCase(caseId)
                            .before("messageId", e.getMessageId()));
                });
    }

    public List<EvidenceItem> listEvidence(String caseId) {
        return evidenceRepository.findByCaseId(caseId);
    }

    public CustodianEntity createCustodian(CustodianEntity custodian) {
        CustodianEntity saved = custodianRepository.save(custodian);
        auditTrail.record(AuditRecord.action("CUSTODIAN_CREATED").on("CUSTODIAN", saved.getId())
                .after("name", saved.getName())
                .after("email", saved.getEmail()));
        return saved;
    }

    public List<CustodianEntity> listCustodians() {
        return custodianRepository.findAll();
    }

    // ---- helpers ---------------------------------------------------------

    private CaseEntity requireWritable(String id) {
        CaseEntity c = getCase(id);
        if (c.getState() == CaseState.CLOSED) {
            throw new CaseClosedException(id);
        }
        return c;
    }

}
