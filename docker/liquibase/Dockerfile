FROM liquibase/liquibase:4.29.2
# The PostgreSQL JDBC driver ships bundled in the image since 4.25
# (/liquibase/internal/lib/postgresql.jar). `lpm add postgresql` is redundant and
# has started failing in CI with "Checksum validation failed" from lpm's download.
COPY DSL/Liquibase/ /liquibase/
