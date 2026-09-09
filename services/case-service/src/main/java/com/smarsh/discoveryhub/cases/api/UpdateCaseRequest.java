package com.smarsh.discoveryhub.cases.api;

/** Body for {@code POST /api/v1/cases/{id}} (update name/description/owner). */
public record UpdateCaseRequest(String name, String description, String owner) {
}
