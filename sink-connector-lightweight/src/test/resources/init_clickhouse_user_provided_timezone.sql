--CREATE USER 'ch_user' IDENTIFIED WITH plaintext_password BY 'root';
--SET allow_introspection_functions=1;
--GRANT ALL ON . TO 'ch_user' WITH GRANT OPTION
--
--
-- CREATE USER ch_user IDENTIFIED WITH plaintext_password BY 'password';
CREATE database datatypes;
CREATE database employees;
CREATE database public;
CREATE database project;

CREATE TABLE project.items
(
  `price` Int64,
  `name` String,
  `_id` String,
  `uuid` String,
  `_sign` Int8,
  `_version` UInt64
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY _id;


CREATE TABLE public.protocol_test
(
  `id` Int64,
  `consultation_id` Int64,
  `recomendation` Nullable(String),
`create_date` DateTime64(6),
`_sign` Int8,
`_version` UInt64
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY id;

CREATE DATABASE altinity_sink_connector;

CREATE TABLE altinity_sink_connector.replica_source_info
(
`id` String,
`offset_key` String,
`offset_val` String,
`record_insert_ts` DateTime,
`record_insert_seq` UInt64,
`_version` UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY id
SETTINGS index_granularity = 8192;

USE employees;
CREATE TABLE employees.dt
(
`timestamp` DateTime('Asia/Istanbul'),
`json` String,
`event_id` UInt8,
`sign` Int8,
`_version` UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9)),
`_updated` DateTime MATERIALIZED now()
)
ENGINE = ReplacingMergeTree(_version) ORDER by event_id;

-- DROP DATABASE IF EXISTS altinity_sink_connector;
-- CREATE DATABASE IF NOT EXISTS altinity_sink_connector;
--
-- CREATE TABLE IF NOT EXISTS altinity_sink_connector.replicate_schema_history (
--                                                                                 id VARCHAR(36) NOT NULL,
--     history_data TEXT,
--     history_data_seq INTEGER,
--     record_insert_ts TIMESTAMP NOT NULL,
--     record_insert_seq INTEGER NOT NULL
--     );


CREATE TABLE `system`.replica_source_info
(
    `id` String,
    `offset_key` String,
    `offset_val` String,
    `record_insert_ts` DateTime,
    `record_insert_seq` UInt64,
    `_version` UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))
)
    ENGINE = ReplacingMergeTree(_version)
ORDER BY id
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS altinity_sink_connector.replicate_schema_history (
                                                                                `id` String,
                                                                                `history_data` String,
                                                                                `history_data_seq` Int64,
                                                                                `record_insert_ts` DateTime,
                                                                                `record_insert_seq` UInt64
)
    ENGINE = MergeTree
    PARTITION BY toYYYYMM(record_insert_ts)
    ORDER BY (record_insert_seq)
    SETTINGS index_granularity = 8192;