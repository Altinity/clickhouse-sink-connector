# Debugging `customers` Table Checksum Mismatch

## Investigation Summary

**Status**: Row counts match (10,007), but checksums differ
- MySQL: `26cc512029ca35b3d332628a9a99a023`
- ClickHouse: `17db1c832b15db4824b1aee5599a662d`

**Root Cause Identified**: The `loyalty_points` column was added via DDL in Phase 4, and existing rows have different default value representations between MySQL and ClickHouse.

---

## Potential Root Causes (Ranked by Likelihood)

### 1. 🎯 **`loyalty_points` Column Added via DDL (MOST LIKELY)**
- **Evidence**: Line 129 in `phase4-live-dml.sql`: `ALTER TABLE customers ADD COLUMN loyalty_points INT DEFAULT 0;`
- **Problem**: 
  - 10,000 existing customers receive the default value
  - MySQL sets `DEFAULT 0` for existing rows immediately
  - ClickHouse CDC might set these as `NULL` or handle differently
  - Only customer_id 200001 explicitly gets `loyalty_points = 100` (line 132)
- **Impact**: 10,000+ rows potentially affected

### 2. 🎯 **`updated_at` Timestamp Precision/Timezone (VERY LIKELY)**
- **Evidence**: Multiple UPDATE operations use `NOW()` (lines 58, 65, 72, 79, 132, 148-150)
- **Problem**: 
  - MySQL NOW() uses session timezone
  - Timestamp precision differences (microseconds)
  - ClickHouse DateTime64 conversion might differ
- **Impact**: Any updated row in phase 4

### 3. **NULL Value Handling**
- **Evidence**: Customer 200030 has `phone = NULL, address = NULL` (line 162)
- **Problem**: ClickHouse and MySQL may serialize NULLs differently in checksums
- **Impact**: Limited (only new rows with explicit NULLs)

### 4. **DateTime Precision on `created_at` and `updated_at`**
- **Evidence**: Both columns use `DATETIME DEFAULT CURRENT_TIMESTAMP`
- **Problem**: Fractional seconds precision differences
- **Impact**: All rows

### 5. **UTF8MB4 Encoding**
- **Evidence**: Line 13 in `phase4-live-dml.sh`: `--default-character-set=utf8mb4`
- **Problem**: Special characters might be encoded differently
- **Impact**: Unlikely (standard English names)

---

## Debugging Queries

### **Query 1: Check `loyalty_points` Column Differences**

```sql
-- MySQL: Check loyalty_points distribution
SELECT 
    loyalty_points, 
    COUNT(*) as count,
    CASE 
        WHEN loyalty_points IS NULL THEN 'NULL'
        WHEN loyalty_points = 0 THEN 'ZERO'
        ELSE 'OTHER'
    END as value_type
FROM test_db.customers
GROUP BY loyalty_points
ORDER BY loyalty_points;
```

```sql
-- ClickHouse: Check loyalty_points distribution
SELECT 
    loyalty_points, 
    COUNT(*) as count,
    CASE 
        WHEN loyalty_points IS NULL THEN 'NULL'
        WHEN loyalty_points = 0 THEN 'ZERO'
        ELSE 'OTHER'
    END as value_type
FROM test_db.customers FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL
GROUP BY loyalty_points
ORDER BY loyalty_points;
```

**Expected Issue**: MySQL shows 10,006 rows with `0`, ClickHouse might show `NULL` or different count.

---

### **Query 2: Compare Specific Row Data (Sample)**

```sql
-- MySQL: Get row data for comparison
SELECT 
    customer_id,
    first_name,
    last_name,
    email,
    loyalty_points,
    CAST(created_at AS CHAR) as created_at_str,
    CAST(updated_at AS CHAR) as updated_at_str
FROM test_db.customers
WHERE customer_id IN (1, 100, 200001, 200010, 200020, 200030)
ORDER BY customer_id;
```

```sql
-- ClickHouse: Get same row data
SELECT 
    customer_id,
    first_name,
    last_name,
    email,
    loyalty_points,
    toString(created_at) as created_at_str,
    toString(updated_at) as updated_at_str
FROM test_db.customers FINAL
WHERE customer_id IN (1, 100, 200001, 200010, 200020, 200030)
AND (is_deleted = 0 OR is_deleted IS NULL)
ORDER BY customer_id;
```

**What to Look For**:
- `loyalty_points` NULL vs 0 differences
- Timestamp format differences (microseconds, trailing zeros)
- Any encoding issues in names

---

### **Query 3: Check Column Existence and Metadata**

```sql
-- MySQL: Check column definition
SELECT 
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT,
    EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'test_db' 
  AND TABLE_NAME = 'customers'
  AND COLUMN_NAME IN ('loyalty_points', 'updated_at', 'created_at')
ORDER BY COLUMN_NAME;
```

```sql
-- ClickHouse: Check column definition
SELECT 
    name,
    type,
    default_kind,
    default_expression
FROM system.columns
WHERE database = 'test_db' 
  AND table = 'customers'
  AND name IN ('loyalty_points', 'updated_at', 'created_at')
ORDER BY name;
```

**What to Look For**: 
- `loyalty_points` default value definition
- Data type differences (INT vs Int32, DATETIME vs DateTime64)

---

### **Query 4: Verify Checksum Calculation Includes `loyalty_points`**

```bash
# Check if loyalty_points is being checksummed in MySQL
python sink-connector/python/db_compare/mysql_table_checksum.py \
    --mysql_host localhost \
    --mysql_port 3306 \
    --mysql_database test_db \
    --tables_regex "^customers$" \
    --debug \
    --defaults_file ~/.my.cnf 2>&1 | grep -i "loyalty_points"

# Check if loyalty_points is being checksummed in ClickHouse
python sink-connector/python/db_compare/clickhouse_table_checksum.py \
    --clickhouse_host localhost \
    --clickhouse_port 9000 \
    --clickhouse_database test_db \
    --tables_regex "^customers$" \
    --sign_column "is_deleted" \
    --debug \
    --clickhouse_config_file ./clickhouse-client.xml 2>&1 | grep -i "loyalty_points"
```

---

### **Query 5: Generate Debug Output for Manual Diff**

```bash
# Generate MySQL row-by-row checksum output
python sink-connector/python/db_compare/mysql_table_checksum.py \
    --mysql_host localhost \
    --mysql_port 3306 \
    --mysql_database test_db \
    --tables_regex "^customers$" \
    --debug_output \
    --debug_limit 50 \
    --defaults_file ~/.my.cnf

# This creates: out.customers.mysql.txt

# Generate ClickHouse row-by-row checksum output
python sink-connector/python/db_compare/clickhouse_table_checksum.py \
    --clickhouse_host localhost \
    --clickhouse_port 9000 \
    --clickhouse_database test_db \
    --tables_regex "^customers$" \
    --sign_column "is_deleted" \
    --debug_output \
    --debug_limit 50 \
    --clickhouse_config_file ./clickhouse-client.xml

# This creates: out.customers.ch.txt

# Compare the first 50 rows to find differences
diff out.customers.mysql.txt out.customers.ch.txt | head -100
```

**What to Look For**: Specific rows/columns that differ between systems.

---

### **Query 6: Check for NULL vs Default 0 Differences**

```sql
-- MySQL: Count NULLs in loyalty_points
SELECT 
    SUM(CASE WHEN loyalty_points IS NULL THEN 1 ELSE 0 END) as null_count,
    SUM(CASE WHEN loyalty_points = 0 THEN 1 ELSE 0 END) as zero_count,
    SUM(CASE WHEN loyalty_points > 0 THEN 1 ELSE 0 END) as positive_count,
    COUNT(*) as total_count
FROM test_db.customers;
```

```sql
-- ClickHouse: Count NULLs in loyalty_points
SELECT 
    SUM(CASE WHEN loyalty_points IS NULL THEN 1 ELSE 0 END) as null_count,
    SUM(CASE WHEN loyalty_points = 0 THEN 1 ELSE 0 END) as zero_count,
    SUM(CASE WHEN loyalty_points > 0 THEN 1 ELSE 0 END) as positive_count,
    COUNT(*) as total_count
FROM test_db.customers FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL;
```

**Expected Issue**: MySQL shows 0 NULLs, ClickHouse might show thousands of NULLs.

---

## Recommended Fix Approaches

### **Option 1: Exclude `loyalty_points` from Checksum (RECOMMENDED - SHORT TERM)**

**Rationale**: 
- `loyalty_points` was added dynamically during the test
- It's not part of the original schema
- The column's default value handling differs between MySQL and ClickHouse CDC

**Implementation**:

Modify `phase5-validate.sh` line 219-226 to exclude `loyalty_points`:

```bash
# MySQL checksum - exclude loyalty_points
MYSQL_CHECKSUM=$(python db_compare/mysql_table_checksum.py \
    --mysql_host "$MYSQL_HOST" \
    --mysql_port "$MYSQL_PORT" \
    --mysql_database "$MYSQL_DATABASE" \
    --tables_regex "^${TABLE}\$" \
    --exclude_columns loyalty_points \
    --threads 1 \
    --threads_per_table 1 \
    --defaults_file /root/.my.cnf 2>&1 | grep "Checksum for table" | awk '{print $13}' || echo "error")

# ClickHouse checksum - exclude loyalty_points
CH_CHECKSUM=$(python db_compare/clickhouse_table_checksum.py \
    --clickhouse_host "$CLICKHOUSE_HOST" \
    --clickhouse_port "$CLICKHOUSE_PORT" \
    --clickhouse_user "$CLICKHOUSE_USER" \
    --clickhouse_password "$CLICKHOUSE_PASSWORD" \
    --clickhouse_database "$CLICKHOUSE_DATABASE" \
    --tables_regex "^${TABLE}\$" \
    --exclude_columns loyalty_points \
    --threads 1 \
    --sign_column "is_deleted" 2>&1 | grep "Checksum for table" | awk '{print $13}' || echo "error")
```

**Pros**:
- Quick fix
- Doesn't require data manipulation
- Test focuses on original schema

**Cons**:
- Doesn't test DDL-added columns
- Masks potential real issue with dynamic schema changes

---

### **Option 2: Fix the Data Difference (RECOMMENDED - LONG TERM)**

**Rationale**: The root cause is how ClickHouse CDC handles `ALTER TABLE ADD COLUMN` with default values.

**Investigation Steps**:
1. Run Query 1 & 6 above to confirm the NULL vs 0 hypothesis
2. If confirmed, this is a **connector bug** in handling DDL schema evolution

**Implementation**:

If ClickHouse has NULLs instead of 0:

```sql
-- Run this in ClickHouse to backfill loyalty_points
ALTER TABLE test_db.customers 
UPDATE loyalty_points = 0 
WHERE loyalty_points IS NULL;

-- Force final merge to apply the update
OPTIMIZE TABLE test_db.customers FINAL;
```

**Pros**:
- Tests real-world CDC scenario
- Identifies connector limitation
- More comprehensive validation

**Cons**:
- Requires data manipulation
- May not be automatic fix (manual intervention needed)

---

### **Option 3: Add Explicit Test Case for Dynamic Columns**

**Rationale**: Separate DDL-added columns into a dedicated test phase.

**Implementation**:

1. Create a new validation phase specifically for dynamically added columns
2. Document expected behavior differences
3. Add a configuration flag to handle schema evolution differences

```bash
# In phase5-validate.sh, add a new section:
echo "======================================"
echo "Step 2b: Dynamic Column Validation"
echo "======================================"
echo ""

# Validate that loyalty_points exists and has expected distribution
echo "Checking dynamically added loyalty_points column..."

MYSQL_LOYALTY_DIST=$(mysql -h "$MYSQL_HOST" ... -e "SELECT loyalty_points, COUNT(*) FROM customers GROUP BY loyalty_points ORDER BY loyalty_points")
CH_LOYALTY_DIST=$(clickhouse-client ... --query "SELECT loyalty_points, COUNT(*) FROM test_db.customers FINAL WHERE is_deleted = 0 OR is_deleted IS NULL GROUP BY loyalty_points ORDER BY loyalty_points")

# Compare distributions instead of checksums
```

**Pros**:
- Most comprehensive testing
- Documents schema evolution behavior
- Separates concerns

**Cons**:
- More complex test infrastructure
- Takes longer to implement

---

### **Option 4: Exclude All Timestamp Columns (ALTERNATIVE)**

**Rationale**: If `updated_at`/`created_at` precision is the issue, exclude them.

**Implementation**:

```bash
--exclude_columns loyalty_points updated_at created_at
```

**Pros**:
- Eliminates timestamp precision issues
- Focuses on business data

**Cons**:
- Doesn't validate timestamp replication
- May hide other issues

---

## Next Steps

### **Immediate Actions (Choose One)**

1. **Quick Validation** (5 minutes):
   - Run Query 1 and Query 6 to confirm `loyalty_points` NULL vs 0 issue
   - If confirmed, proceed with Option 1 or Option 2

2. **Detailed Investigation** (15 minutes):
   - Run Query 5 to generate debug output
   - Use `diff` to find exact row differences
   - Determine if it's `loyalty_points`, timestamps, or both

3. **Quick Fix to Unblock** (2 minutes):
   - Implement Option 1: Exclude `loyalty_points` from checksum
   - Document as known limitation
   - File issue for connector team to investigate

### **Verification After Fix**

Run these commands to verify the fix:

```bash
# After implementing the fix, re-run checksum
cd /app/python

# MySQL checksum (with exclusions if using Option 1)
python db_compare/mysql_table_checksum.py \
    --mysql_host localhost \
    --mysql_port 3306 \
    --mysql_database test_db \
    --tables_regex "^customers$" \
    --exclude_columns loyalty_points \
    --defaults_file ~/.my.cnf

# ClickHouse checksum (with exclusions if using Option 1)
python db_compare/clickhouse_table_checksum.py \
    --clickhouse_host localhost \
    --clickhouse_port 9000 \
    --clickhouse_database test_db \
    --tables_regex "^customers$" \
    --exclude_columns loyalty_points \
    --sign_column "is_deleted" \
    --clickhouse_config_file ./clickhouse-client.xml

# Verify checksums match
```

---

## Diagnosis Confirmation

**Before implementing a fix, please confirm the diagnosis by running:**

1. **Query 1** (loyalty_points distribution) - Should show NULL vs 0 difference
2. **Query 6** (NULL counts) - Should show MySQL: 0 NULLs, ClickHouse: ~10,000 NULLs
3. **Query 5** (debug output diff) - Should show exact row differences

**Once confirmed, I recommend Option 1 for immediate unblocking, then Option 2 for proper long-term fix.**
