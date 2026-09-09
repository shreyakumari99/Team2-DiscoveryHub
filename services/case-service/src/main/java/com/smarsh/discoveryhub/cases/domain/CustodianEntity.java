package com.smarsh.discoveryhub.cases.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A custodian (FR-2.3). The data-generator creates these; the case-service
 * stores them so cases can reference them by id and the UI can list them.
 */
@Entity
@Table(name = "custodians")
public class CustodianEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;
    private String email;
    private String department;

    public CustodianEntity() {
    }

    public CustodianEntity(String name, String email, String department) {
        this.name = name;
        this.email = email;
        this.department = department;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
