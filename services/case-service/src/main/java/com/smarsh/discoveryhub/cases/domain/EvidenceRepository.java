package com.smarsh.discoveryhub.cases.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvidenceRepository extends JpaRepository<EvidenceItem, String> {
    List<EvidenceItem> findByCaseId(String caseId);
}
