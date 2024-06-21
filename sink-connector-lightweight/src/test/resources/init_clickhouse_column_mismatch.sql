CREATE database datatypes;
CREATE database employees;
CREATE database public;
CREATE database project;

CREATE TABLE employees.`newtable`(`col1` String NOT NULL,`col2` Int32 NULL,`_version` UInt64,`_sign` UInt8)
    Engine=ReplacingMergeTree(_version,_sign) PRIMARY KEY(col1) ORDER BY(col1)