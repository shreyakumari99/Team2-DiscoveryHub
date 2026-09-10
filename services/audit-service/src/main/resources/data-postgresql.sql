-- FR-7.3: "Audit entries can never be updated or deleted through any API."
--
-- Application code already has no update or delete path, but a guarantee that
-- rests on nobody writing the wrong line of Java is not a guarantee. This
-- enforces it in the database.
--
-- A trigger is used rather than REVOKE UPDATE, DELETE because the application
-- role owns this table, and an owner can always grant its own privileges back.
-- A BEFORE trigger binds the owner too, so the only way to defeat it is to
-- drop the trigger — a deliberate, visible act rather than an accident.
--
-- NOTE ON THE ';;' SEPARATOR: Spring's script runner splits statements on ';'
-- and does not understand PL/pgSQL dollar-quoting, so it would cut the
-- function body in half. `spring.sql.init.separator: ';;'` in application.yml
-- makes ';;' the statement terminator, leaving the semicolons inside the body
-- alone.
--
-- Runs on every startup and is idempotent. Requires
-- spring.jpa.defer-datasource-initialization=true so Hibernate has created
-- audit_log by the time this executes.

CREATE OR REPLACE FUNCTION discoveryhub_audit_log_append_only()
    RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log is append-only (FR-7.3): % is not permitted on the audit trail', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;;

-- Row-level guard against UPDATE and DELETE.
DROP TRIGGER IF EXISTS trg_audit_log_no_update_delete ON audit_log;;
CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION discoveryhub_audit_log_append_only();;

-- TRUNCATE bypasses row triggers, so it needs its own statement-level guard.
DROP TRIGGER IF EXISTS trg_audit_log_no_truncate ON audit_log;;
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION discoveryhub_audit_log_append_only();;

-- Redelivery of the same Kafka event must not duplicate an entry. Hibernate's
-- ddl-auto=update does not reliably add a unique constraint to an existing
-- table, so it is created explicitly here.
CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_log_event_id ON audit_log (event_id);;

-- The trail is read by case and by time; these keep FR-7.4 queries fast as it
-- becomes the largest table in the system.
CREATE INDEX IF NOT EXISTS idx_audit_log_case_id ON audit_log (case_id);;
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log (timestamp DESC);;
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);;
