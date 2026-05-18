# PROMPT-08: Generate Database Migrations for eFTI Gate v2.0

> [!IMPORTANT]
> **Background prompt — not authoritative.** See [`PROMPT-00-INDEX.md`](PROMPT-00-INDEX.md) for historical context, including how stack references here (Kotlin / Klite / Digilogistika Keskus PoC paths) relate to the v2 spec's stack-open position.

## Context

You are helping create **database migration scripts** for eFTI Gate v2.0, a production system for electronic freight transport information exchange under EU Regulation 2024/2024.

Database migrations are critical for:
- **Version control**: Track all database schema changes
- **Reproducibility**: Same schema on dev, staging, production
- **Rollback capability**: Ability to undo changes if deployment fails
- **Deployment automation**: CI/CD pipeline can apply migrations automatically

This specification will be used by external developers during procurement to set up the database schema using industry-standard migration tools (Flyway or Liquibase).

## Your Task

Generate **database migration scripts** (`specs/db/migrations/V*.sql`) following Flyway naming conventions:
- **Initial schema**: `V1__initial_schema.sql` (from schema.sql)
- **Seed data**: `V2__seed_data.sql` (reference gates, example platforms/authorities)
- **Indexes**: `V3__create_indexes.sql` (performance optimization)
- **Additional migrations** (if needed for different deployment phases)

## Input Materials Required

Before starting, you must have access to:

1. **Database Schema**: `specs/db/schema.sql` (from PROMPT-02)
   - Complete schema (tables, constraints, triggers, functions)
   - This is the source of truth for initial schema

2. **Epic Documentation**: `docs/epics/` (per-epic files)
   - Seed data requirements (e.g., initial gates, admin users)

3. **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
   - Current database setup: `gate/db/*.sql`
   - Seed data: `gate/db/seed.sql` (if exists)

4. **Feedback Document**: `docs/Askend/feedback/CRITICAL-SPECIFICATION-GAPS.md`
   - Database migration requirements

## Specification Requirements

### 1. Migration Tool: Flyway

**Choice**: Flyway (required by technical specification "Tarkvara tehnilise analüüsi nõuded" — Estonian: Software Technical Analysis Requirements)

**Alternative**: Liquibase (acceptable, but Flyway preferred)

**Naming Convention (Flyway)**:
```
V{version}__{description}.sql

Examples:
V1__initial_schema.sql
V2__seed_data.sql
V3__create_indexes.sql
V4__add_audit_logging.sql
```

**Version rules**:
- Version numbers: Integer, sequential (1, 2, 3, ...)
- Separator: Double underscore `__`
- Description: Lowercase with underscores (not hyphens, not spaces)

### 2. Required Migrations

Create at least these migration files:

#### V1__initial_schema.sql
- All tables from `schema.sql`
- All constraints (PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK)
- All functions (used by triggers)
- **NO data**: Schema only

#### V2__seed_data.sql
- Reference data for gates registry (EU gates: eu-ee31, eu-fi01, eu-de01, ...)
- Example platform (for development/testing)
- Example authority (for development/testing)
- Example admin user (for initial setup)

#### V3__create_indexes.sql
- Performance indexes (identifier search, vehicle plate lookup)
- Full-text search indexes (if using pg_trgm)
- Audit logging indexes (timestamp, user_id)

#### V4__seed_production_gates.sql (Optional, environment-specific)
- Production-only gates (excluded from dev/staging)
- Can be skipped in local development

### 3. Migration File Structure

Each migration file must:
- Start with comment header (version, description, date)
- Be idempotent where possible (use `IF NOT EXISTS`, `ON CONFLICT DO NOTHING`)
- Include rollback instructions in comments (how to undo)
- End with success message (for logging)

**Example template**:
```sql
-- Migration: V1__initial_schema.sql
-- Description: Initial database schema for eFTI Gate v2.0
-- Author: Development Team
-- Date: 2026-04-22
-- Database: PostgreSQL 14+
--
-- Rollback: DROP SCHEMA public CASCADE; (WARNING: Destroys all data)

-- =====================================================
-- TABLES
-- =====================================================

CREATE TABLE IF NOT EXISTS platforms (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    ...
);

-- =====================================================
-- CONSTRAINTS
-- =====================================================

ALTER TABLE consignments
ADD CONSTRAINT consignments_platform_fk
FOREIGN KEY (platform_id) REFERENCES platforms(id);

-- =====================================================
-- TRIGGERS
-- =====================================================

CREATE OR REPLACE FUNCTION notify_registry_change()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('registry_change', NEW.gate_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gates_notify_trigger
AFTER INSERT OR UPDATE OR DELETE ON gates
FOR EACH ROW EXECUTE FUNCTION notify_registry_change();

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

-- Log success (will appear in Flyway output)
DO $$
BEGIN
    RAISE NOTICE 'Migration V1 completed: Initial schema created';
END $$;
```

### 4. Seed Data Requirements

#### V2__seed_data.sql

**Gates (EU Registry)**:
```sql
-- Seed EU gates registry
INSERT INTO gates (id, country_code, name, endpoint_url, status, last_ping_at)
VALUES
    ('eu-ee31', 'EE', 'Estonia National eFTI Gate', 'https://efti-gate.mnt.ee/as4', 'active', NULL),
    ('eu-fi01', 'FI', 'Finland National eFTI Gate', 'https://efti.traficom.fi/as4', 'active', NULL),
    ('eu-de01', 'DE', 'Germany National eFTI Gate', 'https://efti.bast.de/as4', 'active', NULL),
    ('eu-se01', 'SE', 'Sweden National eFTI Gate', 'https://efti.trafikverket.se/as4', 'active', NULL),
    ('eu-lt01', 'LT', 'Lithuania National eFTI Gate', 'https://efti.transp.lt/as4', 'active', NULL),
    ('eu-lv01', 'LV', 'Latvia National eFTI Gate', 'https://efti.sam.gov.lv/as4', 'active', NULL),
    ('eu-pl01', 'PL', 'Poland National eFTI Gate', 'https://efti.gitd.gov.pl/as4', 'active', NULL)
ON CONFLICT (id) DO NOTHING;
```

**Platform (Development/Testing)**:
```sql
-- Example platform for development
INSERT INTO platforms (id, name, api_key_hash, certificate, status, created_at)
VALUES (
    'plt-demo-001',
    'Demo Logistics Platform',
    '$2a$10$...',  -- bcrypt hash of 'demo-platform-key-12345'
    NULL,  -- Certificate in production, NULL for dev
    'active',
    NOW()
)
ON CONFLICT (id) DO NOTHING;
```

**Authority (Development/Testing)**:
```sql
-- Example authority for development
INSERT INTO authorities (id, name, country_code, api_key_hash, status, created_at)
VALUES (
    'aut-demo-001',
    'Demo Police Authority',
    'EE',
    '$2a$10$...',  -- bcrypt hash of 'demo-authority-key-67890'
    'active',
    NOW()
)
ON CONFLICT (id) DO NOTHING;
```

**Admin User (Initial Setup)**:
```sql
-- Admin user for initial setup
-- Note: In production, use OAuth instead of API key
INSERT INTO admin_users (id, email, role, api_key_hash, created_at)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'admin@efti-gate.eu',
    'gate_admin',
    '$2a$10$...',  -- bcrypt hash of 'admin-key-initial-setup'
    NOW()
)
ON CONFLICT (id) DO NOTHING;
```

### 5. Index Creation (V3__create_indexes.sql)

```sql
-- Migration: V3__create_indexes.sql
-- Description: Create performance indexes
-- Author: Development Team
-- Date: 2026-04-22

-- =====================================================
-- CONSIGNMENTS (IDENTIFIER SEARCH)
-- =====================================================

-- Vehicle plate search (exact match)
CREATE INDEX IF NOT EXISTS idx_consignments_vehicle_plate
ON consignments (vehicle_plate)
WHERE vehicle_plate IS NOT NULL;

-- Vehicle country search
CREATE INDEX IF NOT EXISTS idx_consignments_vehicle_country
ON consignments (vehicle_country)
WHERE vehicle_country IS NOT NULL;

-- Composite index for plate + country (common query)
CREATE INDEX IF NOT EXISTS idx_consignments_vehicle_plate_country
ON consignments (vehicle_plate, vehicle_country)
WHERE vehicle_plate IS NOT NULL AND vehicle_country IS NOT NULL;

-- Full-text search on vehicle plate (fuzzy matching using pg_trgm)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_consignments_vehicle_plate_trgm
ON consignments USING gin (vehicle_plate gin_trgm_ops);

-- Platform ownership (for row-level security)
CREATE INDEX IF NOT EXISTS idx_consignments_platform_id
ON consignments (platform_id);

-- Expiration job (find expired identifiers)
CREATE INDEX IF NOT EXISTS idx_consignments_expires_at
ON consignments (expires_at)
WHERE expires_at IS NOT NULL;

-- Dataset type filter
CREATE INDEX IF NOT EXISTS idx_consignments_dataset_type
ON consignments (dataset_type);

-- =====================================================
-- DANGEROUS GOODS
-- =====================================================

-- UN number lookup
CREATE INDEX IF NOT EXISTS idx_dangerous_goods_un_number
ON dangerous_goods (un_number);

-- Consignment foreign key (join optimization)
CREATE INDEX IF NOT EXISTS idx_dangerous_goods_consignment_id
ON dangerous_goods (consignment_id);

-- =====================================================
-- DATASET REQUESTS
-- =====================================================

-- Authority requests (for row-level security)
CREATE INDEX IF NOT EXISTS idx_dataset_requests_authority_id
ON dataset_requests (authority_id);

-- Platform requests (for row-level security)
CREATE INDEX IF NOT EXISTS idx_dataset_requests_platform_id
ON dataset_requests (platform_id);

-- Identifier lookup
CREATE INDEX IF NOT EXISTS idx_dataset_requests_identifier_id
ON dataset_requests (identifier_id);

-- Request status filter
CREATE INDEX IF NOT EXISTS idx_dataset_requests_status
ON dataset_requests (status);

-- =====================================================
-- AUDIT LOGGING
-- =====================================================

-- Timestamp range queries (audit log search)
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp
ON audit_log (timestamp DESC);

-- User activity lookup
CREATE INDEX IF NOT EXISTS idx_audit_log_user_id
ON audit_log (user_id);

-- Event type filter
CREATE INDEX IF NOT EXISTS idx_audit_log_event_type
ON audit_log (event_type);

-- =====================================================
-- GATES REGISTRY
-- =====================================================

-- Status filter (find active gates for broadcast)
CREATE INDEX IF NOT EXISTS idx_gates_status
ON gates (status)
WHERE status = 'active';

-- Country code lookup
CREATE INDEX IF NOT EXISTS idx_gates_country_code
ON gates (country_code);

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'Migration V3 completed: Performance indexes created';
END $$;
```

### 6. Idempotency

**Requirement**: Migrations must be safe to re-run (in case of partial failure)

**Strategies**:
- Use `CREATE TABLE IF NOT EXISTS`
- Use `CREATE INDEX IF NOT EXISTS`
- Use `ON CONFLICT DO NOTHING` for INSERT statements
- Use `ALTER TABLE ... ADD CONSTRAINT ... IF NOT EXISTS` (PostgreSQL 11+)

**Warning**: Flyway normally prevents re-running migrations (checksum validation). Idempotency is a safety measure, not a feature.

### 7. Rollback Instructions

Each migration must document how to rollback in comments:

```sql
-- Rollback V1__initial_schema.sql:
-- DROP SCHEMA public CASCADE;
-- CREATE SCHEMA public;
-- (WARNING: Destroys ALL data in database)

-- Rollback V2__seed_data.sql:
-- DELETE FROM gates WHERE id IN ('eu-ee31', 'eu-fi01', ...);
-- DELETE FROM platforms WHERE id = 'plt-demo-001';
-- DELETE FROM authorities WHERE id = 'aut-demo-001';
-- DELETE FROM admin_users WHERE email = 'admin@efti-gate.eu';

-- Rollback V3__create_indexes.sql:
-- DROP INDEX IF EXISTS idx_consignments_vehicle_plate;
-- DROP INDEX IF EXISTS idx_consignments_vehicle_country;
-- ... (list all indexes)
```

### 8. Environment-Specific Migrations

**Problem**: Production has different seed data than dev/staging

**Solution**: Use separate migration files, applied conditionally

```sql
-- V4__seed_production_gates.sql
-- Only run in production environment

-- Check environment (requires env var or config table)
DO $$
BEGIN
    IF current_setting('app.environment', true) != 'production' THEN
        RAISE NOTICE 'Skipping production seed data (not in production environment)';
        RETURN;
    END IF;
END $$;

-- Production gates (may differ from dev)
INSERT INTO gates (id, country_code, name, endpoint_url, status, last_ping_at)
VALUES
    ('eu-ee31', 'EE', 'Estonia Production Gate', 'https://efti.mnt.ee/as4', 'active', NULL),
    ...
ON CONFLICT (id) DO UPDATE SET
    endpoint_url = EXCLUDED.endpoint_url,
    status = EXCLUDED.status;
```

## Document Structure

Create migration files in `specs/db/migrations/`:

```
specs/db/migrations/
├── README.md                     ← Migration guide
├── V1__initial_schema.sql        ← All tables, constraints, triggers
├── V2__seed_data.sql             ← Development seed data
├── V3__create_indexes.sql        ← Performance indexes
├── V4__seed_production_gates.sql ← Production gates (optional)
└── V5__add_audit_columns.sql     ← Future migration example (optional)
```

### README.md Format

```markdown
# eFTI Gate v2.0 Database Migrations

**Migration Tool**: Flyway 9.x (or Liquibase 4.x)

**Database**: PostgreSQL 14+

## Migration Files

| Version | File | Description | Safe to Rollback? |
|---------|------|-------------|-------------------|
| V1 | V1__initial_schema.sql | Initial database schema | ⚠️ No (destroys data) |
| V2 | V2__seed_data.sql | Development seed data | ✅ Yes |
| V3 | V3__create_indexes.sql | Performance indexes | ✅ Yes |
| V4 | V4__seed_production_gates.sql | Production gates (optional) | ✅ Yes |

## Running Migrations

### Using Flyway CLI

```bash
# Configure connection
export FLYWAY_URL="jdbc:postgresql://localhost:5432/efti_gate"
export FLYWAY_USER="efti_admin"
export FLYWAY_PASSWORD="secure_password"

# Apply all pending migrations
flyway migrate

# View migration status
flyway info

# Validate checksums
flyway validate
```

### Using Flyway Docker

```bash
docker run --rm \
  -v $(pwd)/migrations:/flyway/sql \
  -e FLYWAY_URL="jdbc:postgresql://db:5432/efti_gate" \
  -e FLYWAY_USER="efti_admin" \
  -e FLYWAY_PASSWORD="secure_password" \
  flyway/flyway:9-alpine migrate
```

### Using Flyway Programmatically (Java/Kotlin)

```kotlin
import org.flywaydb.core.Flyway

val flyway = Flyway.configure()
    .dataSource("jdbc:postgresql://localhost:5432/efti_gate", "efti_admin", "password")
    .locations("filesystem:./migrations")
    .load()

flyway.migrate()
```

## Rollback Procedure

Flyway does not support automatic rollback. Manual rollback required.

### Rollback V3 (Indexes)

```bash
psql -U efti_admin -d efti_gate -f rollback/V3_rollback.sql
```

**rollback/V3_rollback.sql**:
```sql
-- Rollback V3__create_indexes.sql
DROP INDEX IF EXISTS idx_consignments_vehicle_plate;
DROP INDEX IF EXISTS idx_consignments_vehicle_country;
-- ... (all indexes)
```

### Rollback V2 (Seed Data)

```sql
DELETE FROM gates WHERE id IN ('eu-ee31', 'eu-fi01', 'eu-de01', ...);
DELETE FROM platforms WHERE id = 'plt-demo-001';
DELETE FROM authorities WHERE id = 'aut-demo-001';
DELETE FROM admin_users WHERE email = 'admin@efti-gate.eu';
```

### Rollback V1 (Initial Schema)

⚠️ **WARNING**: Destroys all data

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO efti_admin;
GRANT ALL ON SCHEMA public TO public;
```

## Environment-Specific Migrations

Set environment variable before running migrations:

```bash
# Development
export FLYWAY_PLACEHOLDERS_APP_ENVIRONMENT="development"
flyway migrate

# Production
export FLYWAY_PLACEHOLDERS_APP_ENVIRONMENT="production"
flyway migrate
```

Migrations can check this variable:
```sql
IF current_setting('flyway.placeholders.app.environment', true) = 'production' THEN
    -- Production-specific logic
END IF;
```

## Troubleshooting

### Migration Checksum Mismatch

**Problem**: Modified migration file after it was applied

**Solution**:
```bash
# Repair checksums (use with caution)
flyway repair
```

### Migration Failed (Partial Application)

**Problem**: Migration failed halfway through

**Solution**:
1. Check `flyway_schema_history` table for failed migration
2. Manually fix database to match expected state
3. Mark migration as successful: `UPDATE flyway_schema_history SET success = true WHERE version = 'X';`
4. Or rollback and retry: Manually undo changes, then `flyway migrate` again

### Out-of-Order Migrations

**Problem**: Added new migration with version between existing migrations

**Solution**:
```bash
# Allow out-of-order migrations
flyway -outOfOrder=true migrate
```

## Best Practices

1. **Never modify applied migrations**: Create new migration instead
2. **Test migrations on dev database first**: Before applying to production
3. **Backup before migration**: `pg_dump efti_gate > backup.sql`
4. **Keep migrations small**: One logical change per migration
5. **Use transactions**: Wrap in `BEGIN; ... COMMIT;` where possible
6. **Document rollback**: Always include rollback instructions in comments

## Database Initialization (Fresh Install)

For fresh installation (no existing database):

```bash
# Create database
createdb -U postgres efti_gate

# Create user
psql -U postgres -c "CREATE USER efti_admin WITH PASSWORD 'secure_password';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE efti_gate TO efti_admin;"

# Run migrations
flyway migrate

# Verify
psql -U efti_admin -d efti_gate -c "SELECT * FROM flyway_schema_history;"
```

## Continuous Integration

Example GitHub Actions workflow:

```yaml
name: Database Migrations

on: [push]

jobs:
  migrate:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:14
        env:
          POSTGRES_DB: efti_gate
          POSTGRES_USER: efti_admin
          POSTGRES_PASSWORD: test_password
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v3

      - name: Run Flyway Migrations
        run: |
          docker run --rm \
            --network ${{ job.services.postgres.network }} \
            -v $(pwd)/specs/db/migrations:/flyway/sql \
            -e FLYWAY_URL="jdbc:postgresql://postgres:5432/efti_gate" \
            -e FLYWAY_USER="efti_admin" \
            -e FLYWAY_PASSWORD="test_password" \
            flyway/flyway:9-alpine migrate

      - name: Validate Schema
        run: |
          psql -h postgres -U efti_admin -d efti_gate -c "SELECT COUNT(*) FROM platforms;"
```
```

## Quality Requirements

### Zero Tolerance
- ❌ No placeholders: "TBD", "TODO", "example"
- ❌ No broken SQL: All migrations must execute without errors
- ❌ No missing semicolons: Every SQL statement ends with `;`

### Realistic Data Requirements
- **Gate IDs**: "eu-ee31", "eu-fi01", "eu-de01" (real EU gate pattern)
- **Platform IDs**: "plt-demo-001" (not "platform1", "test")
- **Authority IDs**: "aut-demo-001" (not "authority1", "test")
- **Passwords**: bcrypt hashed (not plaintext "password123")
- **Timestamps**: Use `NOW()` function (not hardcoded "2020-01-01")

### Language Requirements
- **Clear comments**: Explain why, not just what
- **Rollback instructions**: Every migration documents how to undo

### Consistency Requirements
- **Naming**: Same table/column names as schema.sql
- **Constraints**: Same foreign keys, check constraints as schema.sql
- **Data types**: Exact match with schema.sql

### Completeness Requirements
- ✅ V1 creates exact schema from schema.sql
- ✅ V2 provides realistic seed data for dev/testing
- ✅ V3 creates all necessary indexes
- ✅ README.md explains how to run migrations
- ✅ All migrations tested: `psql < V*.sql` succeeds

## Validation Criteria

Before submitting migrations:

### 1. SQL Syntax Validation
```bash
# Test each migration
for file in specs/db/migrations/V*.sql; do
    echo "Testing $file..."
    psql -U postgres -d test_db -f "$file" || exit 1
done
```

### 2. Idempotency Test
```bash
# Run migration twice, should succeed both times
psql -U postgres -d test_db -f V1__initial_schema.sql
psql -U postgres -d test_db -f V1__initial_schema.sql  # Should not fail
```

### 3. Flyway Validation
```bash
# Validate with Flyway
flyway -url=jdbc:postgresql://localhost:5432/test_db -user=postgres validate
```

### 4. Schema Comparison
```bash
# Compare V1 migration output with schema.sql
psql -U postgres -d test_db1 -f V1__initial_schema.sql
psql -U postgres -d test_db2 -f specs/db/schema.sql

# Schema should be identical (use pg_dump to compare)
pg_dump -s test_db1 > schema1.sql
pg_dump -s test_db2 > schema2.sql
diff schema1.sql schema2.sql
```

### 5. Seed Data Validation
```bash
# Verify seed data inserted
psql -U postgres -d test_db -c "SELECT COUNT(*) FROM gates;"
# Should return: 7 (or number of seeded gates)
```

## Output Format

**Directory**: `specs/db/migrations/`

**Files**:
- `README.md`: Migration guide (10-15 pages)
- `V1__initial_schema.sql`: Initial schema (500-800 lines, matches schema.sql)
- `V2__seed_data.sql`: Seed data (100-200 lines)
- `V3__create_indexes.sql`: Indexes (50-100 lines)
- `V4__seed_production_gates.sql`: Production gates (optional, 50-100 lines)

**Format**: PostgreSQL 14+ SQL syntax

## Success Criteria

Your generated migrations are complete when:

✅ **All migration files created** (V1, V2, V3, optional V4)
✅ **All SQL executes** without errors in PostgreSQL 14+
✅ **V1 matches schema.sql** exactly (schema comparison passes)
✅ **Idempotent** (can re-run without errors)
✅ **Seed data realistic** (real gate IDs, bcrypt passwords)
✅ **README.md complete** with Flyway instructions
✅ **Rollback documented** for each migration
✅ **Tested** (manually run: `psql < V*.sql` succeeds)

---

**Ready to generate?** Provide the input materials and start creating the migrations.
