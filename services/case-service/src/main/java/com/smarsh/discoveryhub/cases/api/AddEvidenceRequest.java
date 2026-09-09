package com.smarsh.discoveryhub.cases.api;

import jakarta.validation.constraints.NotBlank;

/** Body for {@code POST /api/v1/cases/{id}/evidence} (FR-2.4). */
public record AddEvidenceRequest(@NotBlank String messageId, String source) {
}
