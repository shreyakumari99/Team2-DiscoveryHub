package com.smarsh.discoveryhub.export.scope;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the {@link ExportScopeResolver} that understands a given scope
 * expression. Spring injects every implementation, so registering a new scope
 * is a matter of adding a {@code @Component} — nothing here changes.
 */
@Component
public class ExportScopeResolvers {

    private final List<ExportScopeResolver> resolvers;

    public ExportScopeResolvers(List<ExportScopeResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public ExportScopeResolver forScope(String scope) {
        return resolvers.stream()
                .filter(r -> r.supports(scope))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported export scope: " + scope));
    }
}
