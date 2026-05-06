# LLM Prompt: Generate Complete PostgreSQL Database Schema for eFTI Gate v2.0

## Context

You are tasked with creating a **complete, executable PostgreSQL database schema** for the European Freight Transport Information (eFTI) Gate system. This schema must support:
1. One-command setup: `psql < schema.sql` creates working database
2. Multi-node deployment with registry synchronization
3. Audit logging and GDPR compliance
4. High-performance queries (license plate search, subset filtering)

## Required Input Materials - CHECKLIST

**⚠️ BEFORE STARTING**: Verify you have ALL required inputs below. If any are missing, **STOP** and request them.

### ✅ Mandatory Inputs - Verify Each One

- [ ] **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
  - **Purpose**: Reference for understanding data model and business logic (NOT to copy blindly)
  - **What to check**:
    - `gate/db/*.sql` - Current database schema files
    - `gate/src/efti/Consignment.kt` - Data models
    - `gate/src/efti/EftiService.kt` - Query patterns
  - **How to use**: Understand what data is stored, what queries are critical, what relationships exist
  - **Do NOT**: Copy missing indexes, weak constraints, poor table design

- [ ] **Epic Documentation**: `efti_full_epics_en.md`
  - **Purpose**: Business requirements defining data entities
  - **Must include**: EPIC 2 (data model), EPIC 13 (database requirements)
  - **What to extract**: Entity relationships, business rules, data retention policies

- [ ] **Technical Analysis Documents**:
  - [ ] `efti-gate-deep-dive-analysis.md` - Deep dive code analysis
    - **What to extract**: Database patterns, performance bottlenecks, query patterns to optimize
  - [ ] `comparison-analysis.md` - Analysis comparison
    - **What to extract**: Consensus on database design, performance characteristics
  - [ ] `gap-analysis-askend-vs-my-analysis.md` - Production readiness gaps
    - **What to extract**: Scalability issues, multi-node requirements
  - [ ] `comparison-existing-vs-v2.0-spec.md` - Strategic direction
    - **What to extract**: Database technology decisions, migration strategy

- [ ] **Technical Requirements**: Procurement specification "Tarkvara tehnilise analüüsi nõuded"
  - **Purpose**: Mandatory technical constraints for database
  - **What to extract**: Required tools (Flyway/Liquibase), PostgreSQL version (14+), required extensions

- [ ] **Askend's Business Analysis**: Your own data requirements
  - **Purpose**: Detailed data model beyond epics
  - **Note**: NOT provided by KeMIT - you must provide your own analysis
  - **What to include**: Entity attributes, relationships, constraints, validation rules

### ⚠️ Cross-Prompt Dependencies

None - This prompt can run in parallel with PROMPT-01.

### ❌ If Missing Inputs

**DO NOT PROCEED** if any mandatory input is missing. The database schema will be incomplete and missing critical tables/indexes.

**Action Required**:
1. Request missing analysis documents from KeMIT
2. Prepare your own data model if not ready
3. Only start generation when ALL inputs are available

## Your Task

Create file: `db/schema.sql` (800-1000 lines) - Complete executable schema

**Design Philosophy**:
- **Optimize for the business requirements**, not for matching Current Gate
- **Improve** Current Gate where it has limitations (missing indexes, weak constraints, poor naming)
- **Preserve** business logic patterns (e.g., expiry calculation)
- **Document** any deviations from Current Gate with rationale in SQL comments

## Database Structure

### Required Extensions

```sql
-- Enable required PostgreSQL extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";  -- UUID generation
CREATE EXTENSION IF NOT EXISTS "pg_trgm";    -- Trigram fuzzy search for license plates
CREATE EXTENSION IF NOT EXISTS "btree_gin";  -- GIN indexes on scalar types
```

### Custom Types (ENUMs)

```sql
-- Gate operational status
CREATE TYPE gate_status AS ENUM ('ONLINE', 'OFFLINE', 'DISABLED');

-- Consignment lifecycle status
CREATE TYPE consignment_status AS ENUM ('active', 'inactive', 'deleted');

-- Transport mode (matches EU classification)
CREATE TYPE transport_mode AS ENUM ('maritime', 'rail', 'road', 'air', 'multimodal');

-- User role types
CREATE TYPE user_role AS ENUM ('SUPER_ADMIN', 'ADMIN', 'PLATFORM', 'AUTHORITY');
```

## Core Tables (12 required)

### 1. gates

**Purpose**: Registry of European eFTI gates for gate-to-gate communication

```sql
CREATE TABLE gates (
  id VARCHAR(20) PRIMARY KEY,  -- Format: eu-{country}{number}, e.g., eu-ee31
  country_code CHAR(2) NOT NULL,
  edelivery_url VARCHAR(500) NOT NULL,  -- AS4 endpoint URL
  fast_adapter_url VARCHAR(500),  -- Optional fast protocol endpoint
  certificate TEXT,  -- X.509 certificate PEM format
  status gate_status NOT NULL DEFAULT 'OFFLINE',
  last_ping_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Constraints
  CONSTRAINT gates_country_code_format CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT gates_id_format CHECK (id ~ '^eu-[a-z]{2}[0-9]{2}$'),
  CONSTRAINT gates_edelivery_url_format CHECK (edelivery_url ~ '^https?://')
);

-- COMMENT ON every table and column (MANDATORY)
COMMENT ON TABLE gates IS 'Registry of European eFTI gates for gate-to-gate communication';
COMMENT ON COLUMN gates.id IS 'Unique gate identifier following EU eFTI naming convention (format: eu-{ISO country code}{number})';
COMMENT ON COLUMN gates.country_code IS 'ISO 3166-1 alpha-2 country code (e.g., EE, FI, DE)';
COMMENT ON COLUMN gates.edelivery_url IS 'eDelivery AS4 message service handler endpoint URL';
COMMENT ON COLUMN gates.fast_adapter_url IS 'Optional fast protocol endpoint (bypasses eDelivery overhead for trusted gates). NULL if not supported.';
COMMENT ON COLUMN gates.certificate IS 'X.509 certificate for mutual TLS authentication in PEM format. Used for eDelivery signature validation.';
COMMENT ON COLUMN gates.status IS 'Current operational status: ONLINE (accepting queries), OFFLINE (unreachable), DISABLED (manually deactivated by admin)';
COMMENT ON COLUMN gates.last_ping_at IS 'Timestamp of last successful ping response. Updated every 5 minutes by automated ping job.';

-- Indexes for query performance
CREATE INDEX idx_gates_status ON gates(status) WHERE status = 'ONLINE';  -- Partial index for broadcast queries
CREATE INDEX idx_gates_country ON gates(country_code);
```

### 2. platforms

**Purpose**: Registry of eFTI platforms registered with this gate

**Schema requirements**:
- `id` VARCHAR(100) PRIMARY KEY (platform-chosen identifier)
- `gate_id` VARCHAR(20) FK → gates(id) ON DELETE RESTRICT
- `name` VARCHAR(200)
- `base_url` VARCHAR(500) (REST API endpoint for dataset queries)
- `edelivery_party_id` VARCHAR(200) NULLABLE (eDelivery AS4 party identifier)
- `edelivery_cert` TEXT NULLABLE
- `supports_subsetting` BOOLEAN DEFAULT true (if false, gate applies XSLT subsetter)
- `created_at`, `updated_at` TIMESTAMPTZ

**Indexes**: FK index on `gate_id`

### 3. authorities

**Purpose**: Registry of competent authorities permitted to query freight data

**Schema requirements**:
- `id` VARCHAR(100) PRIMARY KEY
- `gate_id` VARCHAR(20) FK → gates(id)
- `name` VARCHAR(200)
- `country_code` CHAR(2)
- `subsets` VARCHAR(10)[] (PostgreSQL array, e.g., '{EU01,EU02,EU05}')
- `created_at`, `updated_at`

**Constraints**:
- CHECK: `array_length(subsets, 1) > 0` (cannot be empty array)
- CHECK: `country_code ~ '^[A-Z]{2}$'`

### 4. users

**Purpose**: User accounts for admin, platform, and authority access

**Schema requirements**:
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `email` VARCHAR(255) UNIQUE
- `password_hash` VARCHAR(255) NULLABLE (NULL for TARA-only users)
- `roles` JSONB NOT NULL DEFAULT '{}'::jsonb
  - Format: `{"ADMIN": ["eu-ee31"], "PLATFORM": ["plt-123"], "AUTHORITY": ["auth-mta"]}`
- `is_active` BOOLEAN DEFAULT true
- `created_at`, `updated_at`

**Indexes**:
- UNIQUE on `email`
- GIN index on `roles` for JSONB queries

**COMMENT example**:
```sql
COMMENT ON COLUMN users.roles IS 'JSONB map of role names to arrays of party IDs. Example: {"ADMIN": ["eu-ee31"], "PLATFORM": ["plt-123"]}. User can have multiple roles.';
```

### 5. consignments (MOST COMPLEX TABLE)

**Purpose**: Stored consignment identifiers registered by platforms

**Schema requirements**:
- `id` UUID PRIMARY KEY DEFAULT uuid_generate_v4()
- `dataset_id` UUID NOT NULL (platform's dataset identifier)
- `platform_id` VARCHAR(100) FK → platforms(id) ON DELETE CASCADE
- `gate_id` VARCHAR(20) NOT NULL (denormalized for query performance)
- `xml` TEXT NOT NULL (raw consignment identifier XML without root element)
- `status` consignment_status DEFAULT 'active'
- `mode` transport_mode NULLABLE
- `vehicle_plate` VARCHAR(50) NULLABLE (denormalized for fast search)
- `vehicle_country` CHAR(2) NULLABLE
- `dangerous_goods` BOOLEAN DEFAULT false
- `origin_country` CHAR(2) NULLABLE
- `destination_country` CHAR(2) NULLABLE
- `transport_date` DATE NULLABLE
- `delivered_at` TIMESTAMPTZ NULLABLE (when transport completed)
- `expires_at` TIMESTAMPTZ NULLABLE (auto-calculated: delivered_at + 14 days for ROAD mode)
- `created_at`, `updated_at`

**Constraints**:
```sql
CONSTRAINT consignments_mode_and_plate CHECK (
  mode IS NOT NULL OR vehicle_plate IS NOT NULL
),
CONSTRAINT consignments_country_format CHECK (
  (vehicle_country IS NULL OR vehicle_country ~ '^[A-Z]{2}$') AND
  (origin_country IS NULL OR origin_country ~ '^[A-Z]{2}$') AND
  (destination_country IS NULL OR destination_country ~ '^[A-Z]{2}$')
)
```

**Critical Indexes** (for high-performance search):
```sql
CREATE INDEX idx_consignments_dataset_id ON consignments(dataset_id);
CREATE INDEX idx_consignments_platform_id ON consignments(platform_id);
CREATE INDEX idx_consignments_vehicle_plate ON consignments(vehicle_plate) WHERE vehicle_plate IS NOT NULL;
CREATE INDEX idx_consignments_vehicle_plate_trgm ON consignments USING gin(vehicle_plate gin_trgm_ops);  -- Fuzzy search
CREATE INDEX idx_consignments_status ON consignments(status) WHERE status = 'active';  -- Partial index
CREATE INDEX idx_consignments_mode ON consignments(mode);
CREATE INDEX idx_consignments_transport_date ON consignments(transport_date) WHERE transport_date IS NOT NULL;
CREATE INDEX idx_consignments_expires_at ON consignments(expires_at) WHERE expires_at IS NOT NULL AND status = 'active';
CREATE INDEX idx_consignments_gate_id ON consignments(gate_id);  -- FK index (KeMIT requirement)
```

**COMMENT example**:
```sql
COMMENT ON COLUMN consignments.xml IS 'Raw consignment identifier XML (without <?xml?> declaration and without root <consignment> element). Extracted searchable fields stored in denormalized columns for query performance.';
COMMENT ON COLUMN consignments.expires_at IS 'Auto-calculated expiry timestamp. For ROAD mode: delivered_at + 14 days (per EU Reg 2024/1942 Art 11 para 4 for cabotage control). Other modes: NULL or immediate expiry.';
```

### 6. identifiers

**Purpose**: Individual identifiers extracted from consignments (normalized many-to-one)

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `consignment_id` UUID FK → consignments(id) ON DELETE CASCADE
- `identifier_type` VARCHAR(50) (CHECK: IN ('means', 'equipment', 'carried'))
- `identifier_value` VARCHAR(200)
- `country_code` CHAR(2) NULLABLE
- `created_at`

**Indexes**:
```sql
CREATE INDEX idx_identifiers_consignment_id ON identifiers(consignment_id);
CREATE INDEX idx_identifiers_value ON identifiers(identifier_value);
CREATE INDEX idx_identifiers_value_trgm ON identifiers USING gin(identifier_value gin_trgm_ops);
```

### 7. request_id_cache

**Purpose**: Duplicate request ID detection cache (10-minute TTL per eFTI protocol)

**Schema requirements**:
- `request_id` VARCHAR(100) PRIMARY KEY
- `seen_at` TIMESTAMPTZ DEFAULT NOW()
- `expires_at` TIMESTAMPTZ DEFAULT NOW() + INTERVAL '10 minutes'

**Index**: `CREATE INDEX idx_request_id_cache_expires_at ON request_id_cache(expires_at);`

**Cleanup**: Automated via expiry job (delete WHERE expires_at < NOW())

### 8. async_responses

**Purpose**: Asynchronous eDelivery responses for multi-node coordination

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `request_id` VARCHAR(100)
- `sender_id` VARCHAR(100) (gate/platform ID that sent original request)
- `receiver_id` VARCHAR(100) (gate/platform ID responding)
- `body` TEXT (response payload)
- `created_at` TIMESTAMPTZ DEFAULT NOW()
- UNIQUE constraint on `(request_id, receiver_id)`

**Indexes**:
```sql
CREATE INDEX idx_async_responses_request_id ON async_responses(request_id);
CREATE INDEX idx_async_responses_created_at ON async_responses(created_at);
```

### 9. audit_log (IMMUTABLE)

**Purpose**: Append-only audit log for all administrative actions (GDPR Art 30 compliance)

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `user_id` UUID FK → users(id) ON DELETE SET NULL
- `action` VARCHAR(100) (format: 'resource.operation', e.g., 'gate.create', 'user.delete')
- `resource_type` VARCHAR(50) ('user', 'gate', 'platform', 'authority', 'consignment')
- `resource_id` VARCHAR(200)
- `ip_address` INET
- `details` JSONB (action-specific details, e.g., changed fields)
- `created_at` TIMESTAMPTZ DEFAULT NOW()

**Immutability** (prevent modifications):
```sql
CREATE RULE audit_log_immutable AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
```

**Indexes**:
```sql
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);
```

### 10. change_history

**Purpose**: Automatic change tracking for all registry modifications (before/after values)

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `table_name` VARCHAR(50)
- `record_id` VARCHAR(200)
- `operation` VARCHAR(10) ('INSERT', 'UPDATE', 'DELETE')
- `old_values` JSONB NULLABLE
- `new_values` JSONB NULLABLE
- `changed_by` UUID FK → users(id) ON DELETE SET NULL
- `changed_at` TIMESTAMPTZ DEFAULT NOW()

### 11. sessions (for JWT token blacklisting)

**Purpose**: Track active sessions and revoked tokens

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `user_id` UUID FK → users(id) ON DELETE CASCADE
- `token_hash` VARCHAR(64) UNIQUE (SHA-256 hash of JWT)
- `expires_at` TIMESTAMPTZ
- `created_at` TIMESTAMPTZ

**Index**: `CREATE INDEX idx_sessions_token_hash ON sessions(token_hash);`

### 12. jobs_execution_log

**Purpose**: Track scheduled job executions (ping job, expiry job)

**Schema requirements**:
- `id` UUID PRIMARY KEY
- `job_name` VARCHAR(100) ('ping-job', 'expiry-job')
- `started_at` TIMESTAMPTZ
- `finished_at` TIMESTAMPTZ NULLABLE
- `status` VARCHAR(20) ('running', 'completed', 'failed')
- `details` JSONB (e.g., `{"expired_count": 42, "duration_ms": 150}`)

## Triggers and Functions (CRITICAL)

### 1. Auto-update `updated_at` column

```sql
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply to all tables with updated_at
CREATE TRIGGER gates_updated_at BEFORE UPDATE ON gates
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER platforms_updated_at BEFORE UPDATE ON platforms
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
-- ... (repeat for authorities, users, consignments)
```

### 2. Change History Trigger (Optional but Recommended)

```sql
CREATE OR REPLACE FUNCTION log_change()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO change_history (table_name, record_id, operation, new_values)
    VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'INSERT', row_to_json(NEW));
  ELSIF TG_OP = 'UPDATE' THEN
    INSERT INTO change_history (table_name, record_id, operation, old_values, new_values)
    VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'UPDATE', row_to_json(OLD), row_to_json(NEW));
  ELSIF TG_OP = 'DELETE' THEN
    INSERT INTO change_history (table_name, record_id, operation, old_values)
    VALUES (TG_TABLE_NAME, OLD.id::TEXT, 'DELETE', row_to_json(OLD));
  END IF;
  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Apply to critical tables
CREATE TRIGGER gates_change_log AFTER INSERT OR UPDATE OR DELETE ON gates
  FOR EACH ROW EXECUTE FUNCTION log_change();
-- ... (repeat for other tables)
```

## Seed Data (MANDATORY)

At the end of `schema.sql`, include realistic seed data for development/testing:

```sql
-- Seed data (wrapped in transaction)
BEGIN;

-- Insert 3 gates
INSERT INTO gates (id, country_code, edelivery_url, fast_adapter_url, status, last_ping_at) VALUES
  ('eu-ee31', 'EE', 'https://eu-ee31.eftisandbox.eu/services/msh', 'https://eu-ee31.eftisandbox.eu/services/fast', 'ONLINE', NOW() - INTERVAL '2 minutes'),
  ('eu-fi01', 'FI', 'https://efti.traficom.fi/services/msh', NULL, 'ONLINE', NOW() - INTERVAL '3 minutes'),
  ('eu-de01', 'DE', 'https://efti.bast.de/services/msh', 'https://efti.bast.de/services/fast', 'OFFLINE', NOW() - INTERVAL '2 hours');

-- Insert 2 platforms
INSERT INTO platforms (id, gate_id, name, base_url, supports_subsetting) VALUES
  ('plt-demo-123', 'eu-ee31', 'Demo Platform', 'https://demo-platform.eu-ee31.eftisandbox.eu/v1', true),
  ('plt-test-456', 'eu-ee31', 'Test Platform (no subsetting)', 'https://test-platform.efti.ee/api', false);

-- Insert 3 authorities
INSERT INTO authorities (id, gate_id, name, country_code, subsets) VALUES
  ('auth-mta', 'eu-ee31', 'Tax and Customs Board', 'EE', ARRAY['EU01','EU02','EU05']),
  ('auth-ppa', 'eu-ee31', 'Police and Border Guard', 'EE', ARRAY['EU01','EU02','EU03','EU04','EU05']),
  ('auth-vet', 'eu-ee31', 'Veterinary and Food Board', 'EE', ARRAY['EU06','EU07']);

-- Insert 3 users
INSERT INTO users (id, email, password_hash, roles, is_active) VALUES
  ('550e8400-e29b-41d4-a716-446655440000', 'admin@efti.ee', '$2a$10$...', '{"SUPER_ADMIN": []}'::jsonb, true),
  ('7c9e6679-7425-40de-944b-e07fc1f90ae7', 'platform@demo.com', '$2a$10$...', '{"PLATFORM": ["plt-demo-123"]}'::jsonb, true),
  ('abc12345-def6-7890-abcd-ef1234567890', 'mta@mta.ee', NULL, '{"AUTHORITY": ["auth-mta"]}'::jsonb, true);

-- Insert 50+ realistic consignments
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status, mode, vehicle_plate, vehicle_country, dangerous_goods, transport_date, delivered_at, expires_at) VALUES
  -- Estonian truck (ROAD mode, dangerous goods)
  ('550e8400-e29b-41d4-a716-446655440001', 'plt-demo-123', 'eu-ee31',
   '<means><meansOfTransportId value="123ABC" countryCode="EE"/><modeCode>3</modeCode></means><dangerousGoodsIndicator>true</dangerousGoodsIndicator><transportedDangerousGoods><unNumber>1203</unNumber><technicalName>Gasoline</technicalName></transportedDangerousGoods>',
   'active', 'road', '123ABC', 'EE', true, CURRENT_DATE - INTERVAL '2 days', NULL, NULL),

  -- Finnish truck (ROAD mode, delivered, expires in 12 days)
  ('550e8400-e29b-41d4-a716-446655440002', 'plt-demo-123', 'eu-ee31',
   '<means><meansOfTransportId value="456XYZ" countryCode="FI"/><modeCode>3</modeCode></means>',
   'active', 'road', '456XYZ', 'FI', false, CURRENT_DATE - INTERVAL '3 days', NOW() - INTERVAL '2 days', NOW() + INTERVAL '12 days'),

  -- German truck (ROAD mode, already expired - should be inactive)
  ('550e8400-e29b-41d4-a716-446655440003', 'plt-demo-123', 'eu-ee31',
   '<means><meansOfTransportId value="789KLM" countryCode="DE"/><modeCode>3</modeCode></means>',
   'inactive', 'road', '789KLM', 'DE', false, CURRENT_DATE - INTERVAL '20 days', NOW() - INTERVAL '15 days', NOW() - INTERVAL '1 day'),

  -- Container (MARITIME mode, no vehicle plate)
  ('550e8400-e29b-41d4-a716-446655440004', 'plt-demo-123', 'eu-ee31',
   '<equipmentIdentifier value="MSCU1234567" countryCode="NL"/><modeCode>1</modeCode>',
   'active', 'maritime', NULL, NULL, false, CURRENT_DATE, NULL, NULL)
  -- ... (add 46 more realistic consignments with variations)
  ;

COMMIT;
```

**Seed data requirements**:
- 3+ gates (mix of ONLINE/OFFLINE status)
- 2+ platforms (one with `supports_subsetting=true`, one `false`)
- 3+ authorities (different subset permissions)
- 3+ users (SUPER_ADMIN, PLATFORM, AUTHORITY roles)
- 50+ consignments with:
  - Real Estonian plates: 123ABC, 456XYZ, 789KLM
  - Various modes: road, maritime, rail, air
  - Mix of dangerous goods (true with UN numbers: 1203, 1950, 1965)
  - Past/future transport dates for expiry testing
  - Some with `delivered_at` set (test expiry logic)

## Validation Requirements

Before considering the schema complete:

1. **Syntax Check**: `psql -U postgres -d postgres -f schema.sql --dry-run`
2. **Execution Test**:
   ```bash
   createdb efti_test
   psql -U postgres -d efti_test < schema.sql
   # Should complete without errors
   ```
3. **Data Verification**:
   ```sql
   SELECT count(*) FROM consignments;  -- Should return 50+
   SELECT count(*) FROM gates WHERE status = 'ONLINE';  -- Should return 2+
   ```
4. **Index Verification**:
   ```sql
   SELECT tablename, indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename;
   -- Should show 30+ indexes
   ```
5. **COMMENT Verification**:
   ```sql
   SELECT count(*) FROM pg_description WHERE objsubid > 0;
   -- Should show 80+ column comments (every column documented)
   ```

## Output Format

Create file: `db/schema.sql`

Structure:
```sql
-- eFTI Gate v2.0 Database Schema
-- PostgreSQL 14+
-- Author: Askend Estonia OÜ
-- Date: 2026-04-22
--
-- Setup: psql -U postgres -d efti < schema.sql

-- 1. Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- 2. Custom types
CREATE TYPE gate_status AS ENUM (...);
-- ...

-- 3. Tables (12 total)
CREATE TABLE gates (...);
COMMENT ON TABLE gates IS '...';
COMMENT ON COLUMN gates.id IS '...';
-- ... (all tables)

-- 4. Indexes
CREATE INDEX idx_gates_status ON gates(status) WHERE status = 'ONLINE';
-- ... (30+ indexes)

-- 5. Triggers and functions
CREATE OR REPLACE FUNCTION update_updated_at_column() ...;
CREATE TRIGGER gates_updated_at ...;
-- ...

-- 6. Seed data
BEGIN;
INSERT INTO gates ...;
-- ... (50+ consignments)
COMMIT;
```

## Success Criteria

Your database schema is complete when:

✅ Executes without errors: `psql < schema.sql`
✅ Creates all 12 tables with correct structure
✅ All foreign key columns indexed (KeMIT requirement)
✅ All tables and columns have COMMENT (English)
✅ Seed data: 3+ gates, 2+ platforms, 3+ authorities, 50+ consignments
✅ All examples use realistic data (Estonian plates, real UN numbers)
✅ Zero placeholders or generic examples

## Reference Files to Read

1. `{CURRENT_GATE_SOURCE}/gate/db/*.sql` (current schema)
2. `efti_full_epics_en.md` EPIC 2 (data model)
3. `efti_full_epics_en.md` EPIC 13 (database requirements)

Generate the complete database schema now.
