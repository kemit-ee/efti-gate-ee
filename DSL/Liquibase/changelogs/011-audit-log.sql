-- ----------------------------------------------------------------------------
-- 4.5 audit_log
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_log (
  row_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID,
  action      VARCHAR(64)  NOT NULL,
  resource    VARCHAR(64)  NOT NULL,
  resource_id VARCHAR(200),
  ip_address  INET,
  details     JSONB,
  recorded_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  audit_log IS 'High-level user-action audit trail (login, search, dataset request, follow-up sent, registry mutation, etc.). INSERT-only.';
COMMENT ON COLUMN audit_log.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN audit_log.user_id     IS 'Logical users.id of the actor. NULL for system actions or pre-authentication events.';
COMMENT ON COLUMN audit_log.action      IS 'Action verb (login, login_failed, search_identifiers, request_dataset, …)';
COMMENT ON COLUMN audit_log.resource    IS 'Resource type touched (dataset, identifier, platform, gate, user, …)';
COMMENT ON COLUMN audit_log.resource_id IS 'Identifier of the resource; NULL for collection-level actions';
COMMENT ON COLUMN audit_log.ip_address  IS 'Source IP address (INET supports v4 and v6)';
COMMENT ON COLUMN audit_log.details     IS 'Structured context (search filters, query parameters, error reason, …)';
COMMENT ON COLUMN audit_log.recorded_at IS 'When the action happened';
COMMENT ON COLUMN audit_log.created_at  IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_audit_log_user_recorded ON audit_log (user_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action        ON audit_log (action, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource      ON audit_log (resource, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_recorded      ON audit_log (recorded_at DESC);

GRANT SELECT, INSERT ON audit_log TO app;
GRANT SELECT ON audit_log TO db_archiver;
