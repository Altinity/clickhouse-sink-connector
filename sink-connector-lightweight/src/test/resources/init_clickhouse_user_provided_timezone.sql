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
SETTINGS index_granularity = 8198;

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