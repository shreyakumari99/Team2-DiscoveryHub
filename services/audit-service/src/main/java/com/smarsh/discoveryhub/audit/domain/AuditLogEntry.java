package com.smarsh.discoveryhub.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One append-only audit entry (FR-7.2). Mirrors {@link
 * com.smarsh.discoveryhub.events.AuditEvent}.
 *
 * <p><b>Append-only (FR-7.3), enforced twice.</b> In code, the repository is
 * only ever used to insert and read, and the REST API exposes no write verb.
 * In the database, {@code data-postgresql.sql} installs triggers that reject
 * UPDATE, DELETE and TRUNCATE on this table outright — so the trail holds even
 * against a future bug or a hand-typed query.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String dbId;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private Instant timestamp;

    private String actor;
    private String service;
    private String action;
    private String entityType;
    private String entityId;
    private String caseId;

    /**
     * Before/after snapshots are stored as unbounded text rather than a capped
     * column: a truncated audit detail is worse than a long one, and a case
     * with many custodians can easily exceed a few kilobytes.
     */
    @Column(columnDefinition = "text")
    private String beforeJson;

    @Column(columnDefinition = "text")
    private String afterJson;

    public AuditLogEntry() {
    }

    public AuditLogEntry(String eventId, Instant timestamp, String actor, String service,
                         String action, String entityType, String entityId, String caseId,
                         String beforeJson, String afterJson) {
        this.eventId = eventId;
        this.timestamp = timestamp;
        this.actor = actor;
        this.service = service;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.caseId = caseId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
    }

    public String getDbId() {
        return dbId;
    }

    public void setDbId(String dbId) {
        this.dbId = dbId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }
}
