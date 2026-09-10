package com.smarsh.discoveryhub.export.scope;

import com.smarsh.discoveryhub.export.api.ArchiveServiceClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exports a legal hold's full scope — every message the hold froze, whether or
 * not anyone attached it to the case as evidence (FR-6.1).
 *
 * <p>Scope expression: {@code hold:<holdId>}.
 *
 * <p>The ids come from archive-service's record of which messages carry the
 * hold, not from re-running the hold's original search criteria. Re-running
 * the search would resolve against today's corpus and could quietly produce a
 * different set than the one actually frozen — which is exactly the kind of
 * drift a chain of custody exists to prevent.
 */
@Component
public class HoldScopeResolver implements ExportScopeResolver {

    public static final String PREFIX = "hold:";

    private final ArchiveServiceClient archiveClient;

    public HoldScopeResolver(ArchiveServiceClient archiveClient) {
        this.archiveClient = archiveClient;
    }

    @Override
    public boolean supports(String scope) {
        return scope != null && scope.toLowerCase().startsWith(PREFIX);
    }

    @Override
    public List<String> resolve(String caseId, String scope) {
        return archiveClient.getMessageIdsForHold(holdId(scope));
    }

    @Override
    public String describe(String scope) {
        return "Full scope of legal hold " + holdId(scope);
    }

    private static String holdId(String scope) {
        String id = scope.substring(PREFIX.length()).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Export scope '" + scope + "' is missing a hold id");
        }
        return id;
    }
}
