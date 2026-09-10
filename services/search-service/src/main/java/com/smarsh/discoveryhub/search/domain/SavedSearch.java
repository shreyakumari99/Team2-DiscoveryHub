package com.smarsh.discoveryhub.search.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A saved search (FR-3.5). Persisted in the search-service's own embedded
 * store so an investigator can re-run a query per case later. The full filter
 * criteria are stored as a JSON string; the service re-parses them on re-run.
 */
@Entity
@Table(name = "saved_searches")
public class SavedSearch {

    @Id
    private String id;
    private String caseId;
    private String name;
    private String queryJson;

    public SavedSearch() {
    }

    public SavedSearch(String id, String caseId, String name, String queryJson) {
        this.id = id;
        this.caseId = caseId;
        this.name = name;
        this.queryJson = queryJson;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getQueryJson() {
        return queryJson;
    }

    public void setQueryJson(String queryJson) {
        this.queryJson = queryJson;
    }
}
