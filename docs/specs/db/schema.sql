-- ============================================================================
-- eFTI Gate v2.0 — Complete PostgreSQL Database Schema
-- PostgreSQL 14+
-- Author: Askend Estonia OÜ / KeMIT
-- Date: 2026-04-23
--
-- Setup:
--   createdb efti
--   psql -U postgres -d efti -f schema.sql
--
-- Migration policy:
--   This file is the v0 baseline. Apply once against an empty database; do not
--   re-run on a populated cluster. All subsequent schema changes go through
--   Liquibase changesets in `gate/db/changelog/` (matching the Askend baseline
--   tooling choice). No ad-hoc DDL — every change is a versioned changeset.
--
-- Design notes vs current gate (v1 Liquibase changesets):
--   - Consolidated from separate *.sql changeset files into one executable schema
--   - consignments: added status column, vehicle_plate/country denormalised for fast search
--   - identifiers: added separate id UUID PK; composite PK (id, datasetId) replaced
--   - users: roles column now the sole source of truth; legacy FK columns dropped
--   - gates: status enum replaces ad-hoc text; disabledAt removed (use status=DISABLED)
--   - platforms: headers kept as JSONB; eDeliveryCert/tlsCert renamed for consistency
--   - Added: request_id_cache, sessions, jobs_execution_log tables
--   - Added: follow_up_log (Art 6(2)(c) Reg 2024/1942) and audit_log (action-level)
--   - Added: full COMMENT ON TABLE / COLUMN coverage (GDPR Art 30 traceability)
--   - Added: partial and trigram indexes for high-performance plate searches
--   - Persistence taxonomy (see specs/db/README.md):
--       Ledger    (change_history, audit_log, follow_up_log) — INSERT only;
--                 UPDATE/DELETE rejected by BEFORE trigger raising exception.
--       Ephemeral (request_id_cache, sessions, jobs_execution_log) — INSERT
--                 only by app; aged out by partition rotation under a
--                 maintenance role.
--       Registry  (gates, platforms, authorities, users, consignments,
--                 identifiers) — UPDATE allowed; every UPDATE captured into
--                 change_history. DELETE never granted; logical deletion via
--                 status enums.
--     `app` role: SELECT/INSERT on every table; UPDATE on registry tables only;
--     DELETE on no table. FKs do not cascade.
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
  'ONLINE',    -- Gate is reachable and accepting queries
  'OFFLINE',   -- Gate is unreachable (failed ping)
  'DISABLED'   -- Gate manually deactivated by administrator
);

COMMENT ON TYPE gate_status IS 'Operational status of an eFTI gate node';

CREATE TYPE consignment_status AS ENUM (
  'active',    -- Registered and queryable by authorities
  'inactive',  -- Expired or delivered (14-day ROAD retention elapsed)
  'deleted'    -- Platform explicitly deleted the consignment
);

COMMENT ON TYPE consignment_status IS 'Lifecycle status of a stored consignment identifier record';

CREATE TYPE transport_mode AS ENUM (
  'maritime',    -- Mode code 1: sea transport
  'rail',        -- Mode code 2: railway transport
  'road',        -- Mode code 3: road transport (cabotage rules apply)
  'air',         -- Mode code 4: air cargo
  'multimodal'   -- Mode code 5: combined/multimodal transport
);

COMMENT ON TYPE transport_mode IS 'EU eFTI transport mode classification matching EU Reg 2024/2024 Annex I';

CREATE TYPE identifier_type AS ENUM (
  'means',      -- Means of transport: vehicle registration plate
  'equipment',  -- Transport equipment: container number, trailer
  'carried'     -- Carried transport unit: swap body, loading unit
);

COMMENT ON TYPE identifier_type IS 'Type of consignment identifier as defined in EU eFTI reference model';

CREATE TYPE job_status AS ENUM (
  'running',
  'completed',
  'failed'
);

COMMENT ON TYPE job_status IS 'Execution status of a scheduled background job';

CREATE TYPE follow_up_status AS ENUM (
  'delivered',  -- Successfully forwarded to destination platform/gate
  'failed'      -- Destination unreachable or rejected the message
);

COMMENT ON TYPE follow_up_status IS 'Outcome of a follow-up message forwarding attempt';

-- ============================================================================
-- 2. HELPER FUNCTION — auto-update updated_at
-- ============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = clock_timestamp();
  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION update_updated_at_column() IS
  'Trigger function: automatically sets updated_at to current timestamp on every UPDATE';

-- ============================================================================
-- 3. CHANGE HISTORY SUPPORT
-- ============================================================================

-- Application user context (used by change history trigger to record who changed what)
CREATE OR REPLACE PROCEDURE set_app_user(p_user_id uuid)
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user', p_user_id::text, true);
END;
$$;

COMMENT ON PROCEDURE set_app_user(uuid) IS
  'Sets the current application user ID in the session config for change_history attribution';

CREATE OR REPLACE FUNCTION get_app_user()
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE
  v_user_id varchar;
BEGIN
  v_user_id := current_setting('app.user', true);
  RETURN CASE WHEN v_user_id IS NULL OR v_user_id = '' THEN NULL ELSE v_user_id::uuid END;
END;
$$;

COMMENT ON FUNCTION get_app_user() IS
  'Returns the UUID of the application user set via set_app_user(), or NULL if not set';

-- ============================================================================
-- 4. TABLES
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 4.1 change_history
-- (created before other tables so triggers can reference it immediately)
-- ----------------------------------------------------------------------------

CREATE TABLE change_history (
  "table"     VARCHAR(50)  NOT NULL,
  row_id      VARCHAR(200) NOT NULL,
  "column"    VARCHAR(100) NOT NULL,
  old_value   TEXT,
  new_value   TEXT,
  changed_at  TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
  changed_by  UUID
);

COMMENT ON TABLE change_history IS
  'Append-only field-level audit trail for all registry table changes. '
  'Populated by add_change_history() trigger. Cannot be updated or deleted (RULE enforced).';
COMMENT ON COLUMN change_history."table"  IS 'Name of the table in which the change occurred';
COMMENT ON COLUMN change_history.row_id   IS 'Primary key value of the modified row (as text)';
COMMENT ON COLUMN change_history."column" IS 'Name of the column that changed';
COMMENT ON COLUMN change_history.old_value IS 'Value before the change (NULL for INSERT operations)';
COMMENT ON COLUMN change_history.new_value IS 'Value after the change (NULL for DELETE operations)';
COMMENT ON COLUMN change_history.changed_at IS 'Wall-clock timestamp of the change (clock_timestamp, not transaction time)';
COMMENT ON COLUMN change_history.changed_by IS 'UUID of the user who triggered the change, set via set_app_user(). NULL for system/migration changes.';

CREATE INDEX idx_change_history_table_row ON change_history ("table", row_id);
CREATE INDEX idx_change_history_changed_at ON change_history (changed_at DESC);
CREATE INDEX idx_change_history_changed_by ON change_history (changed_by) WHERE changed_by IS NOT NULL;

-- Immutability: ledger tables are append-only. UPDATE/DELETE attempts raise an
-- exception (BEFORE trigger), regardless of role. Defense-in-depth: also REVOKE
-- UPDATE/DELETE from PUBLIC and grant SELECT, INSERT only to the runtime app role
-- (see section 4.14 grants).
CREATE OR REPLACE FUNCTION reject_modification() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'ledger table % is append-only — % rejected', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'feature_not_supported';
END;
$$;

COMMENT ON FUNCTION reject_modification() IS 'Trigger function for ledger tables (change_history, audit_log, follow_up_log). Raises an exception on UPDATE or DELETE.';

CREATE TRIGGER change_history_no_modify
  BEFORE UPDATE OR DELETE ON change_history
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

REVOKE UPDATE, DELETE ON change_history FROM PUBLIC;

-- Trigger function that records every changed column value
CREATE OR REPLACE FUNCTION add_change_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_pk_col   name := COALESCE(TG_ARGV[0], 'id');  -- pass non-default PK as trigger argument
  v_pk_val   text;
  v_col      name;
  v_old_val  text;
  v_new_val  text;
BEGIN
  EXECUTE format('SELECT ($1.%I)::text', v_pk_col) USING NEW INTO v_pk_val;
  FOR v_col IN (
    SELECT a.attname
    FROM   pg_catalog.pg_attribute a
    JOIN   pg_catalog.pg_class c     ON c.oid = a.attrelid AND c.relname = TG_TABLE_NAME
    JOIN   pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = TG_TABLE_SCHEMA
    WHERE  a.attnum > 0 AND NOT a.attisdropped
  ) LOOP
    EXECUTE format('SELECT ($1.%I)::text', v_col) USING NEW INTO v_new_val;
    EXECUTE format('SELECT ($1.%I)::text', v_col) USING OLD INTO v_old_val;
    CONTINUE WHEN v_old_val IS NOT DISTINCT FROM v_new_val;
    INSERT INTO change_history ("table", row_id, "column", old_value, new_value, changed_by)
    VALUES (TG_TABLE_NAME, v_pk_val, v_col, v_old_val, v_new_val, get_app_user());
  END LOOP;
  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION add_change_history() IS
  'Trigger function: records each changed column as a row in change_history. '
  'Attach AFTER UPDATE on registry tables that need field-level audit. '
  'For tables whose primary key is not named "id", pass the PK column as a trigger '
  'argument: CREATE TRIGGER … EXECUTE FUNCTION add_change_history(''dataset_id'').';

-- ----------------------------------------------------------------------------
-- 4.2 gates
-- ----------------------------------------------------------------------------

CREATE TABLE gates (
  id               CITEXT       PRIMARY KEY,
  country_code     CHAR(2)      NOT NULL,
  e_delivery_url   TEXT         NOT NULL,
  e_delivery_cert  TEXT         NOT NULL,
  tls_cert         TEXT,
  status           gate_status  NOT NULL DEFAULT 'OFFLINE',
  last_ping_at     TIMESTAMPTZ,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT gates_id_format          CHECK (id ~ '^eu-[a-z]{2}[0-9]{2}$'),
  CONSTRAINT gates_country_code_fmt   CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT gates_e_delivery_url_fmt CHECK (e_delivery_url ~ '^https?://')
);

COMMENT ON TABLE  gates IS 'Registry of European eFTI gates for gate-to-gate eDelivery AS4 communication';
COMMENT ON COLUMN gates.id              IS 'Unique gate identifier following EU naming convention (format: eu-{ISO-3166-1-alpha-2}{2-digit number}, e.g. eu-ee31)';
COMMENT ON COLUMN gates.country_code    IS 'ISO 3166-1 alpha-2 country code of the member state operating this gate (e.g. EE, FI, DE)';
COMMENT ON COLUMN gates.e_delivery_url  IS 'eDelivery AS4 Message Service Handler endpoint URL. For fast-protocol gates the path starts with /services/fast.';
COMMENT ON COLUMN gates.e_delivery_cert IS 'X.509 certificate in PEM format used for eDelivery AS4 message signing and signature validation';
COMMENT ON COLUMN gates.tls_cert        IS 'Optional X.509 certificate in PEM format for mutual TLS authentication. NULL if mTLS is not configured.';
COMMENT ON COLUMN gates.status          IS 'Current operational status: ONLINE (accepting queries), OFFLINE (unreachable, failed last ping), DISABLED (manually deactivated by admin)';
COMMENT ON COLUMN gates.last_ping_at    IS 'Timestamp of last successful ping response from this gate. Updated every 5 minutes by GatePingJob. NULL if never successfully pinged.';
COMMENT ON COLUMN gates.created_at      IS 'Timestamp when the gate was first registered in this gate''s registry';
COMMENT ON COLUMN gates.updated_at      IS 'Timestamp of the last update to this gate record. Maintained automatically by trigger.';

CREATE INDEX idx_gates_status      ON gates (status) WHERE status = 'ONLINE';
CREATE INDEX idx_gates_country     ON gates (country_code);

CREATE TRIGGER gates_updated_at
  BEFORE UPDATE ON gates
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER gates_change_history
  AFTER UPDATE ON gates
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- ----------------------------------------------------------------------------
-- 4.3 platforms
-- ----------------------------------------------------------------------------

CREATE TABLE platforms (
  id                   CITEXT   PRIMARY KEY,
  base_url             TEXT     NOT NULL,
  headers              JSONB    NOT NULL DEFAULT '{}',
  e_delivery_cert      TEXT,
  tls_cert             TEXT,
  supports_subsetting  BOOLEAN  NOT NULL DEFAULT TRUE,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT platforms_base_url_fmt CHECK (base_url ~ '^https?://')
);

COMMENT ON TABLE  platforms IS 'Registry of eFTI freight platforms registered to submit consignment identifier data to this gate';
COMMENT ON COLUMN platforms.id                  IS 'Platform-chosen unique identifier (e.g. plt-demo-123). Case-insensitive.';
COMMENT ON COLUMN platforms.base_url            IS 'REST API base URL for dataset retrieval queries from authorities. Must be reachable from the gate.';
COMMENT ON COLUMN platforms.headers             IS 'Additional HTTP headers to include in all requests to this platform (e.g. {"X-Api-Key": "secret"}). Stored as JSONB.';
COMMENT ON COLUMN platforms.e_delivery_cert     IS 'X.509 certificate in PEM format for eDelivery AS4 transport. NULL means platform is REST-only.';
COMMENT ON COLUMN platforms.tls_cert            IS 'Optional certificate for mutual TLS. NULL if mTLS not used.';
COMMENT ON COLUMN platforms.supports_subsetting IS 'If TRUE the platform filters dataset XML by subset itself. If FALSE the gate applies XSLT subsetting before forwarding to the authority.';
COMMENT ON COLUMN platforms.created_at          IS 'Timestamp when the platform was registered';
COMMENT ON COLUMN platforms.updated_at          IS 'Timestamp of last update. Maintained by trigger.';

CREATE TRIGGER platforms_updated_at
  BEFORE UPDATE ON platforms
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER platforms_change_history
  AFTER UPDATE ON platforms
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- ----------------------------------------------------------------------------
-- 4.4 authorities
-- ----------------------------------------------------------------------------

CREATE TABLE authorities (
  id           CITEXT       PRIMARY KEY,
  country_code CHAR(2)      NOT NULL,
  name         TEXT,
  subsets      TEXT[]       NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT authorities_country_code_fmt  CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT authorities_subsets_not_empty CHECK (array_length(subsets, 1) > 0),
  CONSTRAINT authorities_subsets_valid     CHECK (subsets <@ ARRAY['EU01','EU02','EU03','EU04','EU05','EU06','EU07']::text[])
);

COMMENT ON TABLE  authorities IS 'Registry of competent authorities (e.g. customs, police, transport inspectorates) permitted to query eFTI freight data';
COMMENT ON COLUMN authorities.id           IS 'Authority identifier chosen by the gate administrator (e.g. auth-mta, auth-ppa). Case-insensitive.';
COMMENT ON COLUMN authorities.country_code IS 'ISO 3166-1 alpha-2 country code of the member state this authority belongs to';
COMMENT ON COLUMN authorities.name         IS 'Human-readable name of the authority (e.g. "Tax and Customs Board")';
COMMENT ON COLUMN authorities.subsets      IS 'Array of eFTI data subset IDs this authority is legally permitted to access per EU Reg 2024/2024 and national designation. Must be non-empty.';
COMMENT ON COLUMN authorities.created_at   IS 'Timestamp when the authority was registered';
COMMENT ON COLUMN authorities.updated_at   IS 'Timestamp of last update. Maintained by trigger.';

CREATE INDEX idx_authorities_country ON authorities (country_code);

CREATE TRIGGER authorities_updated_at
  BEFORE UPDATE ON authorities
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER authorities_change_history
  AFTER UPDATE ON authorities
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- ----------------------------------------------------------------------------
-- 4.5 users
-- ----------------------------------------------------------------------------

CREATE TABLE users (
  id           UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
  name         TEXT    NOT NULL,
  email        CITEXT  UNIQUE,
  is_admin     BOOLEAN NOT NULL DEFAULT FALSE,
  roles        JSONB   NOT NULL DEFAULT '{}',
  subsets      TEXT[],
  secret_hash  TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by   UUID REFERENCES users (id)
);

COMMENT ON TABLE  users IS 'User accounts for gate administrators, platform service accounts, and authority inspectors';
COMMENT ON COLUMN users.id          IS 'UUID primary key generated by the database';
COMMENT ON COLUMN users.name        IS 'Display name of the user';
COMMENT ON COLUMN users.email       IS 'Email address for email+password login. NULL for API-key-only service accounts. Case-insensitive unique.';
COMMENT ON COLUMN users.is_admin    IS 'Legacy super-admin flag: TRUE means the user has unrestricted administrative access regardless of roles. Will be replaced by SUPER_ADMIN role in v3.';
COMMENT ON COLUMN users.roles       IS 'JSONB map of role names to arrays of party IDs the user can act on. Example: {"GATE":["eu-ee31"],"PLATFORM":["plt-demo-123"],"AUTHORITY":["auth-mta"]}. Empty map {} combined with is_admin=true means SUPER_ADMIN.';
COMMENT ON COLUMN users.subsets     IS 'Optional subset-level restriction for AUTHORITY users. When set, the user can only request these subsets (must be a subset of the authority''s permitted subsets). NULL means no additional restriction.';
COMMENT ON COLUMN users.secret_hash IS 'Hashed API secret or password (SHA-256 base64 for API keys, bcrypt for passwords). NULL for TARA SSO-only users.';
COMMENT ON COLUMN users.created_at  IS 'Timestamp when the user account was created';
COMMENT ON COLUMN users.updated_at  IS 'Timestamp of last update. Maintained by trigger.';
COMMENT ON COLUMN users.created_by  IS 'UUID of the admin user who created this account. NULL for seeded/bootstrapped accounts.';

CREATE INDEX idx_users_email     ON users (email) WHERE email IS NOT NULL;
CREATE INDEX idx_users_roles_gin ON users USING gin (roles);

CREATE TRIGGER users_updated_at
  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER users_change_history
  AFTER UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- ----------------------------------------------------------------------------
-- 4.6 consignments  (most critical table — high query volume)
-- ----------------------------------------------------------------------------

CREATE TABLE consignments (
  dataset_id          UUID              PRIMARY KEY DEFAULT uuid_generate_v4(),
  platform_id         CITEXT            NOT NULL REFERENCES platforms (id),
  gate_id             CITEXT            NOT NULL,
  xml                 TEXT              NOT NULL,
  status              consignment_status NOT NULL DEFAULT 'active',
  mode                transport_mode,
  vehicle_plate       VARCHAR(50),
  vehicle_country     CHAR(2),
  dangerous_goods     BOOLEAN           NOT NULL DEFAULT FALSE,
  origin_country      CHAR(2),
  destination_country CHAR(2),
  transport_date      DATE,
  delivered_at        TIMESTAMPTZ,
  expires_at          TIMESTAMPTZ,
  created_at          TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ       NOT NULL DEFAULT NOW(),

  CONSTRAINT consignments_vehicle_country_fmt   CHECK (vehicle_country IS NULL     OR vehicle_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_origin_country_fmt    CHECK (origin_country IS NULL      OR origin_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_destination_fmt       CHECK (destination_country IS NULL OR destination_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_expires_after_deliver CHECK (expires_at IS NULL OR delivered_at IS NULL OR expires_at > delivered_at)
);

COMMENT ON TABLE  consignments IS 'Stored consignment identifier metadata registered by eFTI platforms. Core query target for authority identifier searches.';
COMMENT ON COLUMN consignments.dataset_id          IS 'UUID assigned by the platform to uniquely identify this consignment dataset. Used in UIL (Unique Identifier Locator) references.';
COMMENT ON COLUMN consignments.platform_id         IS 'FK to platforms.id. No cascade — DELETE is not granted to the runtime app role; logical deletion uses status enums.';
COMMENT ON COLUMN consignments.gate_id             IS 'Gate that owns this consignment record (denormalised for query performance, always this gate''s own ID for locally registered consignments).';
COMMENT ON COLUMN consignments.xml                 IS 'Raw consignment identifier XML body (without <?xml?> declaration). Parsed fields extracted into denormalised columns for fast search. Full XML preserved for authority dataset queries.';
COMMENT ON COLUMN consignments.status              IS 'Lifecycle status. active=queryable, inactive=expired/delivered, deleted=platform-removed.';
COMMENT ON COLUMN consignments.mode                IS 'Transport mode extracted from XML mainCarriageTransportMovement/modeCode. NULL if not specified.';
COMMENT ON COLUMN consignments.vehicle_plate       IS 'Vehicle registration plate extracted from usedTransportMeans/id. Stored for fast indexed search without XML parsing. NULL for equipment/container-only consignments.';
COMMENT ON COLUMN consignments.vehicle_country     IS 'ISO 3166-1 alpha-2 registration country of the vehicle, extracted from registrationCountry/code.';
COMMENT ON COLUMN consignments.dangerous_goods     IS 'TRUE if mainCarriageTransportMovement/dangerousGoodsIndicator=true in the XML.';
COMMENT ON COLUMN consignments.origin_country      IS 'Country of loading/origin extracted from XML location data. May be NULL.';
COMMENT ON COLUMN consignments.destination_country IS 'Country of unloading/destination extracted from XML. May be NULL.';
COMMENT ON COLUMN consignments.transport_date      IS 'Date of carrier acceptance extracted from carrierAcceptanceDateTime. Used for date-range searches.';
COMMENT ON COLUMN consignments.delivered_at        IS 'Timestamp when the consignment was delivered, extracted from deliveryEvent/actualOccurrenceDateTime. NULL while in transit.';
COMMENT ON COLUMN consignments.expires_at          IS 'Calculated expiry timestamp. For road mode: delivered_at + 14 days per EU Reg 2024/1942 Art 11 para 4 (cabotage control retention). Other modes: NULL. IdentifierExpirationJob sets status=inactive when NOW() > expires_at.';
COMMENT ON COLUMN consignments.created_at          IS 'Timestamp when the consignment was first registered at this gate';
COMMENT ON COLUMN consignments.updated_at          IS 'Timestamp of last update (e.g. PUT identifiers call). Maintained by trigger.';

-- Performance-critical indexes for authority search queries
CREATE INDEX idx_consignments_platform_id     ON consignments (platform_id);
CREATE INDEX idx_consignments_gate_id         ON consignments (gate_id);
CREATE INDEX idx_consignments_status_active   ON consignments (status) WHERE status = 'active';
CREATE INDEX idx_consignments_vehicle_plate   ON consignments (vehicle_plate) WHERE vehicle_plate IS NOT NULL;
CREATE INDEX idx_consignments_plate_trgm      ON consignments USING gin (vehicle_plate gin_trgm_ops) WHERE vehicle_plate IS NOT NULL;
CREATE INDEX idx_consignments_mode            ON consignments (mode) WHERE mode IS NOT NULL;
CREATE INDEX idx_consignments_dangerous_goods ON consignments (dangerous_goods) WHERE dangerous_goods = TRUE;
CREATE INDEX idx_consignments_transport_date  ON consignments (transport_date) WHERE transport_date IS NOT NULL;
CREATE INDEX idx_consignments_expires_at      ON consignments (expires_at) WHERE expires_at IS NOT NULL AND status = 'active';
CREATE INDEX idx_consignments_delivered_at    ON consignments (delivered_at) WHERE delivered_at IS NOT NULL;

CREATE TRIGGER consignments_updated_at
  BEFORE UPDATE ON consignments
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- consignments PK is dataset_id (not "id"); pass it as a trigger argument
-- so the parametrised add_change_history() function looks up the right column.
CREATE TRIGGER consignments_change_history
  AFTER UPDATE ON consignments
  FOR EACH ROW EXECUTE FUNCTION add_change_history('dataset_id');

-- ----------------------------------------------------------------------------
-- 4.7 identifiers
-- (individual identifier values extracted from consignments, normalised 1:N)
-- ----------------------------------------------------------------------------

CREATE TABLE identifiers (
  id               UUID             PRIMARY KEY DEFAULT uuid_generate_v4(),
  dataset_id       UUID             NOT NULL REFERENCES consignments (dataset_id),
  identifier_type  identifier_type  NOT NULL,
  identifier_value VARCHAR(200)     NOT NULL,
  country_code     CHAR(2),
  created_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

  CONSTRAINT identifiers_country_code_fmt CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  identifiers IS 'Normalised individual identifier values extracted from consignment XML. One consignment may have multiple identifiers (e.g. vehicle plate + container number).';
COMMENT ON COLUMN identifiers.id               IS 'UUID primary key';
COMMENT ON COLUMN identifiers.dataset_id       IS 'FK to consignments.dataset_id. No cascade — DELETE is not granted to the runtime app role.';
COMMENT ON COLUMN identifiers.identifier_type  IS 'Type of identifier: means (vehicle plate), equipment (container), carried (cargo unit)';
COMMENT ON COLUMN identifiers.identifier_value IS 'The actual identifier string (e.g. "123ABC", "MSCU1234567"). Indexed for exact and fuzzy search.';
COMMENT ON COLUMN identifiers.country_code     IS 'ISO 3166-1 alpha-2 registration country of this identifier, if applicable. NULL for non-country-specific identifiers.';
COMMENT ON COLUMN identifiers.created_at       IS 'Timestamp when the identifier record was created';

CREATE INDEX idx_identifiers_dataset_id    ON identifiers (dataset_id);
CREATE INDEX idx_identifiers_value         ON identifiers (identifier_value);
CREATE INDEX idx_identifiers_value_trgm    ON identifiers USING gin (identifier_value gin_trgm_ops);
CREATE INDEX idx_identifiers_type          ON identifiers (identifier_type);

-- identifiers.id is the default-named UUID PK, so no trigger argument needed.
CREATE TRIGGER identifiers_change_history
  AFTER UPDATE ON identifiers
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- ----------------------------------------------------------------------------
-- 4.8 async_responses
-- (eDelivery AS4 async response coordination for multi-node deployments)
-- ----------------------------------------------------------------------------

CREATE TABLE async_responses (
  receiver_id  CITEXT      NOT NULL,
  request_id   TEXT        NOT NULL,
  body         TEXT        NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  PRIMARY KEY (receiver_id, request_id)
);

COMMENT ON TABLE  async_responses IS 'Stores incoming eDelivery AS4 async responses pending pickup by the originating request handler. Used in multi-node deployments where the response arrives at a different node than the original request.';
COMMENT ON COLUMN async_responses.receiver_id IS 'ID of the gate or platform that is the intended recipient of this response (was the sender of the original request)';
COMMENT ON COLUMN async_responses.request_id  IS 'Correlation ID matching the original outgoing request. Together with receiver_id forms the primary key.';
COMMENT ON COLUMN async_responses.body        IS 'Raw response payload (XML)';
COMMENT ON COLUMN async_responses.created_at  IS 'Timestamp when the response was stored. Used for cleanup of stale entries.';

CREATE INDEX idx_async_responses_created_at ON async_responses (created_at);

-- ----------------------------------------------------------------------------
-- 4.9 request_id_cache
-- (idempotency: duplicate X-Request-ID detection, 10-minute TTL per eFTI protocol)
-- ----------------------------------------------------------------------------

CREATE TABLE request_id_cache (
  request_id  VARCHAR(100) PRIMARY KEY,
  seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '10 minutes'
);

COMMENT ON TABLE  request_id_cache IS 'Short-lived cache of seen X-Request-ID values for duplicate request detection. Entries expire after 10 minutes per eFTI protocol specification.';
COMMENT ON COLUMN request_id_cache.request_id IS 'The X-Request-ID value (UUID string). Primary key ensures uniqueness within the TTL window.';
COMMENT ON COLUMN request_id_cache.seen_at    IS 'Timestamp when this request ID was first seen';
COMMENT ON COLUMN request_id_cache.expires_at IS 'Timestamp after which this entry may be deleted by the cleanup job. Default: seen_at + 10 minutes.';

CREATE INDEX idx_request_id_cache_expires ON request_id_cache (expires_at);

-- ----------------------------------------------------------------------------
-- 4.10 sessions
-- (JWT token blacklisting for explicit logout)
-- ----------------------------------------------------------------------------

CREATE TABLE sessions (
  id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID        NOT NULL REFERENCES users (id),
  token_hash  VARCHAR(64) NOT NULL UNIQUE,
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  revoked_at  TIMESTAMPTZ
);

COMMENT ON TABLE  sessions IS 'Active and revoked JWT sessions. Used for token blacklisting on logout and for enforcing single-session policies.';
COMMENT ON COLUMN sessions.id         IS 'UUID primary key';
COMMENT ON COLUMN sessions.user_id    IS 'FK to the user who owns this session. No cascade — sessions age out via TTL on expires_at, not via parent DELETE.';
COMMENT ON COLUMN sessions.token_hash IS 'SHA-256 hash of the JWT token string. Used for O(1) blacklist lookup on each request.';
COMMENT ON COLUMN sessions.expires_at IS 'Natural expiry of the JWT token (from exp claim). Session is invalid after this timestamp regardless of revocation.';
COMMENT ON COLUMN sessions.created_at IS 'Timestamp when the session was created (login time)';
COMMENT ON COLUMN sessions.revoked_at IS 'Timestamp when the token was explicitly revoked (logout). NULL means session is still active.';

CREATE INDEX idx_sessions_token_hash ON sessions (token_hash);
CREATE INDEX idx_sessions_user_id    ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at) WHERE revoked_at IS NULL;

-- ----------------------------------------------------------------------------
-- 4.11 jobs_execution_log
-- (scheduled job monitoring: GatePingJob, IdentifierExpirationJob)
-- ----------------------------------------------------------------------------

CREATE TABLE jobs_execution_log (
  id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
  job_name    VARCHAR(100) NOT NULL,
  started_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  finished_at TIMESTAMPTZ,
  status      job_status   NOT NULL DEFAULT 'running',
  details     JSONB
);

COMMENT ON TABLE  jobs_execution_log IS 'Execution log for scheduled background jobs. Used for monitoring and alerting on job failures.';
COMMENT ON COLUMN jobs_execution_log.id          IS 'UUID primary key';
COMMENT ON COLUMN jobs_execution_log.job_name    IS 'Logical job name (e.g. "ping-job", "expiry-job")';
COMMENT ON COLUMN jobs_execution_log.started_at  IS 'Timestamp when the job execution started';
COMMENT ON COLUMN jobs_execution_log.finished_at IS 'Timestamp when the job completed. NULL if still running or crashed.';
COMMENT ON COLUMN jobs_execution_log.status      IS 'Execution outcome: running, completed, or failed';
COMMENT ON COLUMN jobs_execution_log.details     IS 'JSON details about the execution result, e.g. {"gates_pinged":3,"gates_online":2,"duration_ms":420} or {"error":"Connection refused","gate_id":"eu-de01"}';

CREATE INDEX idx_jobs_log_job_name   ON jobs_execution_log (job_name, started_at DESC);
CREATE INDEX idx_jobs_log_started_at ON jobs_execution_log (started_at DESC);

-- ----------------------------------------------------------------------------
-- 4.12 follow_up_log — record of follow-up messages forwarded by the gate
-- (mandatory fields per Art 6(2)(c) Reg 2024/1942)
-- ----------------------------------------------------------------------------

CREATE TABLE follow_up_log (
  follow_up_id            UUID             PRIMARY KEY DEFAULT uuid_generate_v4(),
  requesting_gate_id      CITEXT           NOT NULL,
  requesting_user_id      UUID             REFERENCES users (id),
  dataset_request_id      UUID             NOT NULL,
  destination_gate_id     CITEXT           NOT NULL,
  destination_platform_id CITEXT           NOT NULL,
  payload                 TEXT             NOT NULL,
  status                  follow_up_status NOT NULL,
  failure_reason          TEXT,
  received_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  follow_up_log IS 'Log of follow-up messages received by the AAP and forwarded to the destination platform/gate. Mandatory fields per Art 6(2)(c) Reg 2024/1942: follow_up_id, requesting_gate_id, received_at.';
COMMENT ON COLUMN follow_up_log.follow_up_id            IS 'Unique identifier for the follow-up message (UUIDv4). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_gate_id      IS 'Gate that originated the follow-up (AAP). May be the local gate or a remote gate via G2G. Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_user_id      IS 'Authority user who issued the follow-up. NULL when the follow-up arrived over G2G eDelivery without user attribution.';
COMMENT ON COLUMN follow_up_log.dataset_request_id      IS 'Dataset request being followed up on. Not a foreign key — referenced request may have been issued by a peer gate.';
COMMENT ON COLUMN follow_up_log.destination_gate_id     IS 'Gate that owns the target platform. Equals own gate for local platforms, peer gate ID for remote.';
COMMENT ON COLUMN follow_up_log.destination_platform_id IS 'Platform to which the follow-up was delivered.';
COMMENT ON COLUMN follow_up_log.payload                 IS 'Follow-up message body, forwarded unchanged. Stored for audit traceability.';
COMMENT ON COLUMN follow_up_log.status                  IS 'Final outcome of the forwarding attempt. delivered=accepted by destination; failed=rejected or unreachable.';
COMMENT ON COLUMN follow_up_log.failure_reason          IS 'Error description when status=failed (HTTP status, AS4 fault, network error). NULL otherwise.';
COMMENT ON COLUMN follow_up_log.received_at             IS 'Timestamp when the follow-up was received by the AAP. Mandatory per Art 6(2)(c).';

CREATE INDEX idx_follow_up_log_received_at  ON follow_up_log (received_at DESC);
CREATE INDEX idx_follow_up_log_dataset_req  ON follow_up_log (dataset_request_id);
CREATE INDEX idx_follow_up_log_requesting   ON follow_up_log (requesting_gate_id, received_at DESC);

-- Append-only enforcement (uses reject_modification() defined in section 4.1).
CREATE TRIGGER follow_up_log_no_modify
  BEFORE UPDATE OR DELETE ON follow_up_log
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

REVOKE UPDATE, DELETE ON follow_up_log FROM PUBLIC;

-- ----------------------------------------------------------------------------
-- 4.13 audit_log — high-level user-action audit trail
-- (distinct from change_history, which captures column-level row mutations)
-- ----------------------------------------------------------------------------

CREATE TABLE audit_log (
  id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID         REFERENCES users (id),
  action      VARCHAR(64)  NOT NULL,
  resource    VARCHAR(64)  NOT NULL,
  resource_id VARCHAR(200),
  ip_address  INET,
  details     JSONB,
  recorded_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  audit_log IS 'High-level user-action audit trail. Captures who did what to which resource and when. Distinct from change_history (column-level row mutations); audit_log is at the application action level (login, search, dataset request, follow-up sent, etc.).';
COMMENT ON COLUMN audit_log.id          IS 'Unique identifier for the audit record (UUIDv4).';
COMMENT ON COLUMN audit_log.user_id     IS 'User who performed the action. NULL for system actions or pre-authentication events (e.g. failed login).';
COMMENT ON COLUMN audit_log.action      IS 'Action verb (e.g. login, login_failed, search_identifiers, request_dataset, send_follow_up, register_platform).';
COMMENT ON COLUMN audit_log.resource    IS 'Resource type touched by the action (e.g. dataset, identifier, platform, gate, user).';
COMMENT ON COLUMN audit_log.resource_id IS 'Identifier of the resource (UUID, plate string, gate ID, etc.). NULL for collection-level actions (list).';
COMMENT ON COLUMN audit_log.ip_address  IS 'Source IP address of the request. INET supports both IPv4 and IPv6.';
COMMENT ON COLUMN audit_log.details     IS 'Additional structured context (search filters, query parameters, error reason). JSONB for arbitrary action-specific fields.';
COMMENT ON COLUMN audit_log.recorded_at IS 'Timestamp when the action was logged.';

CREATE INDEX idx_audit_log_user_recorded ON audit_log (user_id, recorded_at DESC);
CREATE INDEX idx_audit_log_action        ON audit_log (action, recorded_at DESC);
CREATE INDEX idx_audit_log_resource      ON audit_log (resource, resource_id);
CREATE INDEX idx_audit_log_recorded_at   ON audit_log (recorded_at DESC);

-- Append-only enforcement (uses reject_modification() defined in section 4.1).
CREATE TRIGGER audit_log_no_modify
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION reject_modification();

REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC;

-- ----------------------------------------------------------------------------
-- 4.14 db_user — application database user and grants
-- ----------------------------------------------------------------------------
-- Persistence taxonomy (see specs/db/README.md for full table):
--   - Ledger tables (change_history, audit_log, follow_up_log): SELECT, INSERT
--     only. UPDATE/DELETE additionally rejected by BEFORE trigger.
--   - Ephemeral tables (request_id_cache, sessions, jobs_execution_log):
--     SELECT, INSERT only. Aged out by partition rotation under a separate
--     maintenance role (not `app`).
--   - Registry tables (gates, platforms, authorities, users, consignments,
--     identifiers): SELECT, INSERT, UPDATE. DELETE never granted; logical
--     deletion via status enums.
-- DELETE is not granted to `app` on any table.
-- ----------------------------------------------------------------------------

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app') THEN
    CREATE USER app WITH PASSWORD 'app-secret';
  END IF;
END;
$$;

GRANT USAGE ON SCHEMA public TO app;

-- Defaults for any tables added later by migrations.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES    TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE        ON FUNCTIONS TO app;

-- Registry tables: SELECT, INSERT, UPDATE (UPDATE captured into change_history
-- by AFTER UPDATE triggers). DELETE not granted.
GRANT SELECT, INSERT, UPDATE ON
  gates, platforms, authorities, users,
  consignments, identifiers
  TO app;

-- Ledger tables: SELECT, INSERT only. UPDATE/DELETE additionally rejected by
-- BEFORE trigger that RAISE EXCEPTIONs (defense in depth).
GRANT SELECT, INSERT ON
  change_history, audit_log, follow_up_log
  TO app;

-- Ephemeral tables: SELECT, INSERT only. Aged out by partition rotation under
-- a separate maintenance role (not `app`).
GRANT SELECT, INSERT ON
  request_id_cache, sessions, jobs_execution_log
  TO app;

-- ============================================================================
-- 5. SEED DATA
-- (Realistic development/testing data — DO NOT load in production)
-- Wrapped in explicit transaction for atomic rollback on error
-- ============================================================================

BEGIN;

-- --------------------------------------------------------------------------
-- 5.1 Gates
-- --------------------------------------------------------------------------
INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, last_ping_at) VALUES
  ('eu-ee31', 'EE',
   'https://eu-ee31.eftisandbox.eu/services/msh',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIICpDCCAYwCCQDemoEE31GateCert==...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   NULL,
   'ONLINE',  NOW() - INTERVAL '2 minutes'),

  ('eu-fi01', 'FI',
   'https://efti.traficom.fi/services/msh',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIICpDCCAYwCCQDemoFI01GateCert==...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   NULL,
   'ONLINE',  NOW() - INTERVAL '3 minutes'),

  ('eu-de01', 'DE',
   'https://efti.bast.de/services/msh',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIICpDCCAYwCCQDemoDE01GateCert==...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIICpDCCAYwCCQDemoDE01TLSCert==...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   'OFFLINE', NOW() - INTERVAL '2 hours'),

  ('eu-lv01', 'LV',
   'https://efti.csdd.lv/services/msh',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIICpDCCAYwCCQDemoLV01GateCert==...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   NULL,
   'ONLINE',  NOW() - INTERVAL '1 minute');

-- --------------------------------------------------------------------------
-- 5.2 Platforms
-- --------------------------------------------------------------------------
INSERT INTO platforms (id, base_url, headers, e_delivery_cert, supports_subsetting) VALUES
  ('plt-demo-123', 'https://demo-platform.eu-ee31.eftisandbox.eu/v1',
   '{"X-Api-Key":"demo-platform-key-abc123"}',
   NULL,
   TRUE),

  ('plt-test-456', 'https://test-platform.efti.ee/api',
   '{"Authorization":"Bearer test-platform-token"}',
   NULL,
   FALSE),  -- Gate applies XSLT subsetting for this platform

  ('demo-edelivery', 'http://demo-platform:8070/services/msh',
   '{}',
   '-----BEGIN CERTIFICATE-----' || chr(10) ||
   'MIIC/zCCAeegAwIBAgIULAsIpl8XuCG4P1wR0gSyiMHSrFUwDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAwwEZGVtbzAe...(truncated)' || chr(10) ||
   '-----END CERTIFICATE-----',
   TRUE);

-- --------------------------------------------------------------------------
-- 5.3 Authorities
-- --------------------------------------------------------------------------
INSERT INTO authorities (id, country_code, name, subsets) VALUES
  ('auth-mta',  'EE', 'Tax and Customs Board',        ARRAY['EU01','EU02','EU05']),
  ('auth-ppa',  'EE', 'Police and Border Guard',       ARRAY['EU01','EU02','EU03','EU04','EU05']),
  ('auth-vtko', 'EE', 'Transport Administration',      ARRAY['EU01','EU02','EU03','EU04']),
  ('auth-vet',  'EE', 'Veterinary and Food Board',     ARRAY['EU06','EU07']),
  ('auth-trafi','FI', 'Traficom (FI Transport Auth)',  ARRAY['EU01','EU02','EU03']),
  ('demo',      'EE', 'Demo Authority (dev only)',      ARRAY['EU01','EU02','EU03','EU04','EU05','EU06','EU07']);

-- --------------------------------------------------------------------------
-- 5.4 Users
-- --------------------------------------------------------------------------
INSERT INTO users (id, name, email, is_admin, roles, subsets, secret_hash) VALUES
  -- SUPER_ADMIN (is_admin=true, empty roles)
  ('550e8400-e29b-41d4-a716-446655440000',
   'Demo Admin', 'admin@efti.ee', TRUE, '{}', NULL,
   'UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ='),

  -- Platform service account
  ('502d74a0-eb03-11f0-b86c-3c9c0f2eb459',
   'Demo Platform Service Account', NULL, FALSE,
   '{"PLATFORM":["plt-demo-123"]}'::jsonb, NULL,
   'd6FSE1dlZGeZOaH9qBQVwLIBSNLtR/V6lcX+y8Xvd2Y='),

  -- Authority user (MTA — restricted to EU01, EU02 only, not EU05)
  ('04fa30eb-eb08-11f0-b506-3c9c0f2eb459',
   'MTA Inspector', 'inspector@mta.ee', FALSE,
   '{"AUTHORITY":["auth-mta"]}'::jsonb, ARRAY['EU01','EU02'],
   'SYKPH8XcG6HGxFVyZX6xLxDUViJldNbNbYtqtvM2pO4='),

  -- Authority user (PPA — full authority subset access)
  ('7c9e6679-7425-40de-944b-e07fc1f90ae7',
   'PPA Border Inspector', 'border@ppa.ee', FALSE,
   '{"AUTHORITY":["auth-ppa"]}'::jsonb, NULL,
   'kTqV2mN8pL3xR6sJ9wA0cE4dF7bH1nY5gU2hI0jK8lM='),

  -- Gate admin (scoped to eu-ee31)
  ('abc12345-def6-7890-abcd-ef1234567890',
   'EE Gate Admin', 'admin@efti-ee31.ee', FALSE,
   '{"GATE":["eu-ee31"]}'::jsonb, NULL,
   'xP3mL9kQ2rT5vB8nD1sA6cE0fH4jI7gU3wY9oN2pR5=');

-- --------------------------------------------------------------------------
-- 5.5 Consignments (50 realistic records)
-- --------------------------------------------------------------------------
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status, mode,
  vehicle_plate, vehicle_country, dangerous_goods,
  transport_date, delivered_at, expires_at
) VALUES

  -- 1. Active Estonian truck with dangerous goods (gasoline, UN 1203)
  ('550e8400-e29b-41d4-a716-446655440001', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">123ABC</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1203</unNumber><technicalName>Gasoline</technicalName><adrClass>3</adrClass></transportedDangerousGoods>',
   'active', 'road', '123ABC', 'EE', TRUE,  CURRENT_DATE - 2, NULL, NULL),

  -- 2. Active Finnish truck, delivered 2 days ago — expires in 12 days
  ('550e8400-e29b-41d4-a716-446655440002', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">456XYZ</id><registrationCountry><code>FI</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'active', 'road', '456XYZ', 'FI', FALSE, CURRENT_DATE - 3,
   NOW() - INTERVAL '2 days', NOW() + INTERVAL '12 days'),

  -- 3. Inactive German truck — expired yesterday
  ('550e8400-e29b-41d4-a716-446655440003', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">789KLM</id><registrationCountry><code>DE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'inactive', 'road', '789KLM', 'DE', FALSE, CURRENT_DATE - 20,
   NOW() - INTERVAL '15 days', NOW() - INTERVAL '1 day'),

  -- 4. Active maritime container
  ('550e8400-e29b-41d4-a716-446655440004', 'plt-demo-123', 'eu-ee31',
   '<usedTransportEquipment><id schemeAgencyId="5">MSCU1234567</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator></mainCarriageTransportMovement>',
   'active', 'maritime', NULL, NULL, FALSE, CURRENT_DATE, NULL, NULL),

  -- 5. Active rail freight (Latvia → Estonia)
  ('550e8400-e29b-41d4-a716-446655440005', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LV-RAIL-9901</id><registrationCountry><code>LV</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'active', 'rail', 'LV-RAIL-9901', 'LV', FALSE, CURRENT_DATE - 1, NULL, NULL),

  -- 6. Active Estonian truck — aerosols (UN 1950)
  ('550e8400-e29b-41d4-a716-446655440006', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">321CBA</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1950</unNumber><technicalName>Aerosols</technicalName><adrClass>2.1</adrClass></transportedDangerousGoods>',
   'active', 'road', '321CBA', 'EE', TRUE,  CURRENT_DATE - 1, NULL, NULL),

  -- 7. Active Lithuanian truck — propane (UN 1965)
  ('550e8400-e29b-41d4-a716-446655440007', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LT-7788BB</id><registrationCountry><code>LT</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1965</unNumber><technicalName>Hydrocarbon gas mixture (Propane)</technicalName><adrClass>2.1</adrClass></transportedDangerousGoods>',
   'active', 'road', 'LT-7788BB', 'LT', TRUE,  CURRENT_DATE,     NULL, NULL),

  -- 8. Active air cargo (Helsinki → Tallinn)
  ('550e8400-e29b-41d4-a716-446655440008', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>4</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">AY-1234</id><registrationCountry><code>FI</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'active', 'air', 'AY-1234', 'FI', FALSE, CURRENT_DATE, NULL, NULL),

  -- 9. Deleted Estonian truck (platform deleted)
  ('550e8400-e29b-41d4-a716-446655440009', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">DEL-999</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'deleted', 'road', 'DEL-999', 'EE', FALSE, CURRENT_DATE - 5, NULL, NULL),

  -- 10. Active Latvian truck (no dangerous goods)
  ('550e8400-e29b-41d4-a716-446655440010', 'plt-demo-123', 'eu-ee31',
   '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LV-1122CC</id><registrationCountry><code>LV</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>',
   'active', 'road', 'LV-1122CC', 'LV', FALSE, CURRENT_DATE,     NULL, NULL),

  -- 11-20: More Estonian trucks (active, various dates)
  ('550e8400-e29b-41d4-a716-446655440011', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-T001</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'EE-T001', 'EE', FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440012', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-T002</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'EE-T002', 'EE', FALSE, CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440013', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-T003</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1203</unNumber><technicalName>Gasoline</technicalName></transportedDangerousGoods>', 'active', 'road', 'EE-T003', 'EE', TRUE,  CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440014', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-T004</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'EE-T004', 'EE', FALSE, CURRENT_DATE - 3, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440015', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">FI-T005</id><registrationCountry><code>FI</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'FI-T005', 'FI', FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440016', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">DE-T006</id><registrationCountry><code>DE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'DE-T006', 'DE', FALSE, CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440017', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">PL-T007</id><registrationCountry><code>PL</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1950</unNumber><technicalName>Aerosols</technicalName></transportedDangerousGoods>', 'active', 'road', 'PL-T007', 'PL', TRUE,  CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440018', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">SE-T008</id><registrationCountry><code>SE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'SE-T008', 'SE', FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440019', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">NL-T009</id><registrationCountry><code>NL</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'NL-T009', 'NL', FALSE, CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440020', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LT-T010</id><registrationCountry><code>LT</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'LT-T010', 'LT', FALSE, CURRENT_DATE - 4, NULL, NULL),

  -- 21-30: Delivered + expiry testing (cabotage 14-day window)
  ('550e8400-e29b-41d4-a716-446655440021', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-CAB001</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active',   'road', 'EE-CAB001', 'EE', FALSE, CURRENT_DATE - 10, NOW() - INTERVAL '5 days',  NOW() + INTERVAL '9 days'),
  ('550e8400-e29b-41d4-a716-446655440022', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">FI-CAB002</id><registrationCountry><code>FI</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active',   'road', 'FI-CAB002', 'FI', FALSE, CURRENT_DATE - 12, NOW() - INTERVAL '8 days',  NOW() + INTERVAL '6 days'),
  ('550e8400-e29b-41d4-a716-446655440023', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">DE-CAB003</id><registrationCountry><code>DE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'inactive', 'road', 'DE-CAB003', 'DE', FALSE, CURRENT_DATE - 25, NOW() - INTERVAL '20 days', NOW() - INTERVAL '6 days'),
  ('550e8400-e29b-41d4-a716-446655440024', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">PL-CAB004</id><registrationCountry><code>PL</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'inactive', 'road', 'PL-CAB004', 'PL', FALSE, CURRENT_DATE - 30, NOW() - INTERVAL '25 days', NOW() - INTERVAL '11 days'),
  ('550e8400-e29b-41d4-a716-446655440025', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-CAB005</id><registrationCountry><code>EE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1203</unNumber><technicalName>Gasoline</technicalName></transportedDangerousGoods>', 'active',   'road', 'EE-CAB005', 'EE', TRUE,  CURRENT_DATE - 8,  NOW() - INTERVAL '3 days',  NOW() + INTERVAL '11 days'),

  -- 26-35: Maritime containers
  ('550e8400-e29b-41d4-a716-446655440026', 'plt-demo-123', 'eu-ee31', '<usedTransportEquipment><id schemeAgencyId="5">TCKU9876543</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator></mainCarriageTransportMovement>', 'active', 'maritime', NULL, NULL, FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440027', 'plt-demo-123', 'eu-ee31', '<usedTransportEquipment><id schemeAgencyId="5">HLXU7654321</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1203</unNumber><technicalName>Gasoline</technicalName></transportedDangerousGoods>', 'active', 'maritime', NULL, NULL, TRUE, CURRENT_DATE, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440028', 'plt-demo-123', 'eu-ee31', '<usedTransportEquipment><id schemeAgencyId="5">APZU4567890</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator></mainCarriageTransportMovement>', 'active', 'maritime', NULL, NULL, FALSE, CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440029', 'plt-test-456', 'eu-ee31', '<usedTransportEquipment><id schemeAgencyId="5">BSIU3456789</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator></mainCarriageTransportMovement>', 'active', 'maritime', NULL, NULL, FALSE, CURRENT_DATE - 3, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440030', 'plt-test-456', 'eu-ee31', '<usedTransportEquipment><id schemeAgencyId="5">CMAU2345678</id><categoryCode>CN</categoryCode></usedTransportEquipment><mainCarriageTransportMovement><modeCode>1</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator></mainCarriageTransportMovement>', 'active', 'maritime', NULL, NULL, FALSE, CURRENT_DATE,     NULL, NULL),

  -- 36-45: Rail freight
  ('550e8400-e29b-41d4-a716-446655440036', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">EE-RAIL-001</id></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'rail', 'EE-RAIL-001', NULL, FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440037', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LV-RAIL-002</id></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'rail', 'LV-RAIL-002', NULL, FALSE, CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440038', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">LT-RAIL-003</id></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1965</unNumber><technicalName>Propane mixture</technicalName></transportedDangerousGoods>', 'active', 'rail', 'LT-RAIL-003', NULL, TRUE, CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440039', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">FI-RAIL-004</id></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'rail', 'FI-RAIL-004', NULL, FALSE, CURRENT_DATE - 4, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440040', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>2</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">DE-RAIL-005</id></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'rail', 'DE-RAIL-005', NULL, FALSE, CURRENT_DATE - 1, NULL, NULL),

  -- 46-55: More road transport (various EU countries)
  ('550e8400-e29b-41d4-a716-446655440046', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">AT-5678DE</id><registrationCountry><code>AT</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'AT-5678DE', 'AT', FALSE, CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440047', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">FR-9999ZZ</id><registrationCountry><code>FR</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'FR-9999ZZ', 'FR', FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440048', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">BE-1234AB</id><registrationCountry><code>BE</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'BE-1234AB', 'BE', FALSE, CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440049', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>true</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">CZ-7654XY</id><registrationCountry><code>CZ</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement><transportedDangerousGoods><unNumber>1950</unNumber><technicalName>Aerosols</technicalName></transportedDangerousGoods>', 'active', 'road', 'CZ-7654XY', 'CZ', TRUE,  CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440050', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">HU-5432BC</id><registrationCountry><code>HU</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'HU-5432BC', 'HU', FALSE, CURRENT_DATE - 3, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440051', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">RO-2468EF</id><registrationCountry><code>RO</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'RO-2468EF', 'RO', FALSE, CURRENT_DATE - 2, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440052', 'plt-test-456', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">BG-1357GH</id><registrationCountry><code>BG</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'BG-1357GH', 'BG', FALSE, CURRENT_DATE - 1, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440053', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">SK-8642IJ</id><registrationCountry><code>SK</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'SK-8642IJ', 'SK', FALSE, CURRENT_DATE,     NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440054', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">SI-9753KL</id><registrationCountry><code>SI</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'SI-9753KL', 'SI', FALSE, CURRENT_DATE - 5, NULL, NULL),
  ('550e8400-e29b-41d4-a716-446655440055', 'plt-demo-123', 'eu-ee31', '<mainCarriageTransportMovement><modeCode>3</modeCode><dangerousGoodsIndicator>false</dangerousGoodsIndicator><usedTransportMeans><id schemeAgencyId="11">HR-1098MN</id><registrationCountry><code>HR</code></registrationCountry></usedTransportMeans></mainCarriageTransportMovement>', 'active', 'road', 'HR-1098MN', 'HR', FALSE, CURRENT_DATE - 3, NULL, NULL);

-- --------------------------------------------------------------------------
-- 5.6 Identifiers (extracted from consignments above)
-- --------------------------------------------------------------------------
INSERT INTO identifiers (dataset_id, identifier_type, identifier_value, country_code) VALUES
  ('550e8400-e29b-41d4-a716-446655440001', 'means',     '123ABC',       'EE'),
  ('550e8400-e29b-41d4-a716-446655440002', 'means',     '456XYZ',       'FI'),
  ('550e8400-e29b-41d4-a716-446655440003', 'means',     '789KLM',       'DE'),
  ('550e8400-e29b-41d4-a716-446655440004', 'equipment', 'MSCU1234567',  NULL),
  ('550e8400-e29b-41d4-a716-446655440005', 'means',     'LV-RAIL-9901', 'LV'),
  ('550e8400-e29b-41d4-a716-446655440006', 'means',     '321CBA',       'EE'),
  ('550e8400-e29b-41d4-a716-446655440007', 'means',     'LT-7788BB',    'LT'),
  ('550e8400-e29b-41d4-a716-446655440008', 'means',     'AY-1234',      'FI'),
  ('550e8400-e29b-41d4-a716-446655440009', 'means',     'DEL-999',      'EE'),
  ('550e8400-e29b-41d4-a716-446655440010', 'means',     'LV-1122CC',    'LV'),
  ('550e8400-e29b-41d4-a716-446655440011', 'means',     'EE-T001',      'EE'),
  ('550e8400-e29b-41d4-a716-446655440012', 'means',     'EE-T002',      'EE'),
  ('550e8400-e29b-41d4-a716-446655440013', 'means',     'EE-T003',      'EE'),
  ('550e8400-e29b-41d4-a716-446655440014', 'means',     'EE-T004',      'EE'),
  ('550e8400-e29b-41d4-a716-446655440015', 'means',     'FI-T005',      'FI'),
  ('550e8400-e29b-41d4-a716-446655440016', 'means',     'DE-T006',      'DE'),
  ('550e8400-e29b-41d4-a716-446655440017', 'means',     'PL-T007',      'PL'),
  ('550e8400-e29b-41d4-a716-446655440018', 'means',     'SE-T008',      'SE'),
  ('550e8400-e29b-41d4-a716-446655440019', 'means',     'NL-T009',      'NL'),
  ('550e8400-e29b-41d4-a716-446655440020', 'means',     'LT-T010',      'LT'),
  ('550e8400-e29b-41d4-a716-446655440021', 'means',     'EE-CAB001',    'EE'),
  ('550e8400-e29b-41d4-a716-446655440022', 'means',     'FI-CAB002',    'FI'),
  ('550e8400-e29b-41d4-a716-446655440023', 'means',     'DE-CAB003',    'DE'),
  ('550e8400-e29b-41d4-a716-446655440024', 'means',     'PL-CAB004',    'PL'),
  ('550e8400-e29b-41d4-a716-446655440025', 'means',     'EE-CAB005',    'EE'),
  ('550e8400-e29b-41d4-a716-446655440026', 'equipment', 'TCKU9876543',  NULL),
  ('550e8400-e29b-41d4-a716-446655440027', 'equipment', 'HLXU7654321',  NULL),
  ('550e8400-e29b-41d4-a716-446655440028', 'equipment', 'APZU4567890',  NULL),
  ('550e8400-e29b-41d4-a716-446655440029', 'equipment', 'BSIU3456789',  NULL),
  ('550e8400-e29b-41d4-a716-446655440030', 'equipment', 'CMAU2345678',  NULL),
  ('550e8400-e29b-41d4-a716-446655440036', 'means',     'EE-RAIL-001',  NULL),
  ('550e8400-e29b-41d4-a716-446655440037', 'means',     'LV-RAIL-002',  NULL),
  ('550e8400-e29b-41d4-a716-446655440038', 'means',     'LT-RAIL-003',  NULL),
  ('550e8400-e29b-41d4-a716-446655440039', 'means',     'FI-RAIL-004',  NULL),
  ('550e8400-e29b-41d4-a716-446655440040', 'means',     'DE-RAIL-005',  NULL),
  ('550e8400-e29b-41d4-a716-446655440046', 'means',     'AT-5678DE',    'AT'),
  ('550e8400-e29b-41d4-a716-446655440047', 'means',     'FR-9999ZZ',    'FR'),
  ('550e8400-e29b-41d4-a716-446655440048', 'means',     'BE-1234AB',    'BE'),
  ('550e8400-e29b-41d4-a716-446655440049', 'means',     'CZ-7654XY',    'CZ'),
  ('550e8400-e29b-41d4-a716-446655440050', 'means',     'HU-5432BC',    'HU'),
  ('550e8400-e29b-41d4-a716-446655440051', 'means',     'RO-2468EF',    'RO'),
  ('550e8400-e29b-41d4-a716-446655440052', 'means',     'BG-1357GH',    'BG'),
  ('550e8400-e29b-41d4-a716-446655440053', 'means',     'SK-8642IJ',    'SK'),
  ('550e8400-e29b-41d4-a716-446655440054', 'means',     'SI-9753KL',    'SI'),
  ('550e8400-e29b-41d4-a716-446655440055', 'means',     'HR-1098MN',    'HR');

-- --------------------------------------------------------------------------
-- 5.7 Initial jobs log entries
-- --------------------------------------------------------------------------
INSERT INTO jobs_execution_log (job_name, started_at, finished_at, status, details) VALUES
  ('ping-job',   NOW() - INTERVAL '5 minutes',  NOW() - INTERVAL '4 minutes 58 seconds', 'completed', '{"gates_pinged":4,"gates_online":3,"duration_ms":1840}'),
  ('expiry-job', NOW() - INTERVAL '5 minutes',  NOW() - INTERVAL '4 minutes 59 seconds', 'completed', '{"expired_count":0,"duration_ms":42}'),
  ('ping-job',   NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '9 minutes 57 seconds', 'completed', '{"gates_pinged":4,"gates_online":3,"duration_ms":2100}'),
  ('expiry-job', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '9 minutes 59 seconds', 'completed', '{"expired_count":2,"duration_ms":65}');

COMMIT;

-- ============================================================================
-- END OF SCHEMA
-- Verification queries:
--   SELECT count(*) FROM consignments;         -- expect 50+
--   SELECT count(*) FROM identifiers;          -- expect 45+
--   SELECT count(*) FROM gates WHERE status = 'ONLINE';  -- expect 3
--   SELECT tablename, indexname FROM pg_indexes WHERE schemaname='public' ORDER BY 1;
--   SELECT count(*) FROM pg_description WHERE objsubid > 0;  -- expect 80+
-- ============================================================================
