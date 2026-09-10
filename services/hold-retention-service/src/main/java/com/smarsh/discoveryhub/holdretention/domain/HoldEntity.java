package com.smarsh.discoveryhub.holdretention.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A legal hold placed on a case (FR-4.1). Scope = custodian(s) + optional date
 * range + optional free-text terms. {@code active} is false once released
 * (FR-4.5). {@code messageCount} is the resolved scope size, recorded for the
 * UI badge (FR-4.4) once scope resolution completes.
 */
@Entity
@Table(name = "holds")
public class HoldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String caseId;
    private boolean active;
    private String searchTerms;
    private Instant dateFrom;
    private Instant dateTo;
    private long messageCount;
    private Instant placedAt;
    private Instant releasedAt;

    /**
     * Whether scope resolution has finished. An unresolved hold is active but
     * protects nothing yet; surfacing that is important, because a hold
     * silently stuck at zero messages looks identical to a hold that correctly
     * matched nothing.
     */
    private boolean scopeResolved;

    private Instant scopeResolvedAt;

    @Column(length = 1000)
    private String scopeFailureReason;

    @ElementCollection
    @CollectionTable(name = "hold_custodians", joinColumns = @JoinColumn(name = "hold_id"))
    @Column(name = "custodian")
    private List<String> custodians = new ArrayList<>();

    public HoldEntity() {
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getSearchTerms() {
        return searchTerms;
    }

    public void setSearchTerms(String searchTerms) {
        this.searchTerms = searchTerms;
    }

    public Instant getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(Instant dateFrom) {
        this.dateFrom = dateFrom;
    }

    public Instant getDateTo() {
        return dateTo;
    }

    public void setDateTo(Instant dateTo) {
        this.dateTo = dateTo;
    }

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(Instant placedAt) {
        this.placedAt = placedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public boolean isScopeResolved() {
        return scopeResolved;
    }

    public void setScopeResolved(boolean scopeResolved) {
        this.scopeResolved = scopeResolved;
    }

    public Instant getScopeResolvedAt() {
        return scopeResolvedAt;
    }

    public void setScopeResolvedAt(Instant scopeResolvedAt) {
        this.scopeResolvedAt = scopeResolvedAt;
    }

    public String getScopeFailureReason() {
        return scopeFailureReason;
    }

    public void setScopeFailureReason(String scopeFailureReason) {
        this.scopeFailureReason = scopeFailureReason;
    }

    public List<String> getCustodians() {
        return custodians;
    }

    /**
     * Copies into a mutable list on purpose. Hibernate clears and refills the
     * backing collection when merging the entity, so handing it an immutable
     * list (an empty {@code List.of()}, say) fails at flush time with an
     * {@code UnsupportedOperationException} — which would have broken every
     * hold placed without custodians.
     */
    public void setCustodians(List<String> custodians) {
        this.custodians = custodians == null ? new ArrayList<>() : new ArrayList<>(custodians);
    }
}
