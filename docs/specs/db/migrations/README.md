# eFTI Gate v2.0 Database Migrations

**Migration Tool**: Flyway 9.x (preferred) or Liquibase 4.x

**Database**: PostgreSQL 14+

**Source of truth**: `gate/db/*.sql` (Liquibase changesets used in current gate)

---

## Migration Files

| Version | File | Description | Safe to Rollback? |
|---------|------|-------------|-------------------|
| V1 | [V1__initial_schema.sql](V1__initial_schema.sql) | All tables, functions, triggers, permissions | ⚠️ No (destroys all data) |
| V2 | [V2__seed_data.sql](V2__seed_data.sql) | Development seed data (gates, platforms, authorities, users) | ✅ Yes |
| V3 | [V3__create_indexes.sql](V3__create_indexes.sql) | Performance indexes (identifiers, consignments, gates, users) | ✅ Yes |
| V4 | [V4__seed_production_gates.sql](V4__seed_production_gates.sql) | Production gate certificates (optional, env-guarded) | ✅ Yes |

---

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
  -v $(pwd)/docs/specs/db/migrations:/flyway/sql \
  -e FLYWAY_URL="jdbc:postgresql://db:5432/efti_gate" \
  -e FLYWAY_USER="efti_admin" \
  -e FLYWAY_PASSWORD="secure_password" \
  flyway/flyway:9-alpine migrate
```

### Using Flyway Programmatically (Kotlin)

```kotlin
import org.flywaydb.core.Flyway

val flyway = Flyway.configure()
    .dataSource("jdbc:postgresql://localhost:5432/efti_gate", "efti_admin", "password")
    .locations("filesystem:./docs/specs/db/migrations")
    .load()

flyway.migrate()
```

### Using psql directly (without Flyway)

```bash
# Fresh database setup
createdb -U postgres efti_gate
psql -U postgres -c "CREATE USER efti_admin WITH PASSWORD 'secure_password';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE efti_gate TO efti_admin;"

# Apply migrations in order
psql -U efti_admin -d efti_gate -f V1__initial_schema.sql
psql -U efti_admin -d efti_gate -f V2__seed_data.sql
psql -U efti_admin -d efti_gate -f V3__create_indexes.sql

# Verify
psql -U efti_admin -d efti_gate -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;"
```

---

## Migration Details

### V1 — Initial Schema

Creates all tables, functions and triggers:

| Table | Purpose |
|-------|---------|
| `change_history` | Audit trail of all row-level changes |
| `gates` | Registry of known EU eFTI gates |
| `platforms` | Freight platform configurations |
| `authorities` | Competent authority configurations |
| `users` | All users (admin, platform operators, authority users) |
| `consignments` | Consignment datasets registered by platforms |
| `identifiers` | Searchable identifiers (vehicle plates etc.) per consignment |
| `async_responses` | Temporary storage for async eDelivery responses |

Key functions:
- `set_app_user(uuid)` — sets current user context for audit logging
- `get_app_user()` — retrieves current user from session config
- `add_change_history()` — trigger function that records every column change

### V2 — Seed Data

Development/testing data matching what `compose.yml` expects:

| Entity | ID | Description |
|--------|----|-------------|
| Gate | eu-fi01..eu-pl01 | 6 EU peer gates with placeholder certificates |
| Platform | demo | HTTP platform (demo-platform:8070) |
| Platform | demo-edelivery | eDelivery platform with real demo certificate |
| Authority | demo | Estonian demo authority (subsets: full) |
| User | admin@efti.eu | Gate admin (isAdmin=true) |
| User | demo-platform@efti.eu | Platform operator (role: PLATFORM/demo) |
| User | demo-authority@efti.eu | Authority user (role: AUTHORITY/demo) |

**User credentials**: Stored as SHA-256 hashes. Actual secrets are set during
development environment setup — see `.env` file or `docs/QUICK-START.md`.

### V3 — Indexes

Performance indexes for the main query patterns:

| Index | Table | Columns | Purpose |
|-------|-------|---------|---------|
| `idx_identifiers_id` | identifiers | id | Exact identifier search |
| `idx_identifiers_id_country` | identifiers | id, countryCode | Plate + country search |
| `idx_identifiers_id_trgm` | identifiers | id (GIN) | Fuzzy plate search (ILIKE) |
| `idx_consignments_platform` | consignments | platformId | Platform ownership filter |
| `idx_consignments_delivered` | consignments | createdAt (partial) | Expiry job (WHERE deliveredAt IS NULL) |
| `idx_gates_status` | gates | status (partial) | Find ONLINE gates for broadcast |
| `idx_users_roles` | users | roles (GIN) | Find users by role |

### V4 — Production Gates (Optional)

Environment-guarded migration that updates gate certificates to production values.

Apply in production:
```bash
# Set environment context first
psql -U efti_admin -d efti_gate -c "SET app.environment = 'production';"
psql -U efti_admin -d efti_gate -f V4__seed_production_gates.sql
```

Or via Flyway:
```bash
flyway -placeholders.app.environment=production migrate
```

**Important**: Replace all `REPLACE_WITH_ACTUAL_*_PRODUCTION_CERTIFICATE` placeholders
with real PEM certificates before applying.

---

## Rollback Procedures

Flyway does not support automatic rollback. Manual rollback is required.

### Rollback V3 (Indexes)

```sql
DROP INDEX IF EXISTS idx_identifiers_id;
DROP INDEX IF EXISTS idx_identifiers_country;
DROP INDEX IF EXISTS idx_identifiers_id_country;
DROP INDEX IF EXISTS idx_identifiers_id_trgm;
DROP INDEX IF EXISTS idx_consignments_platform;
DROP INDEX IF EXISTS idx_consignments_delivered;
DROP INDEX IF EXISTS idx_consignments_created;
DROP INDEX IF EXISTS idx_consignments_mode;
DROP INDEX IF EXISTS idx_consignments_dangerous;
DROP INDEX IF EXISTS idx_async_responses_created;
DROP INDEX IF EXISTS idx_change_history_changed_at;
DROP INDEX IF EXISTS idx_change_history_user;
DROP INDEX IF EXISTS idx_gates_status;
DROP INDEX IF EXISTS idx_gates_country;
DROP INDEX IF EXISTS idx_users_roles;
DROP EXTENSION IF EXISTS pg_trgm;
```

### Rollback V2 (Seed Data)

```sql
DELETE FROM users WHERE id IN (
  '175791a3-da82-11f0-b10c-3c9c0f2eb459',
  '502d74a0-eb03-11f0-b86c-3c9c0f2eb459',
  '04fa30eb-eb08-11f0-b506-3c9c0f2eb459'
);
DELETE FROM authorities WHERE id = 'demo';
DELETE FROM platforms WHERE id IN ('demo', 'demo-edelivery');
DELETE FROM gates WHERE id IN ('eu-fi01', 'eu-de01', 'eu-se01', 'eu-lt01', 'eu-lv01', 'eu-pl01');
```

### Rollback V1 (Initial Schema)

⚠️ **WARNING: Destroys all data**

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO efti_admin;
GRANT ALL ON SCHEMA public TO public;
```

---

## Environment-Specific Configuration

```bash
# Development (default): V1 + V2 + V3
flyway migrate

# Production: V1 + V2 + V3 + V4 (with real certificates in V4)
# Before running, update V4 certificate placeholders with actual values
flyway -placeholders.app.environment=production migrate
```

---

## Troubleshooting

### Migration checksum mismatch

**Cause**: A migration file was modified after being applied.

```bash
# Repair Flyway checksums (use with caution — only if you intentionally modified a migration)
flyway repair
```

### Migration failed mid-way

1. Check `flyway_schema_history` table for failed entry
2. Manually fix database state
3. Mark migration resolved: `UPDATE flyway_schema_history SET success = true WHERE version = 'X';`

### Out-of-order migrations

```bash
flyway -outOfOrder=true migrate
```

### `role "app" does not exist`

V1 creates the `app` role only if it doesn't exist. If you're running as the `app` user itself, this is a no-op. Ensure the `app` role is created before granting permissions, or run V1 as a superuser (`postgres`).

---

## Continuous Integration

Add Flyway to your GitHub Actions pipeline:

```yaml
name: Database Migrations CI

on: [push, pull_request]

jobs:
  migrate:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:14-alpine
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
      - uses: actions/checkout@v4

      - name: Run Flyway Migrations
        run: |
          docker run --rm \
            --network ${{ job.services.postgres.network }} \
            -v $(pwd)/docs/specs/db/migrations:/flyway/sql \
            -e FLYWAY_URL="jdbc:postgresql://postgres:5432/efti_gate" \
            -e FLYWAY_USER="efti_admin" \
            -e FLYWAY_PASSWORD="test_password" \
            flyway/flyway:9-alpine migrate

      - name: Validate schema
        run: |
          docker exec $(docker ps -q -f "ancestor=postgres:14-alpine") \
            psql -U efti_admin -d efti_gate \
            -c "SELECT COUNT(*) AS tables FROM pg_tables WHERE schemaname = 'public';"
```

---

## Best Practices

1. **Never modify applied migrations** — create a new `V5__...sql` instead
2. **Test on dev database first** — before applying to staging or production
3. **Backup before migration** — `pg_dump efti_gate > backup_$(date +%Y%m%d).sql`
4. **Keep migrations small** — one logical change per file
5. **Document rollback** — every migration has rollback instructions in comments
6. **Idempotent where possible** — use `IF NOT EXISTS`, `ON CONFLICT DO NOTHING`
