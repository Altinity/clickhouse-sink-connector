-- MySQL initialization script for E2E integration tests
-- Creates comprehensive tables with all supported MySQL data types

CREATE DATABASE IF NOT EXISTS employees;
USE employees;

-- =========================================================================
-- Table 1: all_types_test — Comprehensive MySQL data types
-- =========================================================================

CREATE TABLE IF NOT EXISTS all_types_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    -- Integer types
    col_tinyint TINYINT,
    col_smallint SMALLINT,
    col_mediumint MEDIUMINT,
    col_int INT,
    col_bigint BIGINT,
    col_unsigned_int INT UNSIGNED,
    col_unsigned_bigint BIGINT UNSIGNED,
    -- Decimal types
    col_float FLOAT,
    col_double DOUBLE,
    col_decimal DECIMAL(18,6),
    -- Boolean
    col_boolean BOOLEAN,
    -- String types
    col_varchar VARCHAR(255),
    col_char CHAR(10),
    col_text TEXT,
    col_mediumtext MEDIUMTEXT,
    col_longtext LONGTEXT,
    col_enum ENUM('small', 'medium', 'large'),
    col_set SET('a', 'b', 'c', 'd'),
    -- Binary types
    col_binary BINARY(16),
    col_varbinary VARBINARY(255),
    col_blob BLOB,
    -- Date/Time types
    col_date DATE,
    col_time TIME,
    col_datetime DATETIME(6),
    col_timestamp TIMESTAMP(6) NULL DEFAULT NULL,
    col_year YEAR,
    -- JSON
    col_json JSON,
    -- Bit
    col_bit BIT(8)
) ENGINE=InnoDB;

-- Row 1: Insert test data with typical positive values
INSERT INTO all_types_test VALUES (
    1,
    127, 32767, 8388607, 2147483647, 9223372036854775807, 4294967295, 18446744073709551615,
    3.14, 2.718281828459045, 123456.789012,
    TRUE,
    'Hello World', 'ABCDEFGHIJ', 'Lorem ipsum', 'Medium text', 'Long text',
    'medium', 'a,b,c',
    X'0102030405060708090A0B0C0D0E0F10', X'DEADBEEF', X'CAFEBABE',
    '2024-01-15', '14:30:00', '2024-01-15 14:30:00.123456', '2024-01-15 14:30:00.654321', 2024,
    '{"key": "value", "number": 42}',
    b'10101010'
);

-- Row 2: Insert row with NULLs (only id is populated)
INSERT INTO all_types_test (id) VALUES (2);

-- Row 3: Insert row with edge case / min values
INSERT INTO all_types_test VALUES (
    3,
    -128, -32768, -8388608, -2147483648, -9223372036854775808, 0, 0,
    -3.14, -2.718281828459045, -999999.999999,
    FALSE,
    '', '          ', '', '', '',
    'small', '',
    X'00000000000000000000000000000000', X'00', X'00',
    '1970-01-02', '00:00:00', '1970-01-02 00:00:00.000000', '1970-01-02 00:00:00.000000', 1970,
    '{}',
    b'00000000'
);

-- =========================================================================
-- Table 2: snapshot_test — Simple table for snapshot verification
-- =========================================================================

CREATE TABLE IF NOT EXISTS snapshot_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    value INT
) ENGINE=InnoDB;

INSERT INTO snapshot_test VALUES (1, 'alpha', 100);
INSERT INTO snapshot_test VALUES (2, 'beta', 200);
INSERT INTO snapshot_test VALUES (3, 'gamma', 300);
INSERT INTO snapshot_test VALUES (4, 'delta', 400);
INSERT INTO snapshot_test VALUES (5, 'epsilon', 500);

-- =========================================================================
-- Table 3: ddl_test — Table for DDL operation tests
-- =========================================================================

CREATE TABLE IF NOT EXISTS ddl_test (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    value INT
) ENGINE=InnoDB;

INSERT INTO ddl_test VALUES (1, 'initial', 100);
INSERT INTO ddl_test VALUES (2, 'setup', 200);
