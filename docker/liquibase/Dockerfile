FROM liquibase/liquibase:4.29.2
# PostgreSQL JDBC driver is bundled since 4.25; lpm add fails in CI on a checksum download.
COPY DSL/Liquibase/ /liquibase/
