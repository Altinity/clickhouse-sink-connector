-- Phase 3: DDL Operations Integration Test Scenarios
-- Tests for DROP COLUMN, RENAME COLUMN, MODIFY COLUMN, DROP TABLE, RENAME TABLE
-- Run these tests against a MySQL source with ClickHouse sink connector

-- ============================================================================
-- Setup: Create Initial Test Tables
-- ============================================================================

-- Test table for column operations
CREATE TABLE IF NOT EXISTS ddl_test_columns (
    id INT PRIMARY KEY AUTO_INCREMENT,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    email VARCHAR(100),
    age INT,
    salary DECIMAL(10,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Test table for table operations
CREATE TABLE IF NOT EXISTS ddl_test_tables (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100),
    value INT
);

-- Insert sample data
INSERT INTO ddl_test_columns (first_name, last_name, email, age, salary) VALUES
    ('John', 'Doe', 'john.doe@example.com', 30, 50000.00),
    ('Jane', 'Smith', 'jane.smith@example.com', 28, 55000.00),
    ('Bob', 'Johnson', 'bob.johnson@example.com', 35, 60000.00);

INSERT INTO ddl_test_tables (name, value) VALUES
    ('Record 1', 100),
    ('Record 2', 200),
    ('Record 3', 300);

-- ============================================================================
-- Test 1: ADD COLUMN (Already Supported - Baseline Test)
-- ============================================================================
-- Expected: Column added to ClickHouse
-- Verification: SELECT * FROM ddl_test_columns in ClickHouse should show middle_name

ALTER TABLE ddl_test_columns ADD COLUMN middle_name VARCHAR(50);

-- Update some records to verify column works
UPDATE ddl_test_columns SET middle_name = 'M' WHERE id = 1;
UPDATE ddl_test_columns SET middle_name = 'A' WHERE id = 2;

-- ============================================================================
-- Test 2: DROP COLUMN
-- ============================================================================
-- Expected Behavior (with DROP_COLUMN_BEHAVIOR=RENAME):
--   - Column renamed to _deleted_middle_name_<timestamp> in ClickHouse
--   - No data loss during transition
--
-- Expected Behavior (with DROP_COLUMN_BEHAVIOR=DROP):
--   - Column removed from ClickHouse
--
-- Verification: Check ClickHouse schema after operation

ALTER TABLE ddl_test_columns DROP COLUMN middle_name;

-- Insert new record to verify schema sync
INSERT INTO ddl_test_columns (first_name, last_name, email, age, salary) VALUES
    ('Alice', 'Williams', 'alice.williams@example.com', 32, 58000.00);

-- ============================================================================
-- Test 3: MODIFY COLUMN - Safe Type Change (Int to BigInt)
-- ============================================================================
-- Expected: age column type changed from Int32 to Int64 in ClickHouse
-- Verification: DESCRIBE ddl_test_columns in ClickHouse

ALTER TABLE ddl_test_columns MODIFY COLUMN age BIGINT;

-- Insert value that requires BIGINT
INSERT INTO ddl_test_columns (first_name, last_name, email, age, salary) VALUES
    ('Charlie', 'Brown', 'charlie.brown@example.com', 2147483647, 65000.00);

-- ============================================================================
-- Test 4: MODIFY COLUMN - Safe Type Change (VARCHAR size increase)
-- ============================================================================
-- Expected: email column size increased in ClickHouse
-- Verification: Can insert longer email addresses

ALTER TABLE ddl_test_columns MODIFY COLUMN email VARCHAR(255);

-- Insert record with longer email
INSERT INTO ddl_test_columns (first_name, last_name, email, age, salary) VALUES
    ('David', 'Wilson', 'david.wilson.with.a.very.long.email.address@example.com', 29, 52000.00);

-- ============================================================================
-- Test 5: MODIFY COLUMN - Safe Type Change (DECIMAL precision increase)
-- ============================================================================
-- Expected: salary column precision increased
-- Verification: Can store larger values

ALTER TABLE ddl_test_columns MODIFY COLUMN salary DECIMAL(12,2);

-- Insert record with larger salary
INSERT INTO ddl_test_columns (first_name, last_name, email, age, salary) VALUES
    ('Eve', 'Davis', 'eve.davis@example.com', 40, 999999999.99);

-- ============================================================================
-- Test 6: RENAME COLUMN
-- ============================================================================
-- Expected Behavior (with RENAME_COLUMN_BEHAVIOR=RENAME):
--   - Column renamed in ClickHouse to match MySQL
--   - Data preserved
--
-- Verification: SELECT full_name FROM ddl_test_columns in ClickHouse

ALTER TABLE ddl_test_columns CHANGE COLUMN first_name full_name VARCHAR(50);

-- Update record to verify renamed column works
UPDATE ddl_test_columns SET full_name = 'Johnny' WHERE id = 1;

-- ============================================================================
-- Test 7: Multiple Column Operations in Sequence
-- ============================================================================
-- Test that multiple DDL operations work correctly

-- Add a temporary column
ALTER TABLE ddl_test_columns ADD COLUMN temp_column INT;

-- Modify it
ALTER TABLE ddl_test_columns MODIFY COLUMN temp_column BIGINT;

-- Rename it
ALTER TABLE ddl_test_columns CHANGE COLUMN temp_column permanent_column BIGINT;

-- Insert data
UPDATE ddl_test_columns SET permanent_column = 12345 WHERE id = 1;

-- Drop it
ALTER TABLE ddl_test_columns DROP COLUMN permanent_column;

-- ============================================================================
-- Test 8: RENAME TABLE
-- ============================================================================
-- Expected Behavior (with RENAME_TABLE_BEHAVIOR enabled):
--   - Table renamed in ClickHouse
--   - Connector updates mappings
--
-- Verification: Table exists as ddl_test_tables_renamed in ClickHouse

RENAME TABLE ddl_test_tables TO ddl_test_tables_renamed;

-- Insert into renamed table
INSERT INTO ddl_test_tables_renamed (name, value) VALUES
    ('Record 4', 400);

-- ============================================================================
-- Test 9: DROP TABLE
-- ============================================================================
-- Expected Behavior (with DROP_TABLE_BEHAVIOR=RENAME):
--   - Table renamed to _deleted_ddl_test_tables_renamed_<timestamp>
--   - No data loss
--
-- Expected Behavior (with DROP_TABLE_BEHAVIOR=DROP):
--   - Table removed from ClickHouse
--
-- Verification: Check ClickHouse system.tables

DROP TABLE ddl_test_tables_renamed;

-- ============================================================================
-- Test 10: TRUNCATE TABLE
-- ============================================================================
-- Create a temporary table for truncate test
CREATE TABLE ddl_test_truncate (
    id INT PRIMARY KEY AUTO_INCREMENT,
    data VARCHAR(100)
);

INSERT INTO ddl_test_truncate (data) VALUES ('Row 1'), ('Row 2'), ('Row 3');

-- Truncate the table
TRUNCATE TABLE ddl_test_truncate;

-- Verification: Table should be empty in ClickHouse (or rows marked as deleted)
-- Insert new data after truncate
INSERT INTO ddl_test_truncate (data) VALUES ('New Row 1'), ('New Row 2');

-- ============================================================================
-- Test 11: Reserved Keywords Handling
-- ============================================================================
-- Test that reserved SQL keywords are properly escaped

CREATE TABLE ddl_test_keywords (
    `id` INT PRIMARY KEY AUTO_INCREMENT,
    `user` VARCHAR(50),
    `select` VARCHAR(50),
    `table` VARCHAR(50),
    `timestamp` BIGINT
);

INSERT INTO ddl_test_keywords (`user`, `select`, `table`, `timestamp`) VALUES
    ('test_user', 'test_select', 'test_table', 1234567890);

-- Verify reserved keywords work correctly
UPDATE ddl_test_keywords SET `user` = 'updated_user' WHERE id = 1;

-- ============================================================================
-- Test 12: NULL and NOT NULL Constraints
-- ============================================================================
-- Test modifying nullability

CREATE TABLE ddl_test_nullability (
    id INT PRIMARY KEY AUTO_INCREMENT,
    nullable_col VARCHAR(50) NULL,
    non_null_col VARCHAR(50) NOT NULL
);

INSERT INTO ddl_test_nullability (nullable_col, non_null_col) VALUES
    (NULL, 'required'),
    ('optional', 'required2');

-- Modify column to add NOT NULL (challenging operation)
-- Note: This may fail if NULL values exist - test error handling
-- ALTER TABLE ddl_test_nullability MODIFY COLUMN nullable_col VARCHAR(50) NOT NULL;

-- ============================================================================
-- Test 13: Complex Type Changes
-- ============================================================================
-- Test various safe type conversions

CREATE TABLE ddl_test_type_changes (
    id INT PRIMARY KEY AUTO_INCREMENT,
    int8_col TINYINT,
    int16_col SMALLINT,
    int32_col INT,
    float_col FLOAT,
    date_col DATE,
    datetime_col DATETIME
);

INSERT INTO ddl_test_type_changes VALUES
    (1, 100, 10000, 1000000, 123.45, '2024-01-01', '2024-01-01 12:00:00');

-- Safe conversions: widening only
ALTER TABLE ddl_test_type_changes MODIFY COLUMN int8_col SMALLINT;
ALTER TABLE ddl_test_type_changes MODIFY COLUMN int16_col INT;
ALTER TABLE ddl_test_type_changes MODIFY COLUMN int32_col BIGINT;
ALTER TABLE ddl_test_type_changes MODIFY COLUMN float_col DOUBLE;
ALTER TABLE ddl_test_type_changes MODIFY COLUMN date_col DATETIME;

-- Insert data to verify changes
INSERT INTO ddl_test_type_changes VALUES
    (2, 200, 20000, 2000000, 456.78, '2024-02-01 13:00:00', '2024-02-01 13:00:00');

-- ============================================================================
-- Test 14: Concurrent DML During DDL
-- ============================================================================
-- Test that data operations continue working during schema changes

CREATE TABLE ddl_test_concurrent (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50),
    value INT
);

INSERT INTO ddl_test_concurrent (name, value) VALUES ('Initial', 100);

-- Add column while inserting data
ALTER TABLE ddl_test_concurrent ADD COLUMN description TEXT;
INSERT INTO ddl_test_concurrent (name, value, description) VALUES ('With Description', 200, 'Test description');

-- Rename column while updating data
UPDATE ddl_test_concurrent SET value = 150 WHERE id = 1;
ALTER TABLE ddl_test_concurrent CHANGE COLUMN name item_name VARCHAR(50);
UPDATE ddl_test_concurrent SET item_name = 'Updated Name' WHERE id = 1;

-- ============================================================================
-- Test 15: Edge Cases
-- ============================================================================

-- Very long column names
CREATE TABLE ddl_test_edge_cases (
    id INT PRIMARY KEY AUTO_INCREMENT,
    very_long_column_name_that_might_cause_issues_in_some_systems VARCHAR(100)
);

ALTER TABLE ddl_test_edge_cases 
    CHANGE COLUMN very_long_column_name_that_might_cause_issues_in_some_systems 
                  even_longer_renamed_column_name_for_testing_purposes VARCHAR(100);

-- Special characters in table names (if supported)
-- CREATE TABLE `test-table-with-dashes` (id INT PRIMARY KEY);
-- RENAME TABLE `test-table-with-dashes` TO `renamed-test-table`;
-- DROP TABLE `renamed-test-table`;

-- ============================================================================
-- Verification Queries (Run in ClickHouse)
-- ============================================================================

-- After running all tests, execute these in ClickHouse to verify:
/*

-- Check all tables exist and have correct schemas
SHOW TABLES;

-- Verify ddl_test_columns schema
DESCRIBE TABLE ddl_test_columns;

-- Verify data integrity
SELECT COUNT(*) FROM ddl_test_columns;
SELECT * FROM ddl_test_columns ORDER BY id;

-- Check for renamed/deleted columns
SHOW CREATE TABLE ddl_test_columns;

-- Verify type changes took effect
SELECT 
    name, 
    type 
FROM system.columns 
WHERE table = 'ddl_test_columns' 
ORDER BY name;

-- Check dropped tables (should be renamed to _deleted_* if RENAME behavior)
SELECT 
    name 
FROM system.tables 
WHERE name LIKE '_deleted_%';

-- Verify all data was replicated correctly
SELECT 
    database,
    table,
    rows
FROM system.tables
WHERE database = current_database()
ORDER BY table;

*/

-- ============================================================================
-- Cleanup (Optional)
-- ============================================================================

-- Uncomment to clean up test tables:
-- DROP TABLE IF EXISTS ddl_test_columns;
-- DROP TABLE IF EXISTS ddl_test_tables;
-- DROP TABLE IF EXISTS ddl_test_tables_renamed;
-- DROP TABLE IF EXISTS ddl_test_truncate;
-- DROP TABLE IF EXISTS ddl_test_keywords;
-- DROP TABLE IF EXISTS ddl_test_nullability;
-- DROP TABLE IF EXISTS ddl_test_type_changes;
-- DROP TABLE IF EXISTS ddl_test_concurrent;
-- DROP TABLE IF EXISTS ddl_test_edge_cases;
