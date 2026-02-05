#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
MYSQL_HOST=${MYSQL_HOST:-mysql}
MYSQL_PORT=${MYSQL_PORT:-3306}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_DATABASE=${MYSQL_DATABASE:-testdb}
CLICKHOUSE_HOST=${CLICKHOUSE_HOST:-clickhouse}

echo "========================================"
echo "Validating Replication Results"
echo "========================================"
echo ""

VALIDATION_FAILED=0
TESTS_PASSED=0
TESTS_FAILED=0

# Function to execute MySQL query
mysql_query() {
    mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -sN -e "$1"
}

# Function to execute ClickHouse query
clickhouse_query() {
    clickhouse-client --host "$CLICKHOUSE_HOST" --port 9000 --query "$1"
}

# Function to compare row counts
compare_counts() {
    local table=$1
    local description=$2
    
    # Check if table exists in MySQL
    local mysql_exists=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'testdb' AND table_name = '$table'")
    
    if [ "$mysql_exists" -eq 0 ]; then
        echo -e "${YELLOW}⊘ $description: Table does not exist in MySQL (expected for dropped tables)${NC}"
        return 0
    fi
    
    local mysql_count=$(mysql_query "SELECT COUNT(*) FROM $table" 2>/dev/null || echo "0")
    local ch_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.$table" 2>/dev/null || echo "0")
    
    if [ "$mysql_count" == "$ch_count" ]; then
        echo -e "${GREEN}✓ $description: MySQL=$mysql_count, ClickHouse=$ch_count - MATCH${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗ $description: MySQL=$mysql_count, ClickHouse=$ch_count - MISMATCH!${NC}"
        ((TESTS_FAILED++))
        VALIDATION_FAILED=1
    fi
}

# Function to compare specific values
compare_value() {
    local description=$1
    local expected=$2
    local actual=$3
    
    if [ "$expected" == "$actual" ]; then
        echo -e "${GREEN}✓ $description: Expected=$expected, Actual=$actual - MATCH${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗ $description: Expected=$expected, Actual=$actual - MISMATCH!${NC}"
        ((TESTS_FAILED++))
        VALIDATION_FAILED=1
    fi
}

echo -e "${BLUE}=== Section 1: Data Types Coverage ===${NC}"
compare_counts "test_numeric" "Test 1: Numeric types"
compare_counts "test_strings" "Test 2: String types"
compare_counts "test_datetime" "Test 3: DateTime types"
compare_counts "test_unicode" "Test 4: Unicode/UTF-8"
echo ""

echo -e "${BLUE}=== Section 2: DDL Operations ===${NC}"
compare_counts "test_add_column" "Test 5: ADD COLUMN"
compare_counts "test_drop_column" "Test 6: DROP COLUMN"
compare_counts "test_rename_column" "Test 7: RENAME COLUMN"
compare_counts "test_modify_column" "Test 8: MODIFY COLUMN"
compare_counts "test_new_table_name" "Test 9: RENAME TABLE"

# Test 10: Verify dropped table doesn't exist
ch_dropped_exists=$(clickhouse_query "SELECT COUNT(*) FROM system.tables WHERE database = 'testdb' AND name = 'test_drop_table'" 2>/dev/null || echo "0")
if [ "$ch_dropped_exists" -eq 0 ]; then
    echo -e "${GREEN}✓ Test 10: DROP TABLE - Table correctly not in ClickHouse${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ Test 10: DROP TABLE - Table still exists in ClickHouse!${NC}"
    ((TESTS_FAILED++))
    VALIDATION_FAILED=1
fi
echo ""

echo -e "${BLUE}=== Section 3: DML Operations ===${NC}"
compare_counts "test_insert" "Test 11: INSERT"
compare_counts "test_update" "Test 12: UPDATE"
compare_counts "test_delete" "Test 13: DELETE"
compare_counts "test_replace" "Test 14: REPLACE"
compare_counts "test_duplicate_key" "Test 15: ON DUPLICATE KEY UPDATE"

# Validate UPDATE actually changed values
test_update_value=$(clickhouse_query "SELECT value FROM testdb.test_update WHERE id = 1" 2>/dev/null || echo "")
compare_value "Test 12: UPDATE value changed" "updated" "$test_update_value"

# Validate DELETE removed correct rows (should be 2 remaining)
test_delete_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.test_delete" 2>/dev/null || echo "0")
compare_value "Test 13: DELETE removed correct rows" "2" "$test_delete_count"

# Validate ON DUPLICATE KEY UPDATE counter
test_dup_counter=$(clickhouse_query "SELECT counter FROM testdb.test_duplicate_key WHERE id = 1" 2>/dev/null || echo "0")
compare_value "Test 15: Duplicate key counter" "3" "$test_dup_counter"
echo ""

echo -e "${BLUE}=== Section 4: Transactions ===${NC}"
compare_counts "test_tx_commit" "Test 16: Transaction COMMIT"

# Test 17: Validate transaction rollback (should be 1 row, not 3)
test_tx_rollback_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.test_tx_rollback" 2>/dev/null || echo "0")
if [ "$test_tx_rollback_count" == "1" ]; then
    echo -e "${GREEN}✓ Test 17: Transaction ROLLBACK - Rollback handled correctly (1 row)${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ Test 17: Transaction ROLLBACK - Expected 1 row, got $test_tx_rollback_count - ROLLBACK NOT WORKING!${NC}"
    ((TESTS_FAILED++))
    VALIDATION_FAILED=1
fi

# Test 18: Validate transaction integrity (bank transfer)
test_accounts_total=$(clickhouse_query "SELECT SUM(balance) FROM testdb.test_accounts" 2>/dev/null || echo "0")
alice_balance=$(clickhouse_query "SELECT balance FROM testdb.test_accounts WHERE id = 1" 2>/dev/null || echo "0")
bob_balance=$(clickhouse_query "SELECT balance FROM testdb.test_accounts WHERE id = 2" 2>/dev/null || echo "0")

compare_value "Test 18: Transaction integrity (total balance)" "1500.00" "$test_accounts_total"
compare_value "Test 18: Alice balance after transfer" "900.00" "$alice_balance"
compare_value "Test 18: Bob balance after transfer" "600.00" "$bob_balance"

# Test 19: Savepoint rollback
compare_counts "test_savepoint" "Test 19: Savepoint ROLLBACK"
test_savepoint_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.test_savepoint" 2>/dev/null || echo "0")
compare_value "Test 19: Savepoint row count" "3" "$test_savepoint_count"
echo ""

echo -e "${BLUE}=== Section 5: Edge Cases ===${NC}"
compare_counts "test_nulls" "Test 20: NULL values"
compare_counts "test_empty_vs_null" "Test 21: Empty vs NULL"
compare_counts "test_large_data" "Test 22: Large TEXT/BLOB"
compare_counts "test_special_floats" "Test 23: Special floats"
compare_counts "test_concurrent" "Test 24: Concurrent operations"

# Validate NULL handling
test_null_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.test_nulls WHERE nullable_int IS NULL" 2>/dev/null || echo "0")
if [ "$test_null_count" -ge 1 ]; then
    echo -e "${GREEN}✓ Test 20: NULL values preserved${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ Test 20: NULL values not preserved!${NC}"
    ((TESTS_FAILED++))
    VALIDATION_FAILED=1
fi
echo ""

echo -e "${BLUE}=== Section 6: Complex Scenarios ===${NC}"
compare_counts "test_mixed_ops" "Test 25: Mixed operations in TX"
compare_counts "test_live_schema_change" "Test 26: Live schema changes"
compare_counts "test_bulk_operations" "Test 27: Bulk operations"

# Validate mixed ops final state
test_mixed_final_value=$(clickhouse_query "SELECT value FROM testdb.test_mixed_ops WHERE id = 1" 2>/dev/null || echo "")
compare_value "Test 25: Mixed ops final value" "final" "$test_mixed_final_value"

test_mixed_final_counter=$(clickhouse_query "SELECT counter FROM testdb.test_mixed_ops WHERE id = 1" 2>/dev/null || echo "0")
compare_value "Test 25: Mixed ops final counter" "100" "$test_mixed_final_counter"

# Validate live schema change columns
test_schema_col3_exists=$(clickhouse_query "SELECT COUNT(*) FROM system.columns WHERE database = 'testdb' AND table = 'test_live_schema_change' AND name = 'col3'" 2>/dev/null || echo "0")
if [ "$test_schema_col3_exists" -eq 1 ]; then
    echo -e "${GREEN}✓ Test 26: Schema evolution - col3 exists${NC}"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ Test 26: Schema evolution - col3 missing!${NC}"
    ((TESTS_FAILED++))
    VALIDATION_FAILED=1
fi

# Validate bulk operations count
test_bulk_count=$(clickhouse_query "SELECT COUNT(*) FROM testdb.test_bulk_operations" 2>/dev/null || echo "0")
compare_value "Test 27: Bulk operations count" "30" "$test_bulk_count"
echo ""

# ==================== FINAL SUMMARY ====================
echo "========================================"
if [ $VALIDATION_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓✓✓ VALIDATION PASSED ✓✓✓${NC}"
    echo ""
    echo "Tests Passed: $TESTS_PASSED"
    echo "Tests Failed: $TESTS_FAILED"
    echo ""
    echo "Replication Accuracy: 100%"
    echo "All data replicated correctly!"
else
    echo -e "${RED}✗✗✗ VALIDATION FAILED ✗✗✗${NC}"
    echo ""
    echo "Tests Passed: $TESTS_PASSED"
    echo "Tests Failed: $TESTS_FAILED"
    echo ""
    echo "Some validations failed. Check logs above."
fi
echo "========================================"

exit $VALIDATION_FAILED
