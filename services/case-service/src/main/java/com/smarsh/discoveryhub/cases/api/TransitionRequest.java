package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.CaseState;
import jakarta.validation.constraints.NotNull;

/** Body for {@code POST /api/v1/cases/{id}/transition} (FR-2.2). */
public record TransitionRequest(@NotNull CaseState to) {
}
