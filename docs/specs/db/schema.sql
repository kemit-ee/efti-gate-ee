-- ============================================================================
-- eFTI Gate (EE) — Complete PostgreSQL Database Schema
-- PostgreSQL 14+
-- Author: KeMIT (Estonia)
-- Last revised: 2026-08-28
--
-- Setup:
--   createdb efti
--   psql -U postgres -d efti -f schema.sql
--
-- Migration policy:
--   This file is the v0 baseline. Apply once against an empty database; do not
--   re-run on a populated cluster. All subsequent schema changes go through
--   Liquibase changesets in `gate/db/changelog/`. No ad-hoc DDL.
--
-- ============================================================================
-- DESIGN RULE — APPEND-ONLY EVERYWHERE
-- ============================================================================
-- Every operational table is INSERT-only. No UPDATE, no DELETE — anywhere.
-- "Editing" an entity means INSERTing a new row with the same logical
-- identifier; the latest row (by created_at) is the current state. The
-- runtime `app` role has SELECT + INSERT only; no UPDATE, no DELETE on any
-- table. There are no UPDATE triggers, no per-column "_history" / "_log"
-- companion tables — the operational table itself is its own change log.
--
-- Each table carries:
--   row_id     UUID  primary key (auto, unique per row)
--   <logical>  the previous "primary key" — now a non-unique logical
--              identifier shared by all rows belonging to one entity
--              (e.g. gates.id, users.id, consignments.dataset_id)
--   created_at TIMESTAMPTZ — when this row was written
--   created_by UUID — denormalised users.row_id of the actor (NULL for
--              system events)
--
-- Reads use the DISTINCT ON (latest-row-per-logical-id) pattern:
--   SELECT DISTINCT ON (id) *
--     FROM gates
--     ORDER BY id, created_at DESC
-- Indexed by (logical_id, created_at DESC) for fast latest lookup. The
-- reads are still single-table — the no-JOIN rule holds.
--
-- Foreign-key constraints are not used between operational tables, because
-- their logical-id columns are not unique. Cross-references are
-- denormalised by id (CITEXT or UUID strings); referential integrity is
-- enforced at the application layer.
--
-- Archival of non-latest rows is performed by **CronManager**
-- (https://github.com/Buerostack/CronManager) — a separate Quartz-based
-- scheduler service deployed alongside the gate. CronManager runs an HTTP
-- job (cron-configured, e.g. daily at 03:30) that calls a gate admin
-- endpoint which moves all non-latest rows of every operational table
-- into archival storage. The live database thus stays lean while full
-- history is preserved off-line. See `docs/specs/non-functional.md` and
-- Epic 26 for the contract.
-- ============================================================================

-- ============================================================================
-- 0. EXTENSIONS
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- UUID generation (uuid_generate_v4)
CREATE EXTENSION IF NOT EXISTS "citext";      -- Case-insensitive text (gate/platform IDs)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";     -- Trigram fuzzy search for license plates
CREATE EXTENSION IF NOT EXISTS "btree_gin";   -- GIN indexes on scalar types

-- ============================================================================
-- 1. CUSTOM TYPES (ENUMs)
-- ============================================================================

CREATE TYPE gate_status AS ENUM (
  'ONLINE',    -- Gate/platform is reachable and accepting queries
  'OFFLINE',   -- Gate/platform is unreachable (failed ping)
  'DISABLED',  -- Gate/platform manually deactivated by administrator (visible in list)
  'DELETED'    -- Soft-deleted by operator; hidden from list queries, row retained for audit
);
COMMENT ON TYPE gate_status IS 'Operational status of an eFTI gate or platform node';

CREATE TYPE consignment_status AS ENUM (
  'active',    -- Registered and queryable by authorities
  'inactive',  -- Cabotage retention elapsed (mode=road, +14 days)
  'deleted'    -- Logical deletion by platform or admin
);
COMMENT ON TYPE consignment_status IS 'Lifecycle status of a stored consignment record. State transitions happen by INSERTing a new consignments row with the new status; the latest row wins.';

CREATE TYPE job_status AS ENUM (
  'completed',
  'failed'
);
COMMENT ON TYPE job_status IS 'Final outcome of a scheduled job execution. jobs_execution_log is INSERT-only at job completion — there is no "running" record.';

CREATE TYPE follow_up_status AS ENUM (
  'delivered',  -- Forwarded to destination platform/gate successfully
  'failed'      -- Destination unreachable or rejected the message
);
COMMENT ON TYPE follow_up_status IS 'Outcome of a follow-up message forwarding attempt';

-- ============================================================================
-- 2. HELPER — record actor (denormalised users.row_id) on each INSERT
-- ============================================================================
-- Set per-session via the application layer:
--   SELECT set_app_user('<users.row_id UUID>')
-- Read inside an INSERT to fill row.created_by:
--   INSERT INTO ... (created_by, ...) VALUES (get_app_user(), ...)

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
-- 3.1 gates — registered eFTI gates (own + remote peers)
-- ----------------------------------------------------------------------------
-- Each registry change (status flip, ping result, e-delivery URL update,
-- certificate rotation) is a NEW row sharing the same `id`. Latest row wins.

CREATE TABLE gates (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,        -- logical identifier (e.g. eu-xx01); NOT unique
  country_code    CHAR(2)      NOT NULL,
  e_delivery_url  TEXT,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  status          gate_status  NOT NULL,
  last_ping_at    TIMESTAMPTZ,
  created_by      UUID,                          -- denormalised users.row_id of actor (NULL for ping job)
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT gates_id_format CHECK (id ~ '^eu-[a-z]{2}[0-9]{2}$'),
  CONSTRAINT gates_country_format CHECK (country_code ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  gates IS 'Registry of eFTI gates (own + remote peers). Append-only: each registry change (status flip, ping, URL/cert update) is a new row with the same id. The latest row by created_at is the gate''s current state. Cron-archived by CronManager — see Epic 26.';
COMMENT ON COLUMN gates.row_id          IS 'Synthetic primary key, unique per row (one entity has many rows over time)';
COMMENT ON COLUMN gates.id              IS 'Logical gate identifier in eu-{cc}{nn} format (e.g. eu-xx01). Many rows can share this id over time; latest wins.';
COMMENT ON COLUMN gates.country_code    IS 'ISO 3166-1 alpha-2 country code';
COMMENT ON COLUMN gates.e_delivery_url  IS 'AS4 access-point URL for inbound G2G messages';
COMMENT ON COLUMN gates.e_delivery_cert IS 'Public certificate (PEM) used to verify AS4 messages from this gate';
COMMENT ON COLUMN gates.tls_cert        IS 'Public TLS certificate (PEM) used to verify the gate''s HTTPS endpoint';
COMMENT ON COLUMN gates.status          IS 'Värava tööseisund hetkel: ONLINE — aktiivne ja kättesaadav; OFFLINE — ping ebaõnnestus; DISABLED — halduslikult välja lülitatud (nähtav loendis); DELETED — pehme kustutus (operaatori poolt eemaldatud, rida säilib auditiks).';
COMMENT ON COLUMN gates.last_ping_at    IS 'Timestamp of the latest successful ping that produced this row. NULL if this row pre-dates first ping.';
COMMENT ON COLUMN gates.created_by      IS 'Denormalised users.row_id of the actor that wrote this row. NULL for system events (ping job, registry sync).';
COMMENT ON COLUMN gates.created_at      IS 'When this row was inserted. Latest created_at per id is the current state.';

CREATE INDEX idx_gates_id_latest    ON gates (id, created_at DESC);
CREATE INDEX idx_gates_status       ON gates (status);
CREATE INDEX idx_gates_country      ON gates (country_code);

-- ----------------------------------------------------------------------------
-- 3.2 platforms — registered eFTI platforms
-- ----------------------------------------------------------------------------

CREATE TABLE platforms (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,    -- logical identifier; NOT unique
  base_url        TEXT,
  headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  status          gate_status  NOT NULL DEFAULT 'ONLINE',
  created_by      UUID,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  platforms IS 'Registry of eFTI platforms registered with this gate. Append-only: each edit is a new row sharing the same id; latest wins. status=DELETED represents logical deletion.';
COMMENT ON COLUMN platforms.row_id              IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN platforms.id                  IS 'Logical platform identifier (e.g. plt-xxx-001). Many rows can share this id over time.';
COMMENT ON COLUMN platforms.base_url            IS 'Platform''s REST API base URL';
COMMENT ON COLUMN platforms.headers             IS 'Custom headers (e.g. API key) the gate sends with platform requests';
COMMENT ON COLUMN platforms.e_delivery_cert     IS 'Public certificate (PEM) for AS4 communication with this platform';
COMMENT ON COLUMN platforms.tls_cert            IS 'Public TLS certificate (PEM) for HTTPS communication';
COMMENT ON COLUMN platforms.status IS 'Platvormi tööseisund hetkel: ONLINE — aktiivne ja kättesaadav; OFFLINE — ping ebaõnnestus; DISABLED — halduslikult välja lülitatud (nähtav loendis); DELETED — pehme kustutus (operaatori poolt eemaldatud, rida säilib auditiks).';
COMMENT ON COLUMN platforms.created_by          IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN platforms.created_at          IS 'When this row was inserted';

CREATE INDEX idx_platforms_id_latest    ON platforms (id, created_at DESC);
CREATE INDEX idx_platforms_status       ON platforms (status);

-- ----------------------------------------------------------------------------
-- 3.3 authorities — registered competent authorities
-- ----------------------------------------------------------------------------

CREATE TABLE authorities (
  row_id        UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id            CITEXT       NOT NULL,
  name          TEXT         NOT NULL,
  country_code  CHAR(2)      NOT NULL,
  description   TEXT,
  subsets       TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
  is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by    UUID,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT authorities_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT authorities_subsets_valid  CHECK (subsets <@ ARRAY['EU01','EU02','EU03','EU04','EU05','EU06','EU07']::text[])
);

COMMENT ON TABLE  authorities IS 'Registry of competent authorities. Append-only: each edit is a new row sharing the same id; latest wins. The subsets column declares which eFTI data subsets this authority is permitted to access.';
COMMENT ON COLUMN authorities.row_id        IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN authorities.id            IS 'Logical authority identifier (e.g. auth-mta). Many rows over time.';
COMMENT ON COLUMN authorities.name          IS 'Human-readable name (e.g. "Estonian Tax and Customs Board")';
COMMENT ON COLUMN authorities.country_code  IS 'ISO 3166-1 alpha-2 country code';
COMMENT ON COLUMN authorities.description   IS 'Free-form description; null when unset';
COMMENT ON COLUMN authorities.subsets       IS 'eFTI subsets this authority is permitted to request. Constrained to EU01..EU07.';
COMMENT ON COLUMN authorities.is_active     IS 'Logical-deletion flag';
COMMENT ON COLUMN authorities.created_by    IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN authorities.created_at    IS 'When this row was inserted';

CREATE INDEX idx_authorities_id_latest ON authorities (id, created_at DESC);
CREATE INDEX idx_authorities_country   ON authorities (country_code);
CREATE INDEX idx_authorities_active    ON authorities (is_active) WHERE is_active = TRUE;

-- ----------------------------------------------------------------------------
-- 3.4 users — gate operators, platform users, authority users, gate users
-- ----------------------------------------------------------------------------
-- A logical user is identified by `id` (UUIDv4 generated at creation). Every
-- credential rotation or status flip INSERTs a new row sharing
-- that id. Login picks the latest row per tara_sub; old rows do not authenticate.

CREATE TABLE users (
  row_id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id                UUID         NOT NULL,                 -- logical user identifier; NOT unique
  tara_sub          TEXT         NOT NULL,                 -- the JWT `sub` value the gate matches against; never NULL
  name              TEXT         NOT NULL,
  is_admin          BOOLEAN      NOT NULL DEFAULT FALSE,   -- full admin API access + the JWT authority API
  secret_hash       TEXT,                                  -- bcrypt of break-glass local-admin password. NULL for the typical user (TARA OIDC JWT).
  token_revoked_at  TIMESTAMPTZ,                           -- per-user broadcast revocation marker; see COMMENT for semantics
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by        UUID,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  users IS 'Human users of the gate (gate admins and the single break-glass local-admin row). Append-only: every credential rotation or status flip INSERTs a new row with the same id. Primary auth is TARA OIDC; the gate matches the JWT `sub` claim against `tara_sub` to resolve a JWT to its users row. Platform identity is NOT modelled here (Platform API uses mTLS against platforms.e_delivery_cert); G2G identity is at the AS4 access point.';
COMMENT ON COLUMN users.row_id            IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN users.id                IS 'Logical user identifier (UUID). Many rows over time; latest wins.';
COMMENT ON COLUMN users.tara_sub          IS 'The `sub` value the gate matches against on every JWT validation. For TARA-issued JWTs this is the Estonian PIC. For the single break-glass local-admin row it is the reserved literal ''local-admin'' (lower-case, never collides with a PIC). Never NULL — the lookup path is uniform across TARA and break-glass JWTs.';
COMMENT ON COLUMN users.name              IS 'Display name';
COMMENT ON COLUMN users.is_admin          IS 'TRUE grants full admin API access (gate/platform/authority/user CRUD) and the JWT authority API (dataset search, follow-up, authority-search). Replaces the former roles TEXT[] ''ADMIN'' entry. FALSE means authenticated but no API access beyond GET /api/v1/user. Competent authorities proper authenticate as organisations over X-Road (authorities.registry_code), not as users.';
COMMENT ON COLUMN users.secret_hash       IS 'bcrypt hash of the break-glass local-admin password. NULL for the typical user — primary auth is TARA-issued OIDC JWT (Authority + Admin) or the platform''s eDelivery AP X.509 cert (Platform). Populated only on the single local-root row used during TARA outages and initial bootstrap; the break-glass path is exposed via POST /api/v1/auth/local-token, default-disabled (LOCAL_ADMIN_FALLBACK_ENABLED=false).';
COMMENT ON COLUMN users.token_revoked_at  IS 'Per-user broadcast revocation marker. POST /api/v1/users/{userId}/revoke-token INSERTs a new users row with this column set to NOW(); on JWT validation the gate rejects any presented JWT whose `iat` claim predates the resolved user''s latest token_revoked_at. Distinct from the per-jti `sessions` denylist (which targets a specific JWT, e.g. on POST /api/v1/auth/logout); this column targets all currently-issued JWTs for the user. NULL means no broadcast revocation has occurred.';
COMMENT ON COLUMN users.is_active     IS 'Logical-deletion flag';
COMMENT ON COLUMN users.created_by    IS 'users.row_id of the actor that wrote this row (the admin creating/editing). NULL for self-registration / TARA-on-first-login flows.';
COMMENT ON COLUMN users.created_at    IS 'When this row was inserted';

CREATE INDEX idx_users_id_latest    ON users (id, created_at DESC);
CREATE INDEX idx_users_tara_sub     ON users (tara_sub, created_at DESC);
CREATE INDEX idx_users_active       ON users (is_active) WHERE is_active = TRUE;

-- ----------------------------------------------------------------------------
-- 3.5 consignments — registered freight identifier metadata (the hot table)
-- ----------------------------------------------------------------------------
-- Most-queried table. Append-only: re-registration by the platform, status
-- transitions (active → inactive by IdentifierExpirationJob, → deleted by
-- platform or admin) all INSERT a new row with the same dataset_id; latest
-- wins. Authority searches hit the denormalised columns directly.

CREATE TABLE consignments (
  row_id              UUID                PRIMARY KEY DEFAULT uuid_generate_v4(),
  dataset_id          UUID                NOT NULL,                  -- logical identifier; NOT unique
  platform_id         CITEXT              NOT NULL,
  gate_id             CITEXT              NOT NULL,
  xml                 TEXT                NOT NULL,
  status              consignment_status  NOT NULL DEFAULT 'active',
  -- denormalised search columns (XML extraction; see data-transformations.md §3.1.4)
  transport_mode      CHAR(1),
  acceptance_date     TIMESTAMP,
  acceptance_country  CHAR(2),
  delivery_date       TIMESTAMP,
  delivery_country    CHAR(2),
  dangerous_goods     VARCHAR(2),
  main_transport_id   TEXT,
  main_transport_type TEXT,
  transport_reg_country CHAR(2),
  loading_date        TIMESTAMP,
  loading_country     CHAR(2),
  unloading_date      TIMESTAMP,
  unloading_country   CHAR(2),
  used_equipment_ids        TEXT[],
  used_equipment_categories TEXT[],
  used_equipment_countries  TEXT[],
  used_equipment_seq        TEXT[],
  carried_equipment_ids        TEXT[],
  carried_equipment_categories TEXT[],
  carried_equipment_seq        TEXT[],
  created_by          UUID,
  created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

  CONSTRAINT consignments_acceptance_country_fmt   CHECK (acceptance_country IS NULL   OR acceptance_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_delivery_country_fmt     CHECK (delivery_country IS NULL     OR delivery_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_transport_reg_country_fmt CHECK (transport_reg_country IS NULL OR transport_reg_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_loading_country_fmt      CHECK (loading_country IS NULL      OR loading_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_unloading_country_fmt    CHECK (unloading_country IS NULL    OR unloading_country ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  consignments IS 'Stored consignment identifier metadata. Append-only: re-uploads, status flips (active → inactive → deleted) all INSERT new rows with the same dataset_id. Authority queries SELECT DISTINCT ON (dataset_id) via denormalised search columns — no JOIN required.';
COMMENT ON COLUMN consignments.row_id              IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN consignments.dataset_id          IS 'Logical dataset UUID assigned by the platform; many rows over time. Latest by created_at is current.';
COMMENT ON COLUMN consignments.platform_id         IS 'Logical platform.id at row-write time. Denormalised; the latest platforms row for this id is the current platform.';
COMMENT ON COLUMN consignments.gate_id             IS 'Logical gate.id at row-write time (this gate''s id). Denormalised.';
COMMENT ON COLUMN consignments.xml                 IS 'Identifier XML payload as received from the platform (per consignment-identifier.xsd)';
COMMENT ON COLUMN consignments.status              IS 'Lifecycle status snapshot at row-write time';
COMMENT ON COLUMN consignments.transport_mode      IS 'Transport mode extracted from XML mainCarriageTransportMovement[1]/modeCode';
COMMENT ON COLUMN consignments.acceptance_date      IS 'Carrier acceptance date from XML CarrierAcceptanceDateParameterScope';
COMMENT ON COLUMN consignments.acceptance_country   IS 'Carrier acceptance country from XML CarrierAcceptanceCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.delivery_date        IS 'Delivery date from XML DeliveryDateParameterScope';
COMMENT ON COLUMN consignments.delivery_country     IS 'Delivery country from XML DeliveryCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.dangerous_goods     IS 'Dangerous goods indication code from XML DangerousGoodsIndicationCodeParameterScope';
COMMENT ON COLUMN consignments.main_transport_id    IS 'Main carriage transport means ID from XML MainCarriageTransportMeansIDParameterScope';
COMMENT ON COLUMN consignments.main_transport_type  IS 'Main carriage transport means type code from XML MainCarriageTransportMeansTypeCodeParameterScope';
COMMENT ON COLUMN consignments.transport_reg_country IS 'Transport means registration country from XML TransportMeansRegistrationCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.loading_date         IS 'Main carriage loading date from XML MainCarriageLoadingDateParameterScope';
COMMENT ON COLUMN consignments.loading_country      IS 'Main carriage loading country from XML MainCarriageLoadingCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.unloading_date       IS 'Main carriage unloading date from XML MainCarriageUnloadingDateParameterScope';
COMMENT ON COLUMN consignments.unloading_country    IS 'Main carriage unloading country from XML MainCarriageUnloadingCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.used_equipment_ids         IS 'Used transport equipment IDs from XML UsedTransportEquipmentIDParameterScope';
COMMENT ON COLUMN consignments.used_equipment_categories  IS 'Used transport equipment category codes from XML UsedTransportEquipmentCategoryCodeParameterScope';
COMMENT ON COLUMN consignments.used_equipment_countries   IS 'Used transport equipment registration countries from XML UsedTransportEquipmentRegistrationCountryParameterScope (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.used_equipment_seq         IS 'Used transport equipment sequence numbers from XML UsedTransportEquipmentSequenceNumberParameterScope';
COMMENT ON COLUMN consignments.carried_equipment_ids      IS 'Carried transport equipment IDs from XML CarriedTransportEquipmentIDParameterScope';
COMMENT ON COLUMN consignments.carried_equipment_categories IS 'Carried transport equipment category codes from XML CarriedTransportEquipmentCategoryCodeParameterScope';
COMMENT ON COLUMN consignments.carried_equipment_seq      IS 'Carried transport equipment sequence numbers from XML CarriedTransportEquipmentSequenceNumberParameterScope';
COMMENT ON COLUMN consignments.created_by          IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN consignments.created_at          IS 'When this row was inserted';

CREATE INDEX idx_consignments_dataset_latest  ON consignments (dataset_id, created_at DESC);
CREATE INDEX idx_consignments_platform        ON consignments (platform_id);
CREATE INDEX idx_consignments_gate            ON consignments (gate_id);
CREATE INDEX idx_consignments_status_active   ON consignments (status) WHERE status = 'active';
CREATE INDEX idx_consignments_transport_mode  ON consignments (transport_mode);
CREATE INDEX idx_consignments_acceptance_date   ON consignments (acceptance_date);
CREATE INDEX idx_consignments_acceptance_country ON consignments (acceptance_country);
CREATE INDEX idx_consignments_delivery_date     ON consignments (delivery_date);
CREATE INDEX idx_consignments_delivery_country  ON consignments (delivery_country);
CREATE INDEX idx_consignments_dangerous_goods ON consignments (dangerous_goods);
CREATE INDEX idx_consignments_main_transport_id ON consignments (main_transport_id);
CREATE INDEX idx_consignments_main_transport_type ON consignments (main_transport_type);
CREATE INDEX idx_consignments_transport_reg_country ON consignments (transport_reg_country);
CREATE INDEX idx_consignments_loading_date      ON consignments (loading_date);
CREATE INDEX idx_consignments_loading_country   ON consignments (loading_country);
CREATE INDEX idx_consignments_unloading_date    ON consignments (unloading_date);
CREATE INDEX idx_consignments_unloading_country ON consignments (unloading_country);
CREATE INDEX idx_consignments_used_equip_ids    ON consignments USING gin (used_equipment_ids);
CREATE INDEX idx_consignments_used_equip_cat    ON consignments USING gin (used_equipment_categories);
CREATE INDEX idx_consignments_used_equip_ctry   ON consignments USING gin (used_equipment_countries);
CREATE INDEX idx_consignments_used_equip_seq    ON consignments USING gin (used_equipment_seq);
CREATE INDEX idx_consignments_carried_equip_ids ON consignments USING gin (carried_equipment_ids);
CREATE INDEX idx_consignments_carried_equip_cat ON consignments USING gin (carried_equipment_categories);
CREATE INDEX idx_consignments_carried_equip_seq ON consignments USING gin (carried_equipment_seq);

-- ============================================================================
-- 4. EPHEMERAL / EVENT-LOG TABLES
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 4.1 request_id_cache — idempotency check, 10-minute window
-- ----------------------------------------------------------------------------

CREATE TABLE request_id_cache (
  request_id  VARCHAR(100) PRIMARY KEY,                 -- the X-Request-ID UUID; insert-once per request
  seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '10 minutes'
);

COMMENT ON TABLE  request_id_cache IS 'Short-lived cache of seen X-Request-ID values for duplicate-request detection. INSERT-once per request_id (the natural uniqueness comes from the X-Request-ID UUID itself). Cron-archived by CronManager when expires_at < NOW().';
COMMENT ON COLUMN request_id_cache.request_id IS 'X-Request-ID value (UUID string). Primary key.';
COMMENT ON COLUMN request_id_cache.seen_at    IS 'When the request was first seen';
COMMENT ON COLUMN request_id_cache.expires_at IS 'After this timestamp the row is eligible for archival (default seen_at + 10 min)';

CREATE INDEX idx_request_id_cache_expires ON request_id_cache (expires_at);

-- ----------------------------------------------------------------------------
-- 4.2 sessions — JWT denylist (revocation list)
-- ----------------------------------------------------------------------------
-- Append-only. Holds revocation entries for TARA-issued JWTs whose `jti` claim
-- has been explicitly revoked before TARA-side `exp`. AccessChecker rejects
-- any presented JWT whose `jti` is in this table AND whose `exp` is still in
-- the future. Old (expired-naturally) entries are archived nightly by
-- CronManager (Epic 26).
--
-- Primary auth is TARA OIDC JWT; this table is consulted only on the
-- denylist-check path. There is no "session created" event — the JWT itself
-- is the session. Only revocation is persisted.

CREATE TABLE sessions (
  row_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID         NOT NULL,                 -- logical users.id (extracted from JWT sub at revocation time)
  jti         VARCHAR(255) NOT NULL,                 -- JWT ID claim (RFC 7519 §4.1.7); logical identifier for the token
  expires_at  TIMESTAMPTZ  NOT NULL,                 -- copied from JWT exp; rows past this are eligible for archival
  revoked_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  reason      VARCHAR(64),                            -- 'logout' | 'admin_revoke' | 'incident' | …
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  sessions IS 'JWT denylist. INSERT-only. Holds (jti, revoked_at) entries for tokens explicitly revoked before TARA-side exp. AccessChecker rejects any JWT whose jti is here AND whose exp is in the future. Rows past expires_at are archived by CronManager. Duplicates on jti are tolerated (idempotency: a second logout on the same JWT is a no-op INSERT, not a 409 — the read pattern is EXISTS WHERE jti=$1 AND expires_at > NOW()).';
COMMENT ON COLUMN sessions.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN sessions.user_id     IS 'Logical users.id of the JWT subject at revocation time. Denormalised, no FK.';
COMMENT ON COLUMN sessions.jti         IS 'JWT ID claim (RFC 7519 §4.1.7) — the logical identifier of the revoked token. Not unique: duplicates from multiple revoke calls on the same token are tolerated.';
COMMENT ON COLUMN sessions.expires_at  IS 'Copied from the JWT exp claim. Once exp < NOW() the row stops being load-bearing and becomes archival candidate.';
COMMENT ON COLUMN sessions.revoked_at  IS 'When the revocation event was recorded.';
COMMENT ON COLUMN sessions.reason      IS 'Revocation reason: ''logout'' (user-initiated), ''admin_revoke'' (POST /api/v1/users/{id}/revoke-token), ''incident'' (operator forced), …';
COMMENT ON COLUMN sessions.created_at  IS 'When this row was inserted (== revoked_at).';

CREATE INDEX idx_sessions_jti          ON sessions (jti);
CREATE INDEX idx_sessions_user_id      ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at   ON sessions (expires_at);

-- ----------------------------------------------------------------------------
-- 4.3 jobs_execution_log — final report from each scheduled job execution
-- ----------------------------------------------------------------------------
-- Each scheduled run produces ONE row at completion (or failure). There is
-- no "running" record. Real-time job-running visibility belongs to logs/
-- monitoring, not this table.

CREATE TABLE jobs_execution_log (
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
COMMENT ON COLUMN jobs_execution_log.job_name    IS 'Logical job name (e.g. "ping-job", "expiry-job", "archival-trigger")';
COMMENT ON COLUMN jobs_execution_log.started_at  IS 'When the job execution actually began';
COMMENT ON COLUMN jobs_execution_log.finished_at IS 'When the job execution completed or failed';
COMMENT ON COLUMN jobs_execution_log.status      IS 'completed or failed';
COMMENT ON COLUMN jobs_execution_log.details     IS 'JSON details about the run (e.g. {"expired_count":14,"errors":[]} )';
COMMENT ON COLUMN jobs_execution_log.created_at  IS 'When this row was inserted (== finished_at on a healthy run)';

CREATE INDEX idx_jobs_log_job_name   ON jobs_execution_log (job_name, started_at DESC);
CREATE INDEX idx_jobs_log_started_at ON jobs_execution_log (started_at DESC);

-- ----------------------------------------------------------------------------
-- 4.4 follow_up_log — Art 6(2)(c) Reg 2024/1942 mandatory follow-up record
-- ----------------------------------------------------------------------------

CREATE TABLE follow_up_log (
  row_id                  UUID             PRIMARY KEY DEFAULT uuid_generate_v4(),
  follow_up_id            UUID             NOT NULL,                 -- logical id of the follow-up message
  requesting_gate_id      CITEXT           NOT NULL,
  requesting_user_id      UUID,                                       -- logical users.id (denormalised, no FK)
  dataset_request_id      UUID             NOT NULL,
  destination_gate_id     CITEXT           NOT NULL,
  destination_platform_id CITEXT           NOT NULL,
  payload                 TEXT             NOT NULL,
  status                  follow_up_status NOT NULL,
  failure_reason          TEXT,
  received_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  follow_up_log IS 'Log of follow-up messages received by the AAP and forwarded to the destination platform/gate. Mandatory fields per Art 6(2)(c) Reg 2024/1942: follow_up_id, requesting_gate_id, received_at. INSERT-only.';
COMMENT ON COLUMN follow_up_log.row_id                  IS 'Synthetic primary key';
COMMENT ON COLUMN follow_up_log.follow_up_id            IS 'Unique identifier of the follow-up message (UUIDv4). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_gate_id      IS 'Gate that originated the follow-up (AAP). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_user_id      IS 'Logical users.id of the authority user (NULL for G2G follow-ups without user attribution)';
COMMENT ON COLUMN follow_up_log.dataset_request_id      IS 'Dataset request being followed up on';
COMMENT ON COLUMN follow_up_log.destination_gate_id     IS 'Gate that owns the target platform';
COMMENT ON COLUMN follow_up_log.destination_platform_id IS 'Platform the follow-up was delivered to';
COMMENT ON COLUMN follow_up_log.payload                 IS 'Follow-up message body, forwarded unchanged';
COMMENT ON COLUMN follow_up_log.status                  IS 'delivered or failed';
COMMENT ON COLUMN follow_up_log.failure_reason          IS 'Error description when status=failed';
COMMENT ON COLUMN follow_up_log.received_at             IS 'Mandatory per Art 6(2)(c): when the follow-up was received by the AAP';
COMMENT ON COLUMN follow_up_log.created_at              IS 'When this row was inserted';

CREATE INDEX idx_follow_up_log_received   ON follow_up_log (received_at DESC);
CREATE INDEX idx_follow_up_log_dataset    ON follow_up_log (dataset_request_id);
CREATE INDEX idx_follow_up_log_requesting ON follow_up_log (requesting_gate_id, received_at DESC);

-- ----------------------------------------------------------------------------
-- 4.5 audit_log — high-level user-action audit trail
-- ----------------------------------------------------------------------------

CREATE TABLE audit_log (
  row_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID,                                       -- logical users.id; NULL for system / failed-login events
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
COMMENT ON COLUMN audit_log.action      IS 'Action verb (login, login_failed, search_identifiers, request_dataset, send_follow_up, register_platform, etc.)';
COMMENT ON COLUMN audit_log.resource    IS 'Resource type touched (dataset, identifier, platform, gate, user, …)';
COMMENT ON COLUMN audit_log.resource_id IS 'Identifier of the resource (UUID, plate string, gate ID, …); NULL for collection-level actions';
COMMENT ON COLUMN audit_log.ip_address  IS 'Source IP address (INET supports v4 and v6)';
COMMENT ON COLUMN audit_log.details     IS 'Structured context (search filters, query parameters, error reason, …)';
COMMENT ON COLUMN audit_log.recorded_at IS 'When the action happened';
COMMENT ON COLUMN audit_log.created_at  IS 'When this row was inserted (== recorded_at)';

CREATE INDEX idx_audit_log_user_recorded ON audit_log (user_id, recorded_at DESC);
CREATE INDEX idx_audit_log_action        ON audit_log (action, recorded_at DESC);
CREATE INDEX idx_audit_log_resource      ON audit_log (resource, resource_id);
CREATE INDEX idx_audit_log_recorded      ON audit_log (recorded_at DESC);

-- ----------------------------------------------------------------------------
-- 4.6 async_responses — eDelivery AS4 async response coordination
-- ----------------------------------------------------------------------------
-- Multi-node deployments: a remote gate's async AS4 response may arrive on a
-- different node than the one that sent the request. The receiving node
-- INSERTs the response here (consumed_at NULL); the originating node polls
-- for it. The "claim" — making sure exactly one node consumes each response
-- — is the partial UNIQUE index below: the first node to INSERT a row with
-- consumed_at=NOW() wins; subsequent INSERTs for the same (receiver_id,
-- request_id) hit the unique violation and the would-be claimer falls
-- through to the next pending response. Append-only on the storage side;
-- the unique index applies only to consumption rows.

CREATE TABLE async_responses (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  receiver_id  CITEXT       NOT NULL,
  request_id   TEXT         NOT NULL,
  body         TEXT         NOT NULL,
  consumed_at  TIMESTAMPTZ,                            -- NULL = stored event; non-NULL = consumed event
  consumed_by  UUID,                                   -- gate node identity that claimed the response
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  async_responses IS 'Multi-node coordination for incoming eDelivery AS4 async responses. Append-only on storage (consumed_at=NULL rows). Consumption is a NEW row with the same (receiver_id, request_id), consumed_at populated, and consumed_by set to the claiming node id. The unique partial index `idx_async_responses_claim` ensures exactly one consumption row per (receiver_id, request_id); racing INSERTs from multiple nodes serialise on this index.';
COMMENT ON COLUMN async_responses.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN async_responses.receiver_id IS 'Gate or platform that should receive this response';
COMMENT ON COLUMN async_responses.request_id  IS 'Correlation ID matching the original outgoing request';
COMMENT ON COLUMN async_responses.body        IS 'Raw response payload (XML)';
COMMENT ON COLUMN async_responses.consumed_at IS 'When the response was consumed by its handler. NULL = pending pickup.';
COMMENT ON COLUMN async_responses.consumed_by IS 'Gate node identity (e.g. pod name UUID) that claimed this response. NULL on storage rows; set on consumption rows.';
COMMENT ON COLUMN async_responses.created_at  IS 'When this row was inserted';

CREATE INDEX idx_async_responses_pending  ON async_responses (receiver_id, request_id, created_at DESC) WHERE consumed_at IS NULL;
CREATE INDEX idx_async_responses_created  ON async_responses (created_at);
-- The claim primitive: at most one consumption row per (receiver_id, request_id).
-- Two nodes racing to claim the same pending response: the second INSERT hits
-- this unique violation, the application catches it and moves on to the next
-- pending response.
CREATE UNIQUE INDEX idx_async_responses_claim ON async_responses (receiver_id, request_id) WHERE consumed_at IS NOT NULL;

-- ============================================================================
-- 5. DATABASE ROLES + GRANTS
-- ============================================================================
-- Two roles, two responsibilities:
--
--   `app`         — runtime gate process. SELECT + INSERT on every operational
--                   table. No UPDATE, no DELETE. Every state transition is an
--                   INSERT. The gate cannot, by grant, mutate or remove rows.
--
--   `db_archiver` — CronManager-driven archival sweep. SELECT + DELETE on
--                   operational tables only. No INSERT, no UPDATE. The gate
--                   process never authenticates as this role; CronManager
--                   calls the gate's `POST /api/v1/admin/archive` endpoint,
--                   and the archival worker behind that endpoint connects
--                   with the `db_archiver` credentials (separate connection
--                   pool, separate Kubernetes Secret). DELETE is granted on
--                   operational tables ONLY — never on `audit_log` (the
--                   audit trail is preserved indefinitely on the live DB
--                   per logging-spec retention policy; copying to cold
--                   storage is out of scope of this role).
--
-- See Epic 26 (Append-Only Archival via CronManager) and
-- `docs/specs/deploy/cronmanager-archive.yaml` for the operational contract.

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

-- Defaults for any future tables added by Liquibase changesets.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES    TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE        ON FUNCTIONS TO app;

-- Same defaults for `db_archiver` so any new operational table added later by
-- Liquibase is automatically sweepable. NOTE: any new audit-style table that
-- must be preserved indefinitely on the live DB (like `audit_log`) MUST be
-- explicitly REVOKEd from db_archiver in the Liquibase changeset that creates
-- it. This is a deliberate trade-off: defaulting db_archiver to "can DELETE"
-- means new tables are archivable by default; the operator opts out per-table.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, DELETE ON TABLES    TO db_archiver;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO db_archiver;

-- Explicit grants for `app` — SELECT + INSERT only on every table. No
-- UPDATE, no DELETE. Period.
GRANT SELECT, INSERT ON
  gates, platforms, authorities, users, consignments,
  request_id_cache, sessions, jobs_execution_log,
  follow_up_log, audit_log, async_responses
  TO app;

-- Explicit grants for `db_archiver` — SELECT + DELETE on operational tables
-- only. NOT granted on `audit_log` (preserved indefinitely on live DB).
-- No INSERT, no UPDATE. The archival worker reads non-latest rows, copies
-- them to archival storage, then DELETEs the same rows in batches.
GRANT SELECT, DELETE ON
  gates, platforms, authorities, users, consignments,
  request_id_cache, sessions, jobs_execution_log,
  follow_up_log, async_responses
  TO db_archiver;
GRANT SELECT ON audit_log TO db_archiver;

-- ============================================================================
-- 6. SEED DATA — OPERATOR MUST REPLACE BEFORE DEPLOYMENT
-- ----------------------------------------------------------------------------
-- The values below are illustrative examples that show the row shape and
-- the relationships between tables. They are NOT prescriptive: every gate
-- id, platform id, authority id, PIC, hostname, and agency name in
-- this section is intended to be replaced by the operator with their real
-- deployment values before this seed runs in any non-throwaway environment.
-- ----------------------------------------------------------------------------
-- DO NOT load this seed block in production. Use it as a template for a
-- per-environment seed file (e.g. docs/specs/db/seed/{dev,test,stage}.sql)
-- whose actual values are owned and reviewed by the operator.
-- ============================================================================

BEGIN;

-- Seed users (these become created_by references for subsequent rows).
-- The break-glass local-admin row carries the reserved literal tara_sub='local-admin' so
-- the JWT validation lookup path is uniform across TARA-issued and gate-issued JWTs.
-- TARA-side users carry their Estonian PIC (literal placeholders below).
INSERT INTO users (id, tara_sub, name, is_admin, secret_hash) VALUES
  ('a0000000-0000-4000-8000-000000000001', 'local-admin',    'Break-glass Local Admin', TRUE,  '$2a$12$REPLACE_WITH_REAL_BCRYPT_HASH_DURING_BOOTSTRAP'),
  ('a0000000-0000-4000-8000-000000000002', 'EE00000000001',  'Multi-Gate Super Admin',  TRUE,  NULL),
  ('a0000000-0000-4000-8000-000000000003', 'EE00000000002',  'MTA Inspector',           TRUE,  NULL),
  ('a0000000-0000-4000-8000-000000000004', 'EE00000000003',  'PPA Border Officer',      TRUE,  NULL);

-- Seed gates
INSERT INTO gates (id, country_code, e_delivery_url, status, last_ping_at) VALUES
  ('eu-xx01', 'EE', 'https://efti.example.com/services/msh',   'ONLINE', NOW()),
  ('eu-zz01', 'FI', 'https://efti-peer.example.com/services/msh',         'ONLINE', NOW()),
  ('eu-yy01', 'DE', 'https://efti-peer.example.com/services/msh',             'ONLINE', NOW()),
  ('eu-yy02', 'LV', 'https://efti-peer2.example.com/services/msh',             'OFFLINE', NULL);

-- Seed platforms
INSERT INTO platforms (id, base_url) VALUES
  ('plt-xxx-001', 'https://platform-demo.example.com/v1'),
  ('plt-yyy-001', 'https://platform-cargo.example.com/v1');

-- Seed authorities
INSERT INTO authorities (id, name, country_code, description, subsets) VALUES
  ('auth-mta',   'Estonian Tax and Customs Board (MTA)', 'EE', 'Customs + tax inspection',           ARRAY['EU01','EU02','EU05']),
  ('auth-ppa',   'Police and Border Guard Board (PPA)',  'EE', 'Border + roadside checks',           ARRAY['EU07']),
  ('auth-vtko', 'Estonian Transport Administration',     'EE', 'Road transport regulation',         ARRAY['EU01','EU03','EU04']),
  ('auth-vet',   'Veterinary and Food Board',            'EE', 'Live-animal and food shipments',     ARRAY['EU06','EU07']),
  ('auth-trafi', 'Traficom (FI)',                        'FI', 'Finnish transport authority (peer)', ARRAY['EU01','EU02','EU03']);

-- Seed sample consignments (a representative spread; full seed lives in seed-data/ outside this baseline)
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status, transport_mode, main_transport_id, acceptance_country, delivery_country) VALUES
  ('550e8400-e29b-41d4-a716-446655440001', 'plt-xxx-001', 'eu-xx01', '<consignment xmlns="http://efti.eu/v1/consignment/identifier"/>', 'active', '1', '123ABC', 'EE', 'FI'),
  ('550e8400-e29b-41d4-a716-446655440002', 'plt-xxx-001', 'eu-xx01', '<consignment xmlns="http://efti.eu/v1/consignment/identifier"/>', 'active', '4', NULL,     'EE', 'NL'),
  ('550e8400-e29b-41d4-a716-446655440003', 'plt-xxx-001', 'eu-xx01', '<consignment xmlns="http://efti.eu/v1/consignment/identifier"/>', 'active', '1', '456XYZ', 'EE', 'LV');

COMMIT;
