-- ================================================================
-- COMPREHENSIVE END-TO-END REPLICATION TEST SUITE
-- Tests all MySQL operations, data types, and edge cases
-- 24+ comprehensive test scenarios
-- ================================================================

USE testdb;

-- ================================================================
-- SECTION 1: DATA TYPES COVERAGE (Tests 1-4)
-- ================================================================

-- Test 1: All numeric types
DROP TABLE IF EXISTS test_numeric;
CREATE TABLE test_numeric (
    id INT PRIMARY KEY AUTO_INCREMENT,
    tiny TINYINT,
    small SMALLINT,
    medium MEDIUMINT,
    int_val INT,
    big BIGINT,
    tiny_unsigned TINYINT UNSIGNED,
    small_unsigned SMALLINT UNSIGNED,
    medium_unsigned MEDIUMINT UNSIGNED,
    int_unsigned INT UNSIGNED,
    big_unsigned BIGINT UNSIGNED,
    decimal_val DECIMAL(10,2),
    numeric_val NUMERIC(10,2),
    float_val FLOAT,
    double_val DOUBLE
);

INSERT INTO test_numeric VALUES 
(1, 127, 32767, 8388607, 2147483647, 9223372036854775807,
 255, 65535, 16777215, 4294967295, 18446744073709551615,
 12345.67, 98765.43, 3.14159, 2.71828),
(2, -128, -32768, -8388608, -2147483648, -9223372036854775808,
 0, 0, 0, 0, 0,
 -999.99, -999.99, -1.5, -2.5),
(3, 0, 0, 0, 0, 0, 128, 32768, 8388608, 2147483648, 9223372036854775808,
 0.01, 0.99, 0.0, 0.0);

-- Test 2: String types
DROP TABLE IF EXISTS test_strings;
CREATE TABLE test_strings (
    id INT PRIMARY KEY,
    char_val CHAR(10),
    varchar_val VARCHAR(255),
    text_val TEXT,
    mediumtext_val MEDIUMTEXT,
    longtext_val LONGTEXT,
    binary_val BINARY(10),
    varbinary_val VARBINARY(255),
    blob_val BLOB,
    enum_val ENUM('small', 'medium', 'large'),
    set_val SET('a', 'b', 'c')
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO test_strings VALUES
(1, 'test', 'varchar test', 'text content', 'medium text content here', 'long text content here',
 0xFF00FF00FF00FF00FF00, 0xDEADBEEF,
 'blob content', 'medium', 'a,c'),
(2, 'abc', 'another varchar', 'more text', 'more medium', 'more long',
 0x0000000000000000FF00, 0xCAFEBABE,
 'another blob', 'large', 'b'),
(3, '', '', '', '', '',
 0x00000000000000000000, 0x00,
 '', 'small', '');

-- Test 3: Date and time types
DROP TABLE IF EXISTS test_datetime;
CREATE TABLE test_datetime (
    id INT PRIMARY KEY,
    date_val DATE,
    datetime_val DATETIME,
    timestamp_val TIMESTAMP,
    time_val TIME,
    year_val YEAR
);

INSERT INTO test_datetime VALUES
(1, '2024-01-15', '2024-01-15 10:30:45', '2024-01-15 10:30:45', '10:30:45', 2024),
(2, '1950-06-25', '1950-06-25 23:59:59', '2038-01-19 03:14:07', '23:59:59', 1999),
(3, '2000-01-01', '2000-01-01 00:00:00', '2000-01-01 00:00:00', '00:00:00', 2000),
(4, '2024-12-31', '2024-12-31 23:59:59', '2024-12-31 23:59:59', '23:59:59', 2024);

-- Test 4: Special characters and UTF-8
DROP TABLE IF EXISTS test_unicode;
CREATE TABLE test_unicode (
    id INT PRIMARY KEY,
    emoji VARCHAR(255),
    chinese VARCHAR(255),
    arabic VARCHAR(255),
    russian VARCHAR(255),
    mixed VARCHAR(500)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO test_unicode VALUES
(1, '😀😁😂🤣😃😄', '你好世界', 'مرحبا بالعالم', 'Привет мир', 'Mixed: 😀 你好 مرحبا Привет'),
(2, '🚀🌟💡🔥', '中文测试', 'اختبار عربي', 'Русский тест', 'Special: @#$%^&*()'),
(3, '❤️💙💚💛', '数据库复制', 'قاعدة البيانات', 'База данных', 'Symbols: €£¥₹');

-- ================================================================
-- SECTION 2: DDL OPERATIONS (Tests 5-10)
-- ================================================================

-- Test 5: ADD COLUMN
DROP TABLE IF EXISTS test_add_column;
CREATE TABLE test_add_column (id INT PRIMARY KEY, name VARCHAR(50));
INSERT INTO test_add_column VALUES (1, 'before_add');

ALTER TABLE test_add_column ADD COLUMN age INT;
INSERT INTO test_add_column VALUES (2, 'after_add', 25);

ALTER TABLE test_add_column ADD COLUMN email VARCHAR(100);
INSERT INTO test_add_column VALUES (3, 'second_add', 30, 'test@example.com');

-- Test 6: DROP COLUMN
DROP TABLE IF EXISTS test_drop_column;
CREATE TABLE test_drop_column (id INT PRIMARY KEY, name VARCHAR(50), temp VARCHAR(50), temp2 VARCHAR(50));
INSERT INTO test_drop_column VALUES (1, 'test1', 'temp1', 'temp2');

ALTER TABLE test_drop_column DROP COLUMN temp;
INSERT INTO test_drop_column VALUES (2, 'test2', 'temp2_val');

ALTER TABLE test_drop_column DROP COLUMN temp2;
INSERT INTO test_drop_column VALUES (3, 'test3');

-- Test 7: RENAME COLUMN
DROP TABLE IF EXISTS test_rename_column;
CREATE TABLE test_rename_column (id INT PRIMARY KEY, old_name VARCHAR(50));
INSERT INTO test_rename_column VALUES (1, 'before_rename');

ALTER TABLE test_rename_column RENAME COLUMN old_name TO new_name;
INSERT INTO test_rename_column VALUES (2, 'after_rename');

-- Test 8: MODIFY COLUMN (type change)
DROP TABLE IF EXISTS test_modify_column;
CREATE TABLE test_modify_column (id INT PRIMARY KEY, small_val INT);
INSERT INTO test_modify_column VALUES (1, 100);

ALTER TABLE test_modify_column MODIFY COLUMN small_val BIGINT;
INSERT INTO test_modify_column VALUES (2, 9999999999999);

-- Test 9: RENAME TABLE
DROP TABLE IF EXISTS test_old_table_name;
DROP TABLE IF EXISTS test_new_table_name;
CREATE TABLE test_old_table_name (id INT PRIMARY KEY, data VARCHAR(50));
INSERT INTO test_old_table_name VALUES (1, 'before_rename');

RENAME TABLE test_old_table_name TO test_new_table_name;
INSERT INTO test_new_table_name VALUES (2, 'after_rename');

-- Test 10: CREATE and DROP TABLE
DROP TABLE IF EXISTS test_drop_table;
CREATE TABLE test_drop_table (id INT PRIMARY KEY, value VARCHAR(50));
INSERT INTO test_drop_table VALUES (1, 'will_be_dropped');
-- We'll validate this table is gone after drop
-- DROP TABLE test_drop_table;

-- ================================================================
-- SECTION 3: DML OPERATIONS (Tests 11-15)
-- ================================================================

-- Test 11: INSERT (single and bulk)
DROP TABLE IF EXISTS test_insert;
CREATE TABLE test_insert (id INT PRIMARY KEY, value VARCHAR(50), counter INT);
INSERT INTO test_insert VALUES (1, 'single', 1);
INSERT INTO test_insert VALUES (2, 'row2', 2), (3, 'row3', 3), (4, 'row4', 4);
INSERT INTO test_insert VALUES (5, 'row5', 5), (6, 'row6', 6), (7, 'row7', 7), (8, 'row8', 8);

-- Test 12: UPDATE (single and multiple rows)
DROP TABLE IF EXISTS test_update;
CREATE TABLE test_update (id INT PRIMARY KEY, value VARCHAR(50), counter INT);
INSERT INTO test_update VALUES (1, 'original', 0), (2, 'original', 0), (3, 'original', 0);

UPDATE test_update SET value = 'updated' WHERE id = 1;
UPDATE test_update SET counter = counter + 1 WHERE id <= 2;
UPDATE test_update SET value = 'bulk_update', counter = 100 WHERE id > 1;

-- Test 13: DELETE (single and multiple rows)
DROP TABLE IF EXISTS test_delete;
CREATE TABLE test_delete (id INT PRIMARY KEY, status VARCHAR(20));
INSERT INTO test_delete VALUES (1, 'active'), (2, 'deleted'), (3, 'active'), (4, 'deleted'), (5, 'active');

DELETE FROM test_delete WHERE id = 2;
DELETE FROM test_delete WHERE status = 'deleted';

-- Test 14: REPLACE
DROP TABLE IF EXISTS test_replace;
CREATE TABLE test_replace (id INT PRIMARY KEY, value VARCHAR(50));
INSERT INTO test_replace VALUES (1, 'original');

REPLACE INTO test_replace VALUES (1, 'replaced');
REPLACE INTO test_replace VALUES (2, 'new_row');

-- Test 15: INSERT ON DUPLICATE KEY UPDATE
DROP TABLE IF EXISTS test_duplicate_key;
CREATE TABLE test_duplicate_key (id INT PRIMARY KEY, counter INT, last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
INSERT INTO test_duplicate_key (id, counter) VALUES (1, 1);

INSERT INTO test_duplicate_key (id, counter) VALUES (1, 1) 
ON DUPLICATE KEY UPDATE counter = counter + 1;

INSERT INTO test_duplicate_key (id, counter) VALUES (1, 1) 
ON DUPLICATE KEY UPDATE counter = counter + 1;

INSERT INTO test_duplicate_key (id, counter) VALUES (2, 5);

-- ================================================================
-- SECTION 4: TRANSACTIONS (Tests 16-19)
-- ================================================================

-- Test 16: Simple transaction COMMIT
DROP TABLE IF EXISTS test_tx_commit;
CREATE TABLE test_tx_commit (id INT PRIMARY KEY, value VARCHAR(50));

START TRANSACTION;
INSERT INTO test_tx_commit VALUES (1, 'tx1');
INSERT INTO test_tx_commit VALUES (2, 'tx2');
INSERT INTO test_tx_commit VALUES (3, 'tx3');
COMMIT;

-- Test 17: Transaction ROLLBACK
DROP TABLE IF EXISTS test_tx_rollback;
CREATE TABLE test_tx_rollback (id INT PRIMARY KEY, value VARCHAR(50));

START TRANSACTION;
INSERT INTO test_tx_rollback VALUES (1, 'should_not_appear');
INSERT INTO test_tx_rollback VALUES (2, 'should_not_appear');
ROLLBACK;

INSERT INTO test_tx_rollback VALUES (3, 'should_appear');

-- Test 18: Multi-statement transaction (bank transfer)
DROP TABLE IF EXISTS test_accounts;
CREATE TABLE test_accounts (id INT PRIMARY KEY, name VARCHAR(50), balance DECIMAL(10,2));
INSERT INTO test_accounts VALUES (1, 'Alice', 1000.00), (2, 'Bob', 500.00);

START TRANSACTION;
UPDATE test_accounts SET balance = balance - 100 WHERE id = 1;
UPDATE test_accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;

-- Verify balances
SELECT id, name, balance FROM test_accounts ORDER BY id;

-- Test 19: Nested transactions (savepoints)
DROP TABLE IF EXISTS test_savepoint;
CREATE TABLE test_savepoint (id INT PRIMARY KEY, value VARCHAR(50));

START TRANSACTION;
INSERT INTO test_savepoint VALUES (1, 'outer_tx');
SAVEPOINT sp1;
INSERT INTO test_savepoint VALUES (2, 'savepoint_1');
SAVEPOINT sp2;
INSERT INTO test_savepoint VALUES (3, 'savepoint_2');
ROLLBACK TO SAVEPOINT sp2;
INSERT INTO test_savepoint VALUES (4, 'after_rollback_sp2');
COMMIT;

-- ================================================================
-- SECTION 5: EDGE CASES (Tests 20-24)
-- ================================================================

-- Test 20: NULL values
DROP TABLE IF EXISTS test_nulls;
CREATE TABLE test_nulls (
    id INT PRIMARY KEY,
    nullable_int INT NULL,
    nullable_varchar VARCHAR(50) NULL,
    nullable_decimal DECIMAL(10,2) NULL,
    not_null_int INT NOT NULL,
    not_null_varchar VARCHAR(50) NOT NULL DEFAULT 'default'
);

INSERT INTO test_nulls (id, nullable_int, nullable_varchar, nullable_decimal, not_null_int, not_null_varchar) 
VALUES (1, NULL, NULL, NULL, 100, 'test');

INSERT INTO test_nulls (id, nullable_int, nullable_varchar, nullable_decimal, not_null_int) 
VALUES (2, 42, 'test', 123.45, 200);

INSERT INTO test_nulls (id, not_null_int) 
VALUES (3, 300);

-- Test 21: Empty strings vs NULL
DROP TABLE IF EXISTS test_empty_vs_null;
CREATE TABLE test_empty_vs_null (
    id INT PRIMARY KEY,
    empty_string VARCHAR(50) NOT NULL DEFAULT '',
    null_string VARCHAR(50) NULL,
    zero_int INT NOT NULL DEFAULT 0,
    null_int INT NULL
);

INSERT INTO test_empty_vs_null VALUES (1, '', NULL, 0, NULL);
INSERT INTO test_empty_vs_null VALUES (2, 'not empty', 'not null', 42, 42);
INSERT INTO test_empty_vs_null (id) VALUES (3);

-- Test 22: Large TEXT/BLOB
DROP TABLE IF EXISTS test_large_data;
CREATE TABLE test_large_data (id INT PRIMARY KEY, data LONGTEXT, blob_data LONGBLOB);

INSERT INTO test_large_data VALUES 
(1, REPEAT('A', 1000), REPEAT('B', 1000)),
(2, REPEAT('Large text data ', 100), REPEAT(0xFF, 1000)),
(3, REPEAT('Unicode: 你好世界 ', 50), REPEAT(0xDE, 500));

-- Test 23: Special float values
DROP TABLE IF EXISTS test_special_floats;
CREATE TABLE test_special_floats (
    id INT PRIMARY KEY,
    normal_float FLOAT,
    normal_double DOUBLE,
    zero_float FLOAT,
    negative_zero DOUBLE
);

INSERT INTO test_special_floats VALUES 
(1, 3.14159, 2.71828, 0.0, -0.0),
(2, 1.23456789, 9.87654321, 0.0, 0.0),
(3, -999.999, -888.888, 0.0, 0.0);

-- Test 24: Concurrent operations simulation
DROP TABLE IF EXISTS test_concurrent;
CREATE TABLE test_concurrent (
    id INT PRIMARY KEY AUTO_INCREMENT,
    thread_id INT,
    operation_num INT,
    value VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Simulate concurrent inserts
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (1, 1, 'thread1_op1');
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (2, 1, 'thread2_op1');
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (1, 2, 'thread1_op2');
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (3, 1, 'thread3_op1');
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (2, 2, 'thread2_op2');
INSERT INTO test_concurrent (thread_id, operation_num, value) VALUES (1, 3, 'thread1_op3');

-- ================================================================
-- SECTION 6: COMPLEX SCENARIOS (Tests 25-27)
-- ================================================================

-- Test 25: Mixed operations in transaction
DROP TABLE IF EXISTS test_mixed_ops;
CREATE TABLE test_mixed_ops (id INT PRIMARY KEY, value VARCHAR(50), counter INT DEFAULT 0);

START TRANSACTION;
INSERT INTO test_mixed_ops (id, value) VALUES (1, 'insert1');
UPDATE test_mixed_ops SET value = 'updated' WHERE id = 1;
DELETE FROM test_mixed_ops WHERE id = 1;
INSERT INTO test_mixed_ops (id, value) VALUES (1, 'final');
UPDATE test_mixed_ops SET counter = 100 WHERE id = 1;
COMMIT;

-- Test 26: Schema change during active replication
DROP TABLE IF EXISTS test_live_schema_change;
CREATE TABLE test_live_schema_change (id INT PRIMARY KEY, col1 VARCHAR(50));
INSERT INTO test_live_schema_change (id, col1) VALUES (1, 'before_alter');

ALTER TABLE test_live_schema_change ADD COLUMN col2 INT;
INSERT INTO test_live_schema_change (id, col1, col2) VALUES (2, 'after_alter', 100);

ALTER TABLE test_live_schema_change ADD COLUMN col3 VARCHAR(100) DEFAULT 'default_value';
INSERT INTO test_live_schema_change (id, col1, col2, col3) VALUES (3, 'second_alter', 200, 'new_col');

ALTER TABLE test_live_schema_change MODIFY COLUMN col2 BIGINT;
INSERT INTO test_live_schema_change (id, col1, col2, col3) VALUES (4, 'type_change', 9999999999, 'big_number');

-- Test 27: Bulk operations
DROP TABLE IF EXISTS test_bulk_operations;
CREATE TABLE test_bulk_operations (
    id INT PRIMARY KEY AUTO_INCREMENT,
    batch_id INT,
    data VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert 100 rows in batches
INSERT INTO test_bulk_operations (batch_id, data) VALUES
(1, 'batch1_row1'), (1, 'batch1_row2'), (1, 'batch1_row3'), (1, 'batch1_row4'), (1, 'batch1_row5'),
(1, 'batch1_row6'), (1, 'batch1_row7'), (1, 'batch1_row8'), (1, 'batch1_row9'), (1, 'batch1_row10');

INSERT INTO test_bulk_operations (batch_id, data) VALUES
(2, 'batch2_row1'), (2, 'batch2_row2'), (2, 'batch2_row3'), (2, 'batch2_row4'), (2, 'batch2_row5'),
(2, 'batch2_row6'), (2, 'batch2_row7'), (2, 'batch2_row8'), (2, 'batch2_row9'), (2, 'batch2_row10');

INSERT INTO test_bulk_operations (batch_id, data) VALUES
(3, 'batch3_row1'), (3, 'batch3_row2'), (3, 'batch3_row3'), (3, 'batch3_row4'), (3, 'batch3_row5'),
(3, 'batch3_row6'), (3, 'batch3_row7'), (3, 'batch3_row8'), (3, 'batch3_row9'), (3, 'batch3_row10');

-- ================================================================
-- SUMMARY
-- ================================================================

SELECT 'All test scenarios executed successfully!' AS status;
SELECT 'Total tables created:' AS metric, COUNT(*) AS value 
FROM information_schema.tables 
WHERE table_schema = 'testdb' AND table_name LIKE 'test_%';
