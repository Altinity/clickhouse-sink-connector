-- Transaction Test Scenarios for ClickHouse Sink Connector
-- Phase 4: Transaction Support Testing
--
-- Prerequisites:
-- 1. MySQL source with Debezium configured
-- 2. ClickHouse sink connector running with transaction support enabled
-- 3. Test database and tables created

-- Setup
CREATE DATABASE IF NOT EXISTS test_transactions;
USE test_transactions;

-- Test tables
CREATE TABLE IF NOT EXISTS accounts (
    id INT PRIMARY KEY,
    balance DECIMAL(10, 2),
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id INT PRIMARY KEY,
    user_id INT,
    amount DECIMAL(10, 2),
    status VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS inventory (
    product_id INT PRIMARY KEY,
    quantity INT,
    reserved INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS test_tx (
    id INT PRIMARY KEY,
    description VARCHAR(255)
);

-- ============================================================================
-- Test 1: Simple Transaction COMMIT
-- Expected: All changes should appear in ClickHouse atomically
-- ============================================================================

BEGIN;
INSERT INTO test_tx VALUES (1, 'commit-test');
UPDATE test_tx SET description = 'commit-test-updated' WHERE id = 1;
COMMIT;

-- Verify in ClickHouse: SELECT * FROM test_tx WHERE id = 1;
-- Expected: 1 row with description = 'commit-test-updated'

-- ============================================================================
-- Test 2: Transaction ROLLBACK
-- Expected: No changes should appear in ClickHouse
-- ============================================================================

BEGIN;
INSERT INTO test_tx VALUES (2, 'rollback-test');
UPDATE test_tx SET description = 'rollback-test-updated' WHERE id = 2;
ROLLBACK;

-- Verify in ClickHouse: SELECT * FROM test_tx WHERE id = 2;
-- Expected: 0 rows

-- ============================================================================
-- Test 3: Multi-statement Transaction (Banking Transfer)
-- Expected: Both account updates committed atomically
-- ============================================================================

-- Setup
INSERT INTO accounts VALUES (101, 1000.00, NOW());
INSERT INTO accounts VALUES (102, 500.00, NOW());

-- Transfer $100 from account 101 to 102
BEGIN;
UPDATE accounts SET balance = balance - 100.00, last_updated = NOW() WHERE id = 101;
UPDATE accounts SET balance = balance + 100.00, last_updated = NOW() WHERE id = 102;
COMMIT;

-- Verify in ClickHouse:
-- SELECT id, balance FROM accounts WHERE id IN (101, 102);
-- Expected: Account 101 = 900.00, Account 102 = 600.00

-- ============================================================================
-- Test 4: Mixed DML Operations in Transaction
-- Expected: All operations (INSERT, UPDATE, DELETE) committed atomically
-- ============================================================================

BEGIN;
INSERT INTO orders VALUES (1, 101, 250.00, 'pending');
UPDATE inventory SET quantity = quantity - 2, reserved = reserved + 2 WHERE product_id = 1;
DELETE FROM orders WHERE id = 999; -- Non-existent, should not cause issues
COMMIT;

-- Verify in ClickHouse:
-- SELECT * FROM orders WHERE id = 1;
-- SELECT * FROM inventory WHERE product_id = 1;

-- ============================================================================
-- Test 5: Large Transaction (1000 operations)
-- Expected: All operations committed atomically, no timeouts
-- ============================================================================

BEGIN;

-- Insert 1000 test records
INSERT INTO test_tx VALUES (1001, 'large-tx-1');
INSERT INTO test_tx VALUES (1002, 'large-tx-2');
-- ... (in practice, generate 1000 inserts via script)
-- For brevity, showing concept:

DELIMITER //
CREATE PROCEDURE generate_large_transaction()
BEGIN
    DECLARE i INT DEFAULT 1;
    START TRANSACTION;
    WHILE i <= 1000 DO
        INSERT INTO test_tx VALUES (1000 + i, CONCAT('large-tx-', i));
        SET i = i + 1;
    END WHILE;
    COMMIT;
END//
DELIMITER ;

CALL generate_large_transaction();
DROP PROCEDURE generate_large_transaction;

-- Verify in ClickHouse:
-- SELECT COUNT(*) FROM test_tx WHERE id BETWEEN 1001 AND 2000;
-- Expected: 1000 rows

-- ============================================================================
-- Test 6: Concurrent Transactions from Multiple Connections
-- Expected: Each transaction committed independently and atomically
-- ============================================================================

-- Connection 1:
BEGIN;
UPDATE accounts SET balance = balance + 50 WHERE id = 101;
-- Wait a moment
COMMIT;

-- Connection 2 (simultaneously):
BEGIN;
UPDATE accounts SET balance = balance + 75 WHERE id = 102;
-- Wait a moment
COMMIT;

-- Connection 3 (simultaneously):
BEGIN;
UPDATE accounts SET balance = balance - 25 WHERE id = 101;
ROLLBACK; -- This should be rolled back

-- Verify in ClickHouse:
-- SELECT id, balance FROM accounts WHERE id IN (101, 102);
-- Expected: Account 101 = 950.00 (900 + 50, rollback ignored)
--           Account 102 = 675.00 (600 + 75)

-- ============================================================================
-- Test 7: Transaction with Error and Rollback
-- Expected: No partial data in ClickHouse
-- ============================================================================

BEGIN;
INSERT INTO orders VALUES (2, 102, 300.00, 'pending');
UPDATE inventory SET quantity = quantity - 5 WHERE product_id = 2;
-- Simulate error (violate constraint, etc.)
INSERT INTO orders VALUES (2, 103, 400.00, 'pending'); -- Duplicate key error
-- MySQL will auto-rollback on error in many configurations
ROLLBACK;

-- Verify in ClickHouse:
-- SELECT * FROM orders WHERE id = 2;
-- Expected: 0 rows (all rolled back)

-- ============================================================================
-- Test 8: Nested Savepoints (MySQL doesn't support nested transactions,
--         but savepoints can be tested)
-- Expected: Rollback to savepoint should discard partial work
-- ============================================================================

BEGIN;
INSERT INTO test_tx VALUES (3001, 'savepoint-test-1');
SAVEPOINT sp1;
INSERT INTO test_tx VALUES (3002, 'savepoint-test-2');
SAVEPOINT sp2;
INSERT INTO test_tx VALUES (3003, 'savepoint-test-3');
ROLLBACK TO SAVEPOINT sp2;
-- 3003 should be discarded
COMMIT;

-- Verify in ClickHouse:
-- SELECT * FROM test_tx WHERE id >= 3001 AND id <= 3003;
-- Expected: 2 rows (3001 and 3002, but not 3003)

-- ============================================================================
-- Test 9: Long-Running Transaction (Test Timeout Handling)
-- Expected: Transaction should complete or timeout gracefully
-- ============================================================================

BEGIN;
INSERT INTO test_tx VALUES (4001, 'long-running-tx');
-- In practice, you would add a delay here to simulate long-running transaction
-- SELECT SLEEP(10);
UPDATE test_tx SET description = 'long-running-updated' WHERE id = 4001;
COMMIT;

-- Verify transaction completed successfully

-- ============================================================================
-- Test 10: Empty Transaction
-- Expected: No records written to ClickHouse
-- ============================================================================

BEGIN;
-- No DML operations
COMMIT;

-- No verification needed - should just complete without errors

-- ============================================================================
-- Cleanup
-- ============================================================================

DROP TABLE IF EXISTS test_tx;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS inventory;
DROP DATABASE IF NOT EXISTS test_transactions;

-- ============================================================================
-- Validation Queries for ClickHouse
-- ============================================================================

-- Run these on ClickHouse after all tests:

-- 1. Verify no orphaned transactions
-- SELECT COUNT(*) FROM system.parts WHERE active = 0;

-- 2. Check for any partial commits (if transaction support working, should match MySQL)
-- Compare row counts between MySQL and ClickHouse:
-- MySQL: SELECT COUNT(*) FROM test_transactions.accounts;
-- ClickHouse: SELECT COUNT(*) FROM test_transactions.accounts;

-- 3. Verify transaction metrics (if instrumented)
-- Check connector metrics for:
-- - total_transactions_committed
-- - total_transactions_rolled_back
-- - active_transactions (should be 0 at end)

-- ============================================================================
-- Performance Test: High-Throughput Transactions
-- ============================================================================

-- Generate 10,000 small transactions rapidly
DELIMITER //
CREATE PROCEDURE performance_test_transactions()
BEGIN
    DECLARE i INT DEFAULT 1;
    WHILE i <= 10000 DO
        START TRANSACTION;
        INSERT INTO test_tx VALUES (i, CONCAT('perf-test-', i));
        COMMIT;
        SET i = i + 1;
    END WHILE;
END//
DELIMITER ;

-- CALL performance_test_transactions();
-- DROP PROCEDURE performance_test_transactions;

-- Verify all committed:
-- SELECT COUNT(*) FROM test_tx WHERE description LIKE 'perf-test-%';
-- Expected: 10000 rows
