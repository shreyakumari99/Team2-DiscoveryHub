package com.smarsh.discoveryhub.cases.api;

import java.util.List;

/** Body for {@code POST /api/v1/cases/{id}/evidence/bulk} (FR-3.6 bulk add). */
public record AddEvidenceBulkRequest(List<String> messageIds, String source) {
}
