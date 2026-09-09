package com.smarsh.discoveryhub.cases.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * An evidence item attached to a case (FR-2.4). References an archived message
 * by its immutable id. {@code source} records how it was added ("manual" or
 * "search:<savedSearchId>"). The (caseId, messageId) pair is unique so the same
 * message cannot be added twice to the same case.
 */
@Entity
@Table(name = "evidence_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"caseId", "messageId"}))
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String caseId;
    private String messageId;
    private String source;
    private Instant addedAt;

    public EvidenceItem() {
    }

    public EvidenceItem(String caseId, String messageId, String source, Instant addedAt) {
        this.caseId = caseId;
        this.messageId = messageId;
        this.source = source;
        this.addedAt = addedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }
}
