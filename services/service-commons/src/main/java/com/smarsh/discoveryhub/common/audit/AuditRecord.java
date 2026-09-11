package com.smarsh.discoveryhub.common.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single entry to append to the audit trail (FR-7.2).
 *
 * <p>Built fluently rather than passed as seven positional strings, because
 * most call sites only care about three of them and the rest were being filled
 * with {@code null}. The {@code before}/{@code after} details are supplied as
 * maps and serialized by the publisher, so no call site hand-builds JSON —
 * hand-built JSON was silently producing invalid documents whenever a value
 * contained a quote (e.g. an export failure message).
 *
 * <pre>{@code
 * auditTrail.record(AuditRecord.action("HOLD_RELEASED")
 *         .on("HOLD", holdId)
 *         .by(actor)
 *         .inCase(caseId)
 *         .after("reason", reason));
 * }</pre>
 */
public final class AuditRecord {

    private final String action;
    private String entityType = "UNKNOWN";
    private String entityId;
    private String actor;
    private String caseId;
    private Map<String, Object> before;
    private Map<String, Object> after;

    private AuditRecord(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("audit action is required");
        }
        this.action = action;
    }

    /** Start building an entry for the given action, e.g. {@code CASE_CREATED}. */
    public static AuditRecord action(String action) {
        return new AuditRecord(action);
    }

    /** The entity this action targeted, e.g. {@code ("CASE", caseId)}. */
    public AuditRecord on(String entityType, String entityId) {
        this.entityType = entityType;
        this.entityId = entityId;
        return this;
    }

    /**
     * Who performed the action. Usually left unset: the publisher falls back
     * to {@link CurrentActor} (the investigator on the current request) and
     * then to the service name for background work.
     */
    public AuditRecord by(String actor) {
        if (actor != null && !actor.isBlank()) {
            this.actor = actor;
        }
        return this;
    }

    /** The case this action belongs to, if any — this is what FR-7.4 filters on. */
    public AuditRecord inCase(String caseId) {
        this.caseId = caseId;
        return this;
    }

    /** State before the change (FR-7.2 "before/after details where relevant"). */
    public AuditRecord before(String key, Object value) {
        this.before = put(this.before, key, value);
        return this;
    }

    /** State after the change. */
    public AuditRecord after(String key, Object value) {
        this.after = put(this.after, key, value);
        return this;
    }

    public AuditRecord before(Map<String, Object> details) {
        this.before = details;
        return this;
    }

    public AuditRecord after(Map<String, Object> details) {
        this.after = details;
        return this;
    }

    public String actionName() {
        return action;
    }

    public String entityType() {
        return entityType;
    }

    public String entityId() {
        return entityId;
    }

    public String actor() {
        return actor;
    }

    public String caseId() {
        return caseId;
    }

    public Map<String, Object> beforeDetails() {
        return before;
    }

    public Map<String, Object> afterDetails() {
        return after;
    }

    private static Map<String, Object> put(Map<String, Object> target, String key, Object value) {
        Map<String, Object> map = target == null ? new LinkedHashMap<>() : target;
        map.put(key, value);
        return map;
    }
}
