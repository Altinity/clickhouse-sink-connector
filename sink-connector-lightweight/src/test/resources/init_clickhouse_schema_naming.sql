-- ClickHouse initialisation for PostgresSchemaAwareNamingIT
-- Pre-creates databases needed by the schema-aware naming test.
--
-- All 4 naming configs enabled:
--   clickhouse.table.schema.prefix      = true
--   clickhouse.common.schema.template   = "__{{ schema }}__"
--   clickhouse.database.schema.suffix   = true
--   clickhouse.common.database.prefix   = "dev_"
--
-- Source: PostgreSQL database="public", schema="public", table="tm"
-- Expected ClickHouse database: dev_public__public__
--   (prefix "dev_" + raw db "public" + suffix "__public__")
-- Expected ClickHouse table:    __public__tm
--   (schema prefix resolved via template)

CREATE DATABASE IF NOT EXISTS `dev_public__public__`;

-- Offset / schema-history tables (required by Debezium embedded engine)
CREATE DATABASE IF NOT EXISTS altinity_sink_connector;
