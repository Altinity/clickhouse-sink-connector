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

CREATE TABLE employees.rmt_test
(
  `id` Int64,
  `consultation_id` Int64,
  `recomendation` Nullable(String),
  `create_date` DateTime64(6),
  `is_deleted` UInt8,
  `_version` UInt64
)
ENGINE = ReplacingMergeTree(_version, is_deleted)
ORDER BY id
