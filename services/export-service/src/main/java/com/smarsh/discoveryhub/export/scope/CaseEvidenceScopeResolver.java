package com.smarsh.discoveryhub.export.scope;

import com.smarsh.discoveryhub.export.api.CaseServiceClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The default scope: every evidence item attached to the case (FR-6.1).
 */
@Component
public class CaseEvidenceScopeResolver implements ExportScopeResolver {

    public static final String SCOPE = "evidence";

    private final CaseServiceClient caseClient;

    public CaseEvidenceScopeResolver(CaseServiceClient caseClient) {
        this.caseClient = caseClient;
    }

    @Override
    public boolean supports(String scope) {
        return scope == null || scope.isBlank() || SCOPE.equalsIgnoreCase(scope);
    }

    @Override
    public List<String> resolve(String caseId, String scope) {
        return caseClient.getEvidenceMessageIds(caseId);
    }

    @Override
    public String describe(String scope) {
        return "All evidence items attached to the case";
    }
}
