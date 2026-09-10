package com.smarsh.discoveryhub.export.scope;

import java.util.List;

/**
 * Resolves an export's scope expression into the concrete message ids to
 * package (FR-6.1: "a case's evidence items <em>or</em> a hold's full scope").
 *
 * <p>One implementation per kind of scope, selected at runtime by
 * {@link ExportScopeResolvers}. Adding a future scope — a saved search, a
 * custodian, a date range — means adding a class, not editing a chain of
 * {@code if} statements in the job runner.
 */
public interface ExportScopeResolver {

    /** Whether this resolver understands the given scope expression. */
    boolean supports(String scope);

    /**
     * @param caseId the job's case
     * @param scope  the scope expression, e.g. {@code evidence} or {@code hold:<holdId>}
     * @return message ids to package, in a stable order
     */
    List<String> resolve(String caseId, String scope);

    /** Human-readable description, recorded in the package provenance. */
    String describe(String scope);
}
