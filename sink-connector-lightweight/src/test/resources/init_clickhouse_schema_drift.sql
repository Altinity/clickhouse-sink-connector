-- ClickHouse initialisation for PostgresSchemaDriftIT
-- Creates the minimal infrastructure needed by the integration test.

CREATE database IF NOT EXISTS public;

-- Initial target table for schema drift test.
-- Only the columns that exist at test-start time are created here.
-- The ALTER TABLE ADD COLUMN executed by the drift-detector will add new columns.
CREATE TABLE IF NOT EXISTS public.schema_drift_test
(
    `id`    Int32,
    `name`  Nullable(String),
    `_version` UInt64
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY id;

-- Offset / schema-history tables (required by Debezium embedded engine)
CREATE DATABASE IF NOT EXISTS altinity_sink_connector;

CREATE TABLE IF NOT EXISTS altinity_sink_connector.replica_source_info
(
    `id`                String,
    `offset_key`        String,
    `offset_val`        String,
    `record_insert_ts`  DateTime,
    `record_insert_seq` UInt64,
    `_version`          UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY id
SETTINGS index_granularity = 8192;
