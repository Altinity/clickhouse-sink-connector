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

INSERT INTO altinity_sink_connector.replica_source_info (id,offset_key,offset_val,record_insert_ts,record_insert_seq) VALUES
('1e34dce5-f61c-4fe7-ba83-0a6805a6ea8e','["altinity_sink_connector",{"server":"embeddedconnector"}]','{"transaction_id":null,"ts_sec":1687277615,"file":"mysql-bin.000003","pos":1144937,"gtids":"4ada0375-0f7e-11ee-afd1-0242c0a85003:1-2418","row":1,"server_id":189,"event":2}','2023-06-20 16:13:35',3);

INSERT INTO altinity_sink_connector.replica_source_info (id,offset_key,offset_val,record_insert_ts,record_insert_seq) VALUES
('f15e5c94-4338-409b-b3ed-5044fa20e38e','["company-1",{"server":"embeddedconnector"}]','{"transaction_id":null,"ts_sec":1687278006,"file":"mysql-bin.000003","pos":1156385,"gtids":"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442","row":1,"server_id":266,"event":2}','2023-06-20 16:20:07',3);

-- PostgreSQL
--INSERT INTO altinity_sink_connector.replica_source_info
--(id, offset_key, offset_val, record_insert_ts, record_insert_seq)
--VALUES('03750062-c862-48c5-9f37-451c0d33511b', '["\"engine\"",{"server":"embeddedconnector"}]', '{"transaction_id":null,"lsn_proc":27485360,"messageType":"UPDATE","lsn":27485360,"txId":743,"ts_usec":1687876724804733}', 2023-06-27 14:38:45.000, 1);

--CREATE TABLE employees.rmt_test
--(
--  `id` Int64,
--  `consultation_id` Int64,
--  `recomendation` Nullable(String),
--  `create_date` DateTime64(6),
--  `is_deleted` UInt8,
--  `_version` UInt64
--)
--ENGINE = ReplacingMergeTree(_version, is_deleted)
--ORDER BY id
