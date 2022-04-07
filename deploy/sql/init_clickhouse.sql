use test;

CREATE TABLE employees
(
    `emp_no` Int32,
    `birth_date` Date32,
    `first_name` String,
    `last_name` String,
    `gender` String,
    `hire_date` Date32
)
ENGINE = MergeTree
PRIMARY KEY emp_no
ORDER BY emp_no