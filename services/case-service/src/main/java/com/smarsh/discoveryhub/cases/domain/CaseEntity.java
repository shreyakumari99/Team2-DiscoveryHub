package com.smarsh.discoveryhub.cases.domain;

import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.MatterType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A case (FR-2.1). {@code state} holds the lifecycle position; transitions are
 * validated by {@link com.smarsh.discoveryhub.cases.api.CaseService} (FR-2.2).
 * {@code custodianIds} are references to custodians stored as a separate
 * table; a closed case is read-only (FR-2.5).
 */
@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatterType matterType;

    @Column(nullable = false)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseState state;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "case_custodians", joinColumns = @JoinColumn(name = "case_id"))
    @Column(name = "custodian_id")
    private List<String> custodianIds = new ArrayList<>();

    public CaseEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public MatterType getMatterType() {
        return matterType;
    }

    public void setMatterType(MatterType matterType) {
        this.matterType = matterType;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public CaseState getState() {
        return state;
    }

    public void setState(CaseState state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getCustodianIds() {
        return custodianIds;
    }

    public void setCustodianIds(List<String> custodianIds) {
        this.custodianIds = custodianIds;
    }
}
