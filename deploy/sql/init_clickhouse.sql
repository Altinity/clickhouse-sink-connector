use test;

CREATE TABLE employees
(
    `emp_no` Int32,
    `birth_date` Date32,
    `first_name` String,
    `last_name` String,
    `gender` String,
    `hire_date` Date32,
    `_offset` UInt64,
    `_key` String,
    `_topic` String,
    `_partition` UInt64,
    `_timestamp` DateTime,
    `_timestamp_ms` DateTime64(3)
)
ENGINE = MergeTree
PRIMARY KEY emp_no
ORDER BY emp_no