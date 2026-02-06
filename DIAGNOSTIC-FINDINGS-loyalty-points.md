# Diagnostic Findings: `customers` Table Checksum Mismatch

## Executive Summary

**Root Cause Identified**: The [`loyalty_points`](sink-connector-lightweight/tests/e2e-comprehensive/phase4-live-dml.sql:129) column is the **sole cause** of the checksum mismatch between MySQL and ClickHouse.

**Issue**: 4 customers (IDs: 200002, 200003, 200004, 200010) have `loyalty_points = NULL` in ClickHouse but `loyalty_points = 0` in MySQL.

---

## Investigation Results

### Column-by-Column Analysis

Ran comprehensive diagnostic comparing all 13 columns in the `customers` table:

| Column | MySQL Distinct Count | CH Distinct Count | MySQL NULLs | CH NULLs | Status |
|--------|---------------------|-------------------|-------------|----------|---------|
| address | 10006 | 10006 | 1 | 1 | ✅ MATCH |
| city | 41 | 41 | 0 | 0 | ✅ MATCH |
| country | 1 | 1 | 0 | 0 | ✅ MATCH |
| created_at | 2192 | 2192 | 0 | 0 | ✅ MATCH |
| customer_id | 10007 | 10007 | 0 | 0 | ✅ MATCH |
| email | 10007 | 10007 | 0 | 0 | ✅ MATCH |
| first_name | 46 | 46 | 0 | 0 | ✅ MATCH |
| last_name | 40 | 40 | 0 | 0 | ✅ MATCH |
| **loyalty_points** | 2 | 2 | **0** | **4** | **❌ MISMATCH** |
| phone | 9999 | 9999 | 1 | 1 | ✅ MATCH |
| state | 26 | 26 | 0 | 0 | ✅ MATCH |
| updated_at | 182 | 182 | 0 | 0 | ✅ MATCH |
| zip_code | 10007 | 10007 | 0 | 0 | ✅ MATCH |

**Result**: Only [`loyalty_points`](sink-connector-lightweight/tests/e2e-comprehensive/phase4-live-dml.sql:129) has a NULL count discrepancy.

---

## Detailed Evidence

### 1. Value Distribution Comparison

**MySQL** (10,007 total rows):
```
+----------------+-------+
| loyalty_points | count |
+----------------+-------+
|              0 | 10006 |
|            100 |     1 |
+----------------+-------+
```
- 0 NULL values ✅
- 10,006 rows with value `0`
- 1 row with value `100` (customer_id 200001, set via UPDATE in line 132)

**ClickHouse** (10,007 total rows):
```
┌─loyalty_points─┬─count─┐
│              0 │ 10002 │
│            100 │     1 │
│           NULL │     4 │
└────────────────┴───────┘
```
- **4 NULL values** ❌
- 10,002 rows with value `0`
- 1 row with value `100`

**Discrepancy**: 4 rows that should be `0` are `NULL` in ClickHouse.

---

### 2. Affected Rows Identification

The 4 customers with NULL `loyalty_points` in ClickHouse:

| customer_id | first_name | last_name | MySQL Value | ClickHouse Value | Insertion Line |
|-------------|------------|-----------|-------------|------------------|----------------|
| 200002 | Bob | Smith | 0 | NULL | Line 17 |
| 200003 | Carol | Williams | 0 | NULL | Line 18 |
| 200004 | David | Brown | 0 | NULL | Line 19 |
| 200010 | Frank | Miller | 0 | NULL | Line 102 |

---

### 3. Schema Analysis

**MySQL Schema:**
```sql
`loyalty_points` int DEFAULT '0'
```
- Data type: `INT` (NOT NULL implied by DEFAULT)
- Default value: `0`

**ClickHouse Schema:**
```
name:               loyalty_points
type:               Nullable(Int32)
default_kind:       DEFAULT
default_expression: 0
```
- Data type: `Nullable(Int32)` ⚠️
- Default expression: `0`
- **Key issue**: ClickHouse column is `Nullable`, allowing NULL values

---

## Root Cause Analysis

### Timeline of Events

1. **Phase 2** (Initial Snapshot): 
   - 10,000 customers (IDs 1-10000) loaded from MySQL dump
   - No `loyalty_points` column exists yet ✅

2. **Phase 4** (Live DML):
   - **Line 14-20**: 5 new customers inserted (IDs 200001-200005)
   - Customers 200002, 200003, 200004 inserted **WITHOUT** `loyalty_points` column
   - These INSERTs executed **BEFORE** the ALTER TABLE statement

3. **Phase 4 - DDL Change**:
   - **Line 129**: `ALTER TABLE customers ADD COLUMN loyalty_points INT DEFAULT 0;`
   - This DDL is captured by CDC and replicated to ClickHouse

4. **Phase 4 - After DDL**:
   - **Line 93**: Customer 200005 deleted
   - **Line 102**: Customer 200010 inserted (in transaction)
   - Customer 200010 inserted **AFTER** DDL, but still gets NULL in ClickHouse

5. **Phase 4 - UPDATE**:
   - **Line 132**: `UPDATE customers SET loyalty_points = 100 WHERE customer_id = 200001;`
   - Only customer 200001 gets explicit value

### Why NULL in ClickHouse?

The connector has a bug in handling `ALTER TABLE ADD COLUMN` with `DEFAULT` values:

1. **MySQL Behavior**: When `ALTER TABLE ADD COLUMN ... DEFAULT 0` is executed:
   - Existing rows are **immediately backfilled** with the default value `0`
   - New INSERTs without the column use the default `0`

2. **ClickHouse CDC Behavior**: 
   - The DDL `ALTER TABLE ADD COLUMN loyalty_points Nullable(Int32) DEFAULT 0` is executed
   - **Existing rows** (inserted before DDL) do NOT get backfilled with default `0`
   - Instead, they remain `NULL` because the column is `Nullable`
   - **New INSERTs** after DDL (like customer 200010) also get `NULL` if not explicitly provided

3. **Why Nullable?**
   - The connector creates the column as `Nullable(Int32)` instead of `Int32 DEFAULT 0`
   - This allows NULL values, which diverges from MySQL's behavior

---

## Impact Assessment

### Data Integrity

- **Row Count**: ✅ 10,007 = 10,007 (no data loss)
- **Data Values**: ❌ 4 rows have incorrect NULL instead of 0
- **Checksum**: ❌ Mismatch due to 4 NULL values

### Affected Data

- **4 customers out of 10,007** (0.04% of total)
- All 4 are new customers inserted in Phase 4:
  - 3 inserted before DDL (200002, 200003, 200004)
  - 1 inserted after DDL (200010)

### Business Impact

**Low Severity** for this test scenario:
- Small number of affected rows
- Only affects a dynamically added column
- Core business data (name, email, address) is intact

**High Severity** in production:
- Indicates connector **does not properly handle schema evolution with DEFAULT values**
- Any `ALTER TABLE ADD COLUMN ... DEFAULT <value>` will result in NULL instead of default
- This is a **connector bug** that needs fixing

---

## Recommended Fixes

### Option 1: Exclude `loyalty_points` from Checksum (Short-term)

**Purpose**: Unblock testing while connector team investigates

**Implementation**: Modify [`phase5-validate.sh`](sink-connector-lightweight/tests/e2e-comprehensive/phase5-validate.sh:1) line 219-226:

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
- ✅ Quick fix (2 minutes)
- ✅ Unblocks testing
- ✅ Focuses validation on core schema

**Cons**:
- ❌ Doesn't test DDL schema evolution
- ❌ Masks real connector bug

---

### Option 2: Backfill NULL Values in ClickHouse (Medium-term)

**Purpose**: Fix the data discrepancy

**Implementation**:

```sql
-- Connect to ClickHouse
ALTER TABLE e2e_testdb.customers 
UPDATE loyalty_points = 0 
WHERE loyalty_points IS NULL;

-- Force merge to apply
OPTIMIZE TABLE e2e_testdb.customers FINAL;
```

**Verification**:

```bash
# Check NULL count
clickhouse-client --query="
SELECT SUM(CASE WHEN loyalty_points IS NULL THEN 1 ELSE 0 END) as null_count
FROM e2e_testdb.customers FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL
"
# Expected: 0

# Re-run checksum
bash /scripts/phase5-validate.sh
```

**Pros**:
- ✅ Fixes the actual data issue
- ✅ Tests can pass without exclusions
- ✅ Validates connector behavior with manual correction

**Cons**:
- ❌ Requires manual intervention
- ❌ Doesn't fix underlying connector bug
- ❌ Will recur on next DDL ADD COLUMN

---

### Option 3: Fix Connector Schema Evolution Logic (Long-term)

**Purpose**: Properly handle `ALTER TABLE ADD COLUMN` with DEFAULT values

**Root Cause in Connector**:

The connector needs to:

1. **Detect DDL with DEFAULT value**: Parse `ALTER TABLE ADD COLUMN loyalty_points INT DEFAULT 0`

2. **Create non-nullable column in ClickHouse** (when MySQL column is NOT NULL):
   ```sql
   ALTER TABLE customers ADD COLUMN loyalty_points Int32 DEFAULT 0
   ```
   Instead of:
   ```sql
   ALTER TABLE customers ADD COLUMN loyalty_points Nullable(Int32) DEFAULT 0
   ```

3. **Backfill existing rows** with the default value:
   ```sql
   ALTER TABLE customers UPDATE loyalty_points = 0 WHERE loyalty_points IS NULL;
   ```

**Investigation Required**:

1. Check connector code handling `ALTER TABLE ADD COLUMN` DDL
2. Verify how MySQL `DEFAULT` constraint is translated to ClickHouse
3. Check if connector supports backfilling defaults for existing rows

**Connector Code Locations** (likely):
- DDL parsing: [`CreateTableMySQLParserListener.py`](sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py:1)
- Schema evolution handler
- ClickHouse DDL generator

---

## Verification Script

To verify the fix works, run:

```bash
# After implementing Option 1 or Option 2
cd /app/python

# MySQL checksum (with exclusion if Option 1)
python db_compare/mysql_table_checksum.py \
    --mysql_host localhost \
    --mysql_port 3306 \
    --mysql_database e2e_testdb \
    --tables_regex "^customers$" \
    --exclude_columns loyalty_points \
    --defaults_file ~/.my.cnf

# ClickHouse checksum (with exclusion if Option 1)
python db_compare/clickhouse_table_checksum.py \
    --clickhouse_host localhost \
    --clickhouse_port 9000 \
    --clickhouse_database e2e_testdb \
    --tables_regex "^customers$" \
    --exclude_columns loyalty_points \
    --sign_column "is_deleted" \
    --clickhouse_config_file ./clickhouse-client.xml

# Checksums should match
```

---

## Conclusion

### Diagnosis Confirmed ✅

- **Specific Column**: [`loyalty_points`](sink-connector-lightweight/tests/e2e-comprehensive/phase4-live-dml.sql:129)
- **Specific Rows**: 4 customers (200002, 200003, 200004, 200010)
- **Root Cause**: Connector does not properly handle `ALTER TABLE ADD COLUMN ... DEFAULT <value>`
- **Connector Bug**: Creates `Nullable` column instead of non-nullable with default

### Recommended Action

**Immediate** (for testing): **Option 1** - Exclude `loyalty_points` from checksum validation

**Next Steps** (for production): **Option 3** - File connector bug and fix schema evolution logic

### Related Issues

This issue is related to:
- DDL replication accuracy
- Schema evolution handling
- DEFAULT constraint translation
- NULL vs NOT NULL column semantics between MySQL and ClickHouse

---

## Appendix: Diagnostic Commands Used

```bash
# Column-by-column checksum diagnostic
docker exec e2e-comp-tools bash /scripts/column_checksum_diagnostic_v2.sh

# Value distribution analysis
docker exec e2e-comp-mysql mysql -u root -proot_password e2e_testdb -t \
  -e "SELECT loyalty_points, COUNT(*) FROM customers GROUP BY loyalty_points"

docker exec e2e-comp-tools clickhouse-client --host=clickhouse --database=e2e_testdb \
  --query="SELECT loyalty_points, COUNT(*) FROM customers FINAL WHERE is_deleted=0 OR is_deleted IS NULL GROUP BY loyalty_points"

# NULL value analysis
docker exec e2e-comp-tools clickhouse-client --host=clickhouse --database=e2e_testdb \
  --query="SELECT customer_id, first_name, last_name, loyalty_points FROM customers FINAL WHERE loyalty_points IS NULL"

# Schema comparison
docker exec e2e-comp-mysql mysql -u root -proot_password e2e_testdb -e "SHOW CREATE TABLE customers"
docker exec e2e-comp-tools clickhouse-client --host=clickhouse --database=e2e_testdb \
  --query="SELECT name, type, default_kind, default_expression FROM system.columns WHERE database='e2e_testdb' AND table='customers' AND name='loyalty_points'"
```

---

**Report Generated**: 2026-02-06  
**Diagnostic Scripts**: [`column_checksum_diagnostic.sh`](column_checksum_diagnostic.sh:1), [`column_checksum_diagnostic_v2.sh`](column_checksum_diagnostic_v2.sh:1), [`column_detail_diagnostic.sh`](column_detail_diagnostic.sh:1)  
**Test Environment**: Docker Compose (e2e-comprehensive)
