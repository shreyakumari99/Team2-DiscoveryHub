package com.smarsh.discoveryhub.cases.api;

import java.util.List;

/** Body for {@code POST /api/v1/cases/{id}/custodians} (FR-2.3). */
public record AddCustodiansRequest(List<String> custodianIds) {
}
