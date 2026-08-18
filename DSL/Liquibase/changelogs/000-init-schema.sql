-- ============================================================================
-- eFTI Gate (EE) — v0 baseline schema
-- Liquibase changeset 000-init-schema
--
-- Derived from docs/specs/db/schema.sql.
-- Apply once against an empty database via Liquibase; do not re-run manually.
-- All subsequent schema changes go through numbered Liquibase changesets.
-- ============================================================================

-- ============================================================================
-- 0. EXTENSIONS
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

-- ============================================================================
-- 1. CUSTOM TYPES (ENUMs)
-- ============================================================================

DO $$
BEGIN
  CREATE TYPE gate_status AS ENUM (
    'ONLINE',
    'OFFLINE',
    'DISABLED',
    'DELETED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE gate_status IS 'Operational status of an eFTI gate or platform node';

DO $$
BEGIN
  CREATE TYPE consignment_status AS ENUM (
    'ACTIVE',
    'INACTIVE',
    'DELETED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE consignment_status IS 'Lifecycle status of a stored consignment record. State transitions happen by INSERTing a new consignments row with the new status; the latest row wins.';

DO $$
BEGIN
  CREATE TYPE job_status AS ENUM (
    'COMPLETED',
    'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE job_status IS 'Final outcome of a scheduled job execution. jobs_execution_log is INSERT-only at job completion — there is no "running" record.';

DO $$
BEGIN
  CREATE TYPE follow_up_status AS ENUM (
    'DELIVERED',
    'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE follow_up_status IS 'Outcome of a follow-up message forwarding attempt';

-- ============================================================================
-- 2. HELPER — record actor (denormalised users.row_id) on each INSERT
-- ============================================================================

CREATE OR REPLACE FUNCTION set_app_user(p_user UUID) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user', p_user::text, false);
END;
$$;

CREATE OR REPLACE FUNCTION get_app_user() RETURNS UUID
LANGUAGE plpgsql AS $$
DECLARE v text := current_setting('app.user', true);
BEGIN
  RETURN CASE WHEN v IS NULL OR v = '' THEN NULL ELSE v::UUID END;
END;
$$;

COMMENT ON FUNCTION get_app_user() IS 'Returns the current session''s logical actor UUID (users.row_id). Used to populate `created_by` on registry INSERTs. NULL for system actions (background jobs, anonymous events).';

-- ============================================================================
-- 3. OPERATIONAL TABLES (all append-only)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 3.1 gates
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS gates (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,
  country_code    CHAR(2)      NOT NULL,
  e_delivery_url  TEXT,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  status          gate_status  NOT NULL,
  last_ping_at    TIMESTAMPTZ,
  created_by      UUID,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT gates_country_format CHECK (country_code ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  gates IS 'Registry of eFTI gates (own + remote peers). Append-only: each registry change (status flip, ping, URL/cert update) is a new row with the same id. The latest row by created_at is the gate''s current state. Cron-archived by CronManager.';
COMMENT ON COLUMN gates.row_id          IS 'Synthetic primary key, unique per row (one entity has many rows over time)';
COMMENT ON COLUMN gates.id              IS 'Logical gate identifier in eu-{cc}{nn} format (e.g. eu-ee01). Many rows can share this id over time; latest wins.';
COMMENT ON COLUMN gates.country_code    IS 'ISO 3166-1 alpha-2 country code';
COMMENT ON COLUMN gates.e_delivery_url  IS 'AS4 access-point URL for inbound G2G messages';
COMMENT ON COLUMN gates.e_delivery_cert IS 'Public certificate (PEM) used to verify AS4 messages from this gate';
COMMENT ON COLUMN gates.tls_cert        IS 'Public TLS certificate (PEM) used to verify the gate''s HTTPS endpoint';
COMMENT ON COLUMN gates.status          IS 'Current gate operational status: ONLINE — active and available; OFFLINE — ping failed; DISABLED — administratively disabled (visible in list); DELETED — soft deletion (removed by operator, row retained for audit).';
COMMENT ON COLUMN gates.last_ping_at    IS 'Timestamp of the latest successful ping that produced this row. NULL if this row pre-dates first ping.';
COMMENT ON COLUMN gates.created_by      IS 'Denormalised users.row_id of the actor. NULL for system events (ping job, registry sync).';
COMMENT ON COLUMN gates.created_at      IS 'When this row was inserted. Latest created_at per id is the current state.';

CREATE INDEX IF NOT EXISTS idx_gates_id_latest ON gates (id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gates_status    ON gates (status);
CREATE INDEX IF NOT EXISTS idx_gates_country   ON gates (country_code);

-- ----------------------------------------------------------------------------
-- 3.2 platforms
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS platforms (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,
  base_url        TEXT,
  headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  cert_subject    TEXT,
  cert_serial     TEXT,
  status          gate_status  NOT NULL DEFAULT 'ONLINE',
  created_by      UUID,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  platforms IS 'Registry of eFTI platforms registered with this gate. Append-only: each edit is a new row sharing the same id; latest wins.';
COMMENT ON COLUMN platforms.row_id              IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN platforms.id                  IS 'Logical platform identifier (e.g. plt-xxx-001). Many rows can share this id over time.';
COMMENT ON COLUMN platforms.base_url            IS 'Platform''s REST API base URL';
COMMENT ON COLUMN platforms.headers             IS 'Custom headers (e.g. API key) the gate sends with platform requests';
COMMENT ON COLUMN platforms.e_delivery_cert     IS 'Public certificate (PEM) for AS4 communication with this platform';
COMMENT ON COLUMN platforms.tls_cert            IS 'Public TLS certificate (PEM) for HTTPS communication';
COMMENT ON COLUMN platforms.cert_subject        IS 'Subject DN of the platform''s eDelivery AP X.509 certificate. Used for inbound mTLS lookup.';
COMMENT ON COLUMN platforms.cert_serial         IS 'Serial number of the eDelivery AP certificate. Together with cert_subject forms the natural key for inbound-mTLS lookup.';
COMMENT ON COLUMN platforms.status              IS 'Current platform operational status: ONLINE — active and available; OFFLINE — ping failed; DISABLED — administratively disabled (visible in list); DELETED — soft deletion (removed by operator, row retained for audit).';
COMMENT ON COLUMN platforms.created_by          IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN platforms.created_at          IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_platforms_id_latest   ON platforms (id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_platforms_status      ON platforms (status);
CREATE INDEX IF NOT EXISTS idx_platforms_cert_lookup ON platforms (cert_subject, cert_serial);

-- ----------------------------------------------------------------------------
-- 3.3 authorities
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS authorities (
  row_id        UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id            CITEXT       NOT NULL,
  name          TEXT         NOT NULL,
  registry_code TEXT         NOT NULL,
  description   TEXT,
  subsets       TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
  is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by    UUID,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  authorities IS 'Registry of competent authorities. Append-only: each edit is a new row sharing the same id; latest wins.';
COMMENT ON COLUMN authorities.row_id        IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN authorities.id            IS 'Logical authority identifier (e.g. auth-mta). Many rows over time.';
COMMENT ON COLUMN authorities.name          IS 'Human-readable name (e.g. "Estonian Tax and Customs Board")';
COMMENT ON COLUMN authorities.registry_code IS 'Estonian Business Registry code of the authority, used for access control from';
COMMENT ON COLUMN authorities.description   IS 'Free-form description; null when unset';
COMMENT ON COLUMN authorities.subsets       IS 'eFTI subsets this authority is permitted to request. Constrained to EU01..EU07.';
COMMENT ON COLUMN authorities.is_active     IS 'Logical-deletion flag';
COMMENT ON COLUMN authorities.created_by    IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN authorities.created_at    IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_authorities_id_latest ON authorities (id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authorities_registry_code   ON authorities (registry_code);
CREATE INDEX IF NOT EXISTS idx_authorities_active    ON authorities (is_active) WHERE is_active = TRUE;

-- ----------------------------------------------------------------------------
-- 3.4 users
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
  row_id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id                UUID         NOT NULL,
  tara_sub          TEXT         NOT NULL,
  name              TEXT         NOT NULL,
  secret_hash       TEXT,
  token_revoked_at  TIMESTAMPTZ,
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by        UUID,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  users IS 'Human admin users of the gate. Append-only: every role/subset change INSERTs a new row with the same id. Primary auth is TARA OIDC; gate matches JWT `sub` against `tara_sub`.';
COMMENT ON COLUMN users.row_id            IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN users.id                IS 'Logical user identifier (UUID). Many rows over time; latest wins.';
COMMENT ON COLUMN users.tara_sub          IS 'JWT `sub` value the gate matches against. For TARA JWTs this is the Estonian PIC. For break-glass local-admin the reserved literal ''local-admin''.';
COMMENT ON COLUMN users.name              IS 'Display name';
COMMENT ON COLUMN users.secret_hash       IS 'bcrypt hash of the break-glass local-admin password. NULL for normal users.';
COMMENT ON COLUMN users.token_revoked_at  IS 'Per-user broadcast revocation marker. NULL means no broadcast revocation has occurred.';
COMMENT ON COLUMN users.is_active         IS 'Logical-deletion flag';
COMMENT ON COLUMN users.created_by        IS 'users.row_id of the actor that wrote this row.';
COMMENT ON COLUMN users.created_at        IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_users_id_latest    ON users (id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_tara_sub     ON users (tara_sub, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_active       ON users (is_active) WHERE is_active = TRUE;

-- ----------------------------------------------------------------------------
-- 3.5 consignments
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS consignments (
  row_id                        UUID                PRIMARY KEY DEFAULT uuid_generate_v4(),
  dataset_id                    UUID                NOT NULL,
  platform_id                   CITEXT              NOT NULL,
  gate_id                       CITEXT              NOT NULL,
  xml                           TEXT                NOT NULL,
  status                        consignment_status  NOT NULL DEFAULT 'ACTIVE',
  transport_mode                CHAR(1),
  acceptance_date               TIMESTAMPTZ,
  acceptance_country            CHAR(2),
  delivery_date                 TIMESTAMPTZ,
  delivery_country              CHAR(2),
  dangerous_goods               VARCHAR(2),
  main_transport_id             TEXT,
  main_transport_type           TEXT,
  transport_reg_country         CHAR(2),
  loading_date                  TIMESTAMPTZ,
  loading_country               CHAR(2),
  unloading_date                TIMESTAMPTZ,
  unloading_country             CHAR(2),
  used_equipment_ids            TEXT[],
  used_equipment_categories     TEXT[],
  used_equipment_countries      TEXT[],
  used_equipment_seq            TEXT[],
  carried_equipment_ids         TEXT[],
  carried_equipment_categories  TEXT[],
  carried_equipment_seq         TEXT[],
  created_at                    TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

  CONSTRAINT consignments_acceptance_country_fmt    CHECK (acceptance_country    IS NULL OR acceptance_country    ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_delivery_country_fmt      CHECK (delivery_country      IS NULL OR delivery_country      ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_transport_reg_country_fmt CHECK (transport_reg_country IS NULL OR transport_reg_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_loading_country_fmt       CHECK (loading_country       IS NULL OR loading_country       ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_unloading_country_fmt     CHECK (unloading_country     IS NULL OR unloading_country     ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  consignments IS 'Stored consignment identifier metadata. Append-only: re-uploads and status flips all INSERT new rows with the same dataset_id.';
COMMENT ON COLUMN consignments.row_id                        IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN consignments.dataset_id                    IS 'Logical dataset UUID assigned by the platform; many rows over time. Latest by created_at is current.';
COMMENT ON COLUMN consignments.platform_id                   IS 'Logical platform.id at row-write time. Denormalised.';
COMMENT ON COLUMN consignments.gate_id                       IS 'Logical gate.id at row-write time. Denormalised.';
COMMENT ON COLUMN consignments.xml                           IS 'Identifier XML payload as received from the platform';
COMMENT ON COLUMN consignments.status                        IS 'Lifecycle status snapshot at row-write time';
COMMENT ON COLUMN consignments.transport_mode                IS 'Transport mode extracted from XML';
COMMENT ON COLUMN consignments.acceptance_date               IS 'Carrier acceptance date from XML';
COMMENT ON COLUMN consignments.acceptance_country            IS 'Carrier acceptance country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.delivery_date                 IS 'Delivery date from XML';
COMMENT ON COLUMN consignments.delivery_country              IS 'Delivery country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.dangerous_goods               IS 'Dangerous goods indication code from XML';
COMMENT ON COLUMN consignments.main_transport_id             IS 'Main carriage transport means ID from XML';
COMMENT ON COLUMN consignments.main_transport_type           IS 'Main carriage transport means type code from XML';
COMMENT ON COLUMN consignments.transport_reg_country         IS 'Transport means registration country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.loading_date                  IS 'Main carriage loading date from XML';
COMMENT ON COLUMN consignments.loading_country               IS 'Main carriage loading country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.unloading_date                IS 'Main carriage unloading date from XML';
COMMENT ON COLUMN consignments.unloading_country             IS 'Main carriage unloading country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.used_equipment_ids            IS 'Used transport equipment IDs from XML';
COMMENT ON COLUMN consignments.used_equipment_categories     IS 'Used transport equipment category codes from XML';
COMMENT ON COLUMN consignments.used_equipment_countries      IS 'Used transport equipment registration countries from XML';
COMMENT ON COLUMN consignments.used_equipment_seq            IS 'Used transport equipment sequence numbers from XML';
COMMENT ON COLUMN consignments.carried_equipment_ids         IS 'Carried transport equipment IDs from XML';
COMMENT ON COLUMN consignments.carried_equipment_categories  IS 'Carried transport equipment category codes from XML';
COMMENT ON COLUMN consignments.carried_equipment_seq         IS 'Carried transport equipment sequence numbers from XML';
COMMENT ON COLUMN consignments.created_at                    IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_consignments_dataset_latest    ON consignments (dataset_id, platform_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_consignments_platform          ON consignments (platform_id);
CREATE INDEX IF NOT EXISTS idx_consignments_status_active     ON consignments (status) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_consignments_transport_mode    ON consignments (transport_mode);
CREATE INDEX IF NOT EXISTS idx_consignments_acceptance_date   ON consignments (acceptance_date);
CREATE INDEX IF NOT EXISTS idx_consignments_acceptance_country ON consignments (acceptance_country);
CREATE INDEX IF NOT EXISTS idx_consignments_delivery_date     ON consignments (delivery_date);
CREATE INDEX IF NOT EXISTS idx_consignments_delivery_country  ON consignments (delivery_country);
CREATE INDEX IF NOT EXISTS idx_consignments_dangerous_goods   ON consignments (dangerous_goods);
CREATE INDEX IF NOT EXISTS idx_consignments_main_transport_id ON consignments (main_transport_id);
CREATE INDEX IF NOT EXISTS idx_consignments_main_transport_type ON consignments (main_transport_type);
CREATE INDEX IF NOT EXISTS idx_consignments_transport_reg_country ON consignments (transport_reg_country);
CREATE INDEX IF NOT EXISTS idx_consignments_loading_date      ON consignments (loading_date);
CREATE INDEX IF NOT EXISTS idx_consignments_loading_country   ON consignments (loading_country);
CREATE INDEX IF NOT EXISTS idx_consignments_unloading_date    ON consignments (unloading_date);
CREATE INDEX IF NOT EXISTS idx_consignments_unloading_country ON consignments (unloading_country);
CREATE INDEX IF NOT EXISTS idx_consignments_used_equip_ids    ON consignments USING gin (used_equipment_ids);
CREATE INDEX IF NOT EXISTS idx_consignments_used_equip_cat    ON consignments USING gin (used_equipment_categories);
CREATE INDEX IF NOT EXISTS idx_consignments_used_equip_ctry   ON consignments USING gin (used_equipment_countries);
CREATE INDEX IF NOT EXISTS idx_consignments_used_equip_seq    ON consignments USING gin (used_equipment_seq);
CREATE INDEX IF NOT EXISTS idx_consignments_carried_equip_ids ON consignments USING gin (carried_equipment_ids);
CREATE INDEX IF NOT EXISTS idx_consignments_carried_equip_cat ON consignments USING gin (carried_equipment_categories);
CREATE INDEX IF NOT EXISTS idx_consignments_carried_equip_seq ON consignments USING gin (carried_equipment_seq);

-- ============================================================================
-- 4. EPHEMERAL / EVENT-LOG TABLES
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 4.1 request_id_cache
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS request_id_cache (
  request_id  VARCHAR(100) PRIMARY KEY,
  seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '10 minutes'
);

COMMENT ON TABLE  request_id_cache IS 'Short-lived cache of seen X-Request-ID values for duplicate-request detection. Cron-archived by CronManager when expires_at < NOW().';
COMMENT ON COLUMN request_id_cache.request_id IS 'X-Request-ID value (UUID string). Primary key.';
COMMENT ON COLUMN request_id_cache.seen_at    IS 'When the request was first seen';
COMMENT ON COLUMN request_id_cache.expires_at IS 'After this timestamp the row is eligible for archival (default seen_at + 10 min)';

CREATE INDEX IF NOT EXISTS idx_request_id_cache_expires ON request_id_cache (expires_at);

-- ----------------------------------------------------------------------------
-- 4.2 sessions — JWT denylist
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sessions (
  row_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID         NOT NULL,
  jti         VARCHAR(255) NOT NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  revoked_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  reason      VARCHAR(64),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  sessions IS 'JWT denylist. INSERT-only. Holds (jti, revoked_at) entries for tokens explicitly revoked before TARA-side exp.';
COMMENT ON COLUMN sessions.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN sessions.user_id     IS 'Logical users.id of the JWT subject at revocation time.';
COMMENT ON COLUMN sessions.jti         IS 'JWT ID claim (RFC 7519 §4.1.7). Not unique: duplicates from multiple revoke calls on the same token are tolerated.';
COMMENT ON COLUMN sessions.expires_at  IS 'Copied from the JWT exp claim. Once exp < NOW() the row becomes an archival candidate.';
COMMENT ON COLUMN sessions.revoked_at  IS 'When the revocation event was recorded.';
COMMENT ON COLUMN sessions.reason      IS 'Revocation reason: ''logout'', ''admin_revoke'', ''incident'', …';
COMMENT ON COLUMN sessions.created_at  IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_sessions_jti        ON sessions (jti);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id    ON sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions (expires_at);

-- ----------------------------------------------------------------------------
-- 4.3 jobs_execution_log
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS jobs_execution_log (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  job_name     VARCHAR(100) NOT NULL,
  started_at   TIMESTAMPTZ  NOT NULL,
  finished_at  TIMESTAMPTZ  NOT NULL,
  status       job_status   NOT NULL,
  details      JSONB,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  jobs_execution_log IS 'Final report of each scheduled job execution. INSERT-only at completion (or failure). No running-state row.';
COMMENT ON COLUMN jobs_execution_log.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN jobs_execution_log.job_name    IS 'Logical job name (e.g. "ping-job", "expiry-job")';
COMMENT ON COLUMN jobs_execution_log.started_at  IS 'When the job execution actually began';
COMMENT ON COLUMN jobs_execution_log.finished_at IS 'When the job execution completed or failed';
COMMENT ON COLUMN jobs_execution_log.status      IS 'completed or failed';
COMMENT ON COLUMN jobs_execution_log.details     IS 'JSON details about the run (e.g. {"expired_count":14,"errors":[]})';
COMMENT ON COLUMN jobs_execution_log.created_at  IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_jobs_log_job_name   ON jobs_execution_log (job_name, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_log_started_at ON jobs_execution_log (started_at DESC);

-- ----------------------------------------------------------------------------
-- 4.4 follow_up_log
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS follow_up_log (
  row_id                  UUID             PRIMARY KEY DEFAULT uuid_generate_v4(),
  follow_up_id            UUID             NOT NULL,
  requesting_gate_id      CITEXT           NOT NULL,
  requesting_user_id      UUID,
  dataset_request_id      UUID             NOT NULL,
  destination_gate_id     CITEXT           NOT NULL,
  destination_platform_id CITEXT           NOT NULL,
  payload                 TEXT             NOT NULL,
  status                  follow_up_status NOT NULL,
  failure_reason          TEXT,
  received_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  follow_up_log IS 'Log of follow-up messages received by the AAP and forwarded to the destination platform/gate. Mandatory fields per Art 6(2)(c) Reg 2024/1942. INSERT-only.';
COMMENT ON COLUMN follow_up_log.row_id                  IS 'Synthetic primary key';
COMMENT ON COLUMN follow_up_log.follow_up_id            IS 'Unique identifier of the follow-up message (UUIDv4). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_gate_id      IS 'Gate that originated the follow-up (AAP). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_user_id      IS 'Logical users.id of the authority user (NULL for G2G follow-ups).';
COMMENT ON COLUMN follow_up_log.dataset_request_id      IS 'Dataset request being followed up on';
COMMENT ON COLUMN follow_up_log.destination_gate_id     IS 'Gate that owns the target platform';
COMMENT ON COLUMN follow_up_log.destination_platform_id IS 'Platform the follow-up was delivered to';
COMMENT ON COLUMN follow_up_log.payload                 IS 'Follow-up message body, forwarded unchanged';
COMMENT ON COLUMN follow_up_log.status                  IS 'delivered or failed';
COMMENT ON COLUMN follow_up_log.failure_reason          IS 'Error description when status=failed';
COMMENT ON COLUMN follow_up_log.received_at             IS 'Mandatory per Art 6(2)(c): when the follow-up was received by the AAP';
COMMENT ON COLUMN follow_up_log.created_at              IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_follow_up_log_received   ON follow_up_log (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_follow_up_log_dataset    ON follow_up_log (dataset_request_id);
CREATE INDEX IF NOT EXISTS idx_follow_up_log_requesting ON follow_up_log (requesting_gate_id, received_at DESC);

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

-- ----------------------------------------------------------------------------
-- 4.6 async_responses
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS async_responses (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  receiver_id  CITEXT       NOT NULL,
  request_id   TEXT         NOT NULL,
  body         TEXT         NOT NULL,
  consumed_at  TIMESTAMPTZ,
  consumed_by  UUID,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  async_responses IS 'Multi-node coordination for incoming eDelivery AS4 async responses. Append-only on storage (consumed_at=NULL rows). Consumption is a NEW row with consumed_at populated.';
COMMENT ON COLUMN async_responses.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN async_responses.receiver_id IS 'Gate or platform that should receive this response';
COMMENT ON COLUMN async_responses.request_id  IS 'Correlation ID matching the original outgoing request';
COMMENT ON COLUMN async_responses.body        IS 'Raw response payload (XML)';
COMMENT ON COLUMN async_responses.consumed_at IS 'When the response was consumed. NULL = pending pickup.';
COMMENT ON COLUMN async_responses.consumed_by IS 'Gate node identity that claimed this response. NULL on storage rows.';
COMMENT ON COLUMN async_responses.created_at  IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_async_responses_pending ON async_responses (receiver_id, request_id, created_at DESC) WHERE consumed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_async_responses_created ON async_responses (created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_async_responses_claim ON async_responses (receiver_id, request_id) WHERE consumed_at IS NOT NULL;

-- ============================================================================
-- 5. DATABASE ROLES + GRANTS
-- ============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app') THEN
    CREATE USER app WITH PASSWORD 'app-secret';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'db_archiver') THEN
    CREATE USER db_archiver WITH PASSWORD 'archiver-secret';
  END IF;
END;
$$;

GRANT USAGE ON SCHEMA public TO app;
GRANT USAGE ON SCHEMA public TO db_archiver;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES    TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE        ON FUNCTIONS TO app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, DELETE ON TABLES    TO db_archiver;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO db_archiver;

GRANT SELECT, INSERT ON
  gates, platforms, authorities, users, consignments,
  request_id_cache, sessions, jobs_execution_log,
  follow_up_log, audit_log, async_responses
  TO app;

GRANT SELECT, DELETE ON
  gates, platforms, authorities, users, consignments,
  request_id_cache, sessions, jobs_execution_log,
  follow_up_log, async_responses
  TO db_archiver;

GRANT SELECT ON audit_log TO db_archiver;
