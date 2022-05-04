CREATE DATABASE IF NOT EXISTS test;
USE test;
CREATE TABLE IF NOT EXISTS employees
(
    `emp_no` Int32,
    `birth_date` Date32,
    `first_name` String,
    `last_name` String,
    `gender` String,
    `hire_date` Date32,
    `_offset` Nullable(UInt64),
    `_key` Nullable(String),
    `_topic` Nullable(String),
    `_partition` Nullable(UInt64),
    `_timestamp` Nullable(DateTime),
    `_timestamp_ms` Nullable(DateTime64(3))
)
ENGINE = MergeTree
PRIMARY KEY emp_no
ORDER BY emp_no;
