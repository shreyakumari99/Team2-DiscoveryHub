package com.smarsh.discoveryhub.holdretention.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** Body for {@code POST /api/v1/holds} (FR-4.1). */
public record PlaceHoldRequest(
        @NotBlank String caseId,
        List<String> custodians,
        Instant dateFrom,
        Instant dateTo,
        String searchTerms
) {
}
