package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.MatterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body for {@code POST /api/v1/cases} (FR-2.1). */
public record CreateCaseRequest(
        @NotBlank String name,
        String description,
        @NotNull MatterType matterType,
        @NotBlank String owner,
        List<String> custodianIds
) {
}
