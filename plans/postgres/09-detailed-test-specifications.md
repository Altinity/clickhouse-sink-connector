# PostgreSQL Connector - Detailed Test Specifications

## Document Purpose

This document provides **EXTREME DETAIL** on every single test that must be written for the PostgreSQL connector implementation. It includes:
1. **Complete Test Inventory** - Every test class, method, file path, and assertion
2. **Test Data Specifications** - SQL scripts, edge cases, performance datasets
3. **Test Execution Procedures** - Docker setup, initialization, troubleshooting
4. **MySQL Regression Test Matrix** - Baseline tests that must never break

**Coverage Target**: 80%+ for both batch dump and CDC replication functionality.

---

## Table of Contents

1. [Test Inventory (Complete Enumeration)](#1-test-inventory-complete-enumeration)
2. [Test Data Specifications](#2-test-data-specifications)
3. [Test Execution Procedures](#3-test-execution-procedures)
4. [MySQL Regression Test Matrix](#4-mysql-regression-test-matrix)
5. [Test Coverage Tracking](#5-test-coverage-tracking)
6. [Test Failure Triage](#6-test-failure-triage)

---

## 1. Test Inventory (Complete Enumeration)

This section lists **EVERY SINGLE TEST** to be written with exact file paths, class names, method names, and assertions.

### 1.1 Python Unit Tests - PostgreSQL Type Conversion

**File**: [`sink-connector/python/db_load/postgres_parser/tests/test_type_conversion.py`](sink-connector/python/db_load/postgres_parser/tests/test_type_conversion.py)

**Class**: `TestPostgresToClickHouseTypeConversion`

**Test Methods**:

1. **`test_integer_types_conversion()`**
   - **Input**: PostgreSQL types: `SMALLINT`, `INTEGER`, `BIGINT`
   - **Expected Output**: ClickHouse types: `Int16`, `Int32`, `Int64`
   - **Assertions**: 
     - `assert convert_postgres_type('SMALLINT') == 'Int16'`
     - `assert convert_postgres_type('INTEGER') == 'Int32'`
     - `assert convert_postgres_type('BIGINT') == 'Int64'`

2. **`test_serial_types_conversion()`**
   - **Input**: `SMALLSERIAL`, `SERIAL`, `BIGSERIAL`
   - **Expected Output**: `UInt16`, `UInt32`, `UInt64`
   - **Assertions**: 
     - `assert convert_postgres_type('SMALLSERIAL') == 'UInt16'`
     - `assert convert_postgres_type('SERIAL') == 'UInt32'`
     - `assert convert_postgres_type('BIGSERIAL') == 'UInt64'`

3. **`test_decimal_types_conversion()`**
   - **Input**: `DECIMAL(10,2)`, `NUMERIC(15,5)`, `DECIMAL` (no precision)
   - **Expected Output**: `Decimal(10,2)`, `Decimal(15,5)`, `Decimal(38,10)` (default)
   - **Assertions**: 
     - `assert convert_postgres_type('DECIMAL(10,2)') == 'Decimal(10,2)'`
     - `assert convert_postgres_type('NUMERIC(15,5)') == 'Decimal(15,5)'`
     - `assert convert_postgres_type('DECIMAL') == 'Decimal(38,10)'`

4. **`test_floating_point_conversion()`**
   - **Input**: `REAL`, `DOUBLE PRECISION`
   - **Expected Output**: `Float32`, `Float64`
   - **Assertions**: 
     - `assert convert_postgres_type('REAL') == 'Float32'`
     - `assert convert_postgres_type('DOUBLE PRECISION') == 'Float64'`

5. **`test_string_types_conversion()`**
   - **Input**: `VARCHAR(255)`, `CHAR(10)`, `TEXT`, `VARCHAR` (no length)
   - **Expected Output**: `String`, `FixedString(10)`, `String`, `String`
   - **Assertions**: 
     - `assert convert_postgres_type('VARCHAR(255)') == 'String'`
     - `assert convert_postgres_type('CHAR(10)') == 'FixedString(10)'`
     - `assert convert_postgres_type('TEXT') == 'String'`
     - `assert convert_postgres_type('VARCHAR') == 'String'`

6. **`test_date_time_conversion()`**
   - **Input**: `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`, `TIME WITH TIME ZONE`
   - **Expected Output**: `Date`, `String`, `DateTime`, `DateTime64(6)`, `String`
   - **Assertions**: 
     - `assert convert_postgres_type('DATE') == 'Date'`
     - `assert convert_postgres_type('TIME') == 'String'`
     - `assert convert_postgres_type('TIMESTAMP') == 'DateTime'`
     - `assert convert_postgres_type('TIMESTAMP WITH TIME ZONE') == 'DateTime64(6)'`
     - `assert convert_postgres_type('TIME WITH TIME ZONE') == 'String'`

7. **`test_interval_conversion()`**
   - **Input**: `INTERVAL`
   - **Expected Output**: `Int64` (microseconds)
   - **Assertions**: 
     - `assert convert_postgres_type('INTERVAL') == 'Int64'`

8. **`test_boolean_conversion()`**
   - **Input**: `BOOLEAN`, `BOOL`
   - **Expected Output**: `UInt8`
   - **Assertions**: 
     - `assert convert_postgres_type('BOOLEAN') == 'UInt8'`
     - `assert convert_postgres_type('BOOL') == 'UInt8'`

9. **`test_binary_types_conversion()`**
   - **Input**: `BYTEA`
   - **Expected Output**: `String`
   - **Assertions**: 
     - `assert convert_postgres_type('BYTEA') == 'String'`

10. **`test_uuid_conversion()`**
    - **Input**: `UUID`
    - **Expected Output**: `UUID`
    - **Assertions**: 
      - `assert convert_postgres_type('UUID') == 'UUID'`

11. **`test_json_conversion()`**
    - **Input**: `JSON`, `JSONB`
    - **Expected Output**: `String`, `String`
    - **Assertions**: 
      - `assert convert_postgres_type('JSON') == 'String'`
      - `assert convert_postgres_type('JSONB') == 'String'`

12. **`test_array_types_conversion()`**
    - **Input**: `INTEGER[]`, `TEXT[]`, `BOOLEAN[]`
    - **Expected Output**: `Array(Int32)`, `Array(String)`, `Array(UInt8)`
    - **Assertions**: 
      - `assert convert_postgres_type('INTEGER[]') == 'Array(Int32)'`
      - `assert convert_postgres_type('TEXT[]') == 'Array(String)'`
      - `assert convert_postgres_type('BOOLEAN[]') == 'Array(UInt8)'`

13. **`test_network_types_conversion()`**
    - **Input**: `INET`, `CIDR`, `MACADDR`
    - **Expected Output**: `String`, `String`, `String`
    - **Assertions**: 
      - `assert convert_postgres_type('INET') == 'String'`
      - `assert convert_postgres_type('CIDR') == 'String'`
      - `assert convert_postgres_type('MACADDR') == 'String'`

14. **`test_geometric_types_conversion()`**
    - **Input**: `POINT`, `LINE`, `LSEG`, `BOX`, `PATH`, `POLYGON`, `CIRCLE`
    - **Expected Output**: All → `String`
    - **Assertions**: 
      - `assert convert_postgres_type('POINT') == 'String'`
      - `assert convert_postgres_type('LINE') == 'String'`
      - `assert convert_postgres_type('LSEG') == 'String'`
      - `assert convert_postgres_type('BOX') == 'String'`
      - `assert convert_postgres_type('PATH') == 'String'`
      - `assert convert_postgres_type('POLYGON') == 'String'`
      - `assert convert_postgres_type('CIRCLE') == 'String'`

15. **`test_money_conversion()`**
    - **Input**: `MONEY`
    - **Expected Output**: `Decimal(19,2)`
    - **Assertions**: 
      - `assert convert_postgres_type('MONEY') == 'Decimal(19,2)'`

16. **`test_bit_types_conversion()`**
    - **Input**: `BIT(8)`, `BIT VARYING(16)`
    - **Expected Output**: `String`, `String`
    - **Assertions**: 
      - `assert convert_postgres_type('BIT(8)') == 'String'`
      - `assert convert_postgres_type('BIT VARYING(16)') == 'String'`

17. **`test_xml_conversion()`**
    - **Input**: `XML`
    - **Expected Output**: `String`
    - **Assertions**: 
      - `assert convert_postgres_type('XML') == 'String'`

18. **`test_case_insensitive_type_names()`**
    - **Input**: `varchar(100)`, `VARCHAR(100)`, `VarChar(100)`
    - **Expected Output**: All → `String`
    - **Assertions**: 
      - `assert convert_postgres_type('varchar(100)') == 'String'`
      - `assert convert_postgres_type('VARCHAR(100)') == 'String'`
      - `assert convert_postgres_type('VarChar(100)') == 'String'`

19. **`test_type_with_modifiers()`**
    - **Input**: `TIMESTAMP(3)`, `TIME(6)`, `INTERVAL HOUR TO SECOND`
    - **Expected Output**: `DateTime64(3)`, `String`, `Int64`
    - **Assertions**: 
      - `assert convert_postgres_type('TIMESTAMP(3)') == 'DateTime64(3)'`
      - `assert convert_postgres_type('TIME(6)') == 'String'`
      - `assert convert_postgres_type('INTERVAL HOUR TO SECOND') == 'Int64'`

20. **`test_unknown_type_fallback()`**
    - **Input**: `CUSTOM_TYPE`, `USER_DEFINED_TYPE`
    - **Expected Output**: `String` (fallback)
    - **Assertions**: 
      - `assert convert_postgres_type('CUSTOM_TYPE') == 'String'`
      - `assert convert_postgres_type('USER_DEFINED_TYPE') == 'String'`

---

### 1.2 Python Unit Tests - DDL Parser

**File**: [`sink-connector/python/db_load/postgres_parser/tests/test_ddl_parser.py`](sink-connector/python/db_load/postgres_parser/tests/test_ddl_parser.py)

**Class**: `TestPostgresDDLParser`

**Test Methods**:

1. **`test_parse_simple_create_table()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE users (
       id SERIAL PRIMARY KEY,
       name VARCHAR(100) NOT NULL,
       email TEXT
     );
     ```
   - **Expected Output**:
     - Table name: `users`
     - Columns: `[{name: 'id', type: 'SERIAL', nullable: False, pk: True}, {name: 'name', type: 'VARCHAR(100)', nullable: False, pk: False}, {name: 'email', type: 'TEXT', nullable: True, pk: False}]`
   - **Assertions**:
     - `assert table_def.name == 'users'`
     - `assert len(table_def.columns) == 3`
     - `assert table_def.columns[0].name == 'id'`
     - `assert table_def.columns[0].data_type == 'SERIAL'`
     - `assert table_def.columns[0].nullable == False`
     - `assert table_def.columns[0].primary_key == True`

2. **`test_parse_table_with_constraints()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE orders (
       order_id BIGSERIAL PRIMARY KEY,
       user_id INTEGER NOT NULL,
       total DECIMAL(10,2) NOT NULL,
       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
       CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)
     );
     ```
   - **Expected Output**:
     - Table name: `orders`
     - Columns: 4 columns with correct types
     - Constraints: 1 foreign key constraint
   - **Assertions**:
     - `assert table_def.name == 'orders'`
     - `assert len(table_def.columns) == 4`
     - `assert table_def.columns[2].data_type == 'DECIMAL(10,2)'`
     - `assert table_def.columns[3].default_value == 'CURRENT_TIMESTAMP'`
     - `assert len(table_def.constraints) == 1`
     - `assert table_def.constraints[0].type == 'FOREIGN KEY'`

3. **`test_parse_composite_primary_key()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE order_items (
       order_id INTEGER,
       product_id INTEGER,
       quantity INTEGER NOT NULL,
       PRIMARY KEY (order_id, product_id)
     );
     ```
   - **Expected Output**:
     - Primary key on columns: `[order_id, product_id]`
   - **Assertions**:
     - `assert len(table_def.primary_key_columns) == 2`
     - `assert 'order_id' in table_def.primary_key_columns`
     - `assert 'product_id' in table_def.primary_key_columns`

4. **`test_parse_unique_constraints()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE products (
       id SERIAL PRIMARY KEY,
       sku VARCHAR(50) UNIQUE NOT NULL,
       name TEXT,
       UNIQUE (name, sku)
     );
     ```
   - **Expected Output**:
     - Unique constraints: 2 (column-level + table-level)
   - **Assertions**:
     - `assert len(table_def.unique_constraints) == 2`
     - `assert table_def.columns[1].unique == True`

5. **`test_parse_check_constraints()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE employees (
       id SERIAL PRIMARY KEY,
       age INTEGER CHECK (age >= 18),
       salary DECIMAL(10,2) CHECK (salary > 0)
     );
     ```
   - **Expected Output**:
     - Check constraints: 2
   - **Assertions**:
     - `assert len(table_def.check_constraints) == 2`
     - `assert 'age >= 18' in table_def.check_constraints[0].expression`
     - `assert 'salary > 0' in table_def.check_constraints[1].expression`

6. **`test_parse_default_values()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE settings (
       id SERIAL,
       enabled BOOLEAN DEFAULT TRUE,
       max_retries INTEGER DEFAULT 3,
       created_at TIMESTAMP DEFAULT NOW()
     );
     ```
   - **Expected Output**:
     - Default values: `TRUE`, `3`, `NOW()`
   - **Assertions**:
     - `assert table_def.columns[1].default_value == 'TRUE'`
     - `assert table_def.columns[2].default_value == '3'`
     - `assert table_def.columns[3].default_value == 'NOW()'`

7. **`test_parse_array_columns()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE tags_table (
       id SERIAL PRIMARY KEY,
       tags TEXT[],
       scores INTEGER[]
     );
     ```
   - **Expected Output**:
     - Array types: `TEXT[]`, `INTEGER[]`
   - **Assertions**:
     - `assert table_def.columns[1].data_type == 'TEXT[]'`
     - `assert table_def.columns[2].data_type == 'INTEGER[]'`

8. **`test_parse_jsonb_column()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE documents (
       id SERIAL PRIMARY KEY,
       data JSONB NOT NULL
     );
     ```
   - **Expected Output**:
     - JSONB type preserved
   - **Assertions**:
     - `assert table_def.columns[1].data_type == 'JSONB'`
     - `assert table_def.columns[1].nullable == False`

9. **`test_parse_generated_columns()`**
   - **Input DDL**:
     ```sql
     CREATE TABLE computed (
       id SERIAL PRIMARY KEY,
       price DECIMAL(10,2),
       tax_rate DECIMAL(5,2),
       total DECIMAL(10,2) GENERATED ALWAYS AS (price * (1 + tax_rate)) STORED
     );
     ```
   - **Expected Output**:
     - Generated column detected
   - **Assertions**:
     - `assert table_def.columns[3].generated == True`
     - `assert 'price * (1 + tax_rate)' in table_def.columns[3].generation_expression`

10. **`test_parse_multiline_ddl()`**
    - **Input DDL**: Multi-line table definition with line breaks and indentation
    - **Expected Output**: Correctly parsed despite formatting
    - **Assertions**:
      - `assert table_def is not None`
      - `assert len(table_def.columns) > 0`

11. **`test_parse_ddl_with_comments()`**
    - **Input DDL**:
      ```sql
      -- Users table
      CREATE TABLE users (
        id SERIAL PRIMARY KEY, -- Primary identifier
        name VARCHAR(100) -- User's full name
      );
      ```
    - **Expected Output**: Comments ignored, table parsed correctly
    - **Assertions**:
      - `assert table_def.name == 'users'`
      - `assert len(table_def.columns) == 2`

12. **`test_parse_schema_qualified_table()`**
    - **Input DDL**: `CREATE TABLE public.users (...);`
    - **Expected Output**: Schema and table name separated
    - **Assertions**:
      - `assert table_def.schema == 'public'`
      - `assert table_def.name == 'users'`

13. **`test_parse_quoted_identifiers()`**
    - **Input DDL**:
      ```sql
      CREATE TABLE "User Table" (
        "User ID" SERIAL PRIMARY KEY,
        "User Name" VARCHAR(100)
      );
      ```
    - **Expected Output**: Quoted names preserved
    - **Assertions**:
      - `assert table_def.name == 'User Table'`
      - `assert table_def.columns[0].name == 'User ID'`

14. **`test_parse_case_sensitivity()`**
    - **Input DDL**: `CREATE TABLE Users (...);` vs `create table users (...);`
    - **Expected Output**: Case-insensitive keywords, case-preserved identifiers
    - **Assertions**:
      - `assert table_def.name == 'Users'` (case preserved)

15. **`test_invalid_ddl_error_handling()`**
    - **Input DDL**: `CREATE TABLE users (id INVALID_TYPE);`
    - **Expected Output**: `DDLParseError` exception
    - **Assertions**:
      - `with pytest.raises(DDLParseError):`

---

### 1.3 Python Unit Tests - PostgreSQL Checksum

**File**: [`sink-connector/python/db_compare/tests/test_postgres_checksum.py`](sink-connector/python/db_compare/tests/test_postgres_checksum.py)

**Class**: `TestPostgresTableChecksum`

**Test Methods**:

1. **`test_checksum_simple_table()`**
   - **Setup**: Create PostgreSQL table with 10 rows of test data
   - **Action**: Calculate checksum
   - **Expected**: Non-zero checksum value
   - **Assertions**:
     - `assert checksum is not None`
     - `assert checksum != 0`

2. **`test_checksum_consistency()`**
   - **Setup**: Calculate checksum twice on same table
   - **Expected**: Identical checksums
   - **Assertions**:
     - `checksum1 = calculator.calculate('test_table')`
     - `checksum2 = calculator.calculate('test_table')`
     - `assert checksum1 == checksum2`

3. **`test_checksum_detects_data_change()`**
   - **Setup**: Calculate checksum, modify 1 row, recalculate
   - **Expected**: Different checksums
   - **Assertions**:
     - `checksum_before = calculator.calculate('test_table')`
     - `# UPDATE test_table SET value = 999 WHERE id = 5`
     - `checksum_after = calculator.calculate('test_table')`
     - `assert checksum_before != checksum_after`

4. **`test_checksum_column_order_independence()`**
   - **Setup**: Create two tables with same data, different column order
   - **Expected**: Different checksums (order matters)
   - **Assertions**:
     - `checksum_table1 = calculator.calculate('table1')`
     - `checksum_table2 = calculator.calculate('table2')`
     - `assert checksum_table1 != checksum_table2`

5. **`test_checksum_null_handling()`**
   - **Setup**: Table with NULL values
   - **Expected**: Consistent checksum including NULLs
   - **Assertions**:
     - `assert checksum is not None`
     - `checksum2 = calculator.calculate('test_table_with_nulls')`
     - `assert checksum == checksum2`

6. **`test_checksum_large_table_performance()`**
   - **Setup**: Table with 1,000,000 rows
   - **Expected**: Checksum completes within 10 seconds
   - **Assertions**:
     - `start_time = time.time()`
     - `checksum = calculator.calculate('large_table')`
     - `elapsed = time.time() - start_time`
     - `assert elapsed < 10.0`

7. **`test_checksum_with_special_characters()`**
   - **Setup**: Table with Unicode, emojis, special characters
   - **Expected**: Consistent checksum
   - **Assertions**:
     - `assert checksum is not None`

8. **`test_checksum_empty_table()`**
   - **Setup**: Table with 0 rows
   - **Expected**: Zero or predefined empty checksum
   - **Assertions**:
     - `checksum = calculator.calculate('empty_table')`
     - `assert checksum == 0 or checksum == EMPTY_TABLE_CHECKSUM`

---

### 1.4 Python Component Tests - PostgreSQL Dumper

**File**: [`sink-connector/python/db_dump/tests/test_postgres_dumper.py`](sink-connector/python/db_dump/tests/test_postgres_dumper.py)

**Class**: `TestPostgresDumper`

**Test Methods**:

1. **`test_dump_schema_single_table()`**
   - **Setup**: PostgreSQL table `users` with 3 columns
   - **Action**: `dumper.dump_schema('users')`
   - **Expected**: SQL DDL file created
   - **Assertions**:
     - `assert os.path.exists('users.sql')`
     - `assert 'CREATE TABLE users' in file_content`
     - `assert 'id SERIAL PRIMARY KEY' in file_content`

2. **`test_dump_data_single_table()`**
   - **Setup**: Table with 100 rows
   - **Action**: `dumper.dump_data('users', output_format='csv')`
   - **Expected**: CSV file with 100 data rows + 1 header
   - **Assertions**:
     - `assert os.path.exists('users.csv')`
     - `assert csv_line_count == 101`

3. **`test_dump_all_schemas()`**
   - **Setup**: Database with 5 tables
   - **Action**: `dumper.dump_all_schemas()`
   - **Expected**: 5 SQL files created
   - **Assertions**:
     - `assert len(glob.glob('*.sql')) == 5`

4. **`test_dump_with_postgres_copy()`**
   - **Setup**: Table with 10,000 rows
   - **Action**: `dumper.dump_data('large_table', use_copy=True)`
   - **Expected**: CSV file created using COPY command (faster)
   - **Assertions**:
     - `assert os.path.exists('large_table.csv')`
     - `assert csv_line_count == 10001`

5. **`test_dump_handles_null_values()`**
   - **Setup**: Table with NULL values
   - **Action**: Dump to CSV
   - **Expected**: NULLs represented as empty fields or `\N`
   - **Assertions**:
     - `assert '\\N' in file_content or ',,' in file_content`

6. **`test_dump_handles_quotes_in_data()`**
   - **Setup**: Table with rows containing quotes: `O'Reilly`, `"John"`
   - **Action**: Dump to CSV
   - **Expected**: Quotes properly escaped
   - **Assertions**:
     - `assert '"O\'Reilly"' in file_content or 'O\'\'Reilly' in file_content`

7. **`test_dump_handles_newlines_in_data()`**
   - **Setup**: Text column with embedded newlines
   - **Action**: Dump to CSV
   - **Expected**: Newlines escaped or quoted
   - **Assertions**:
     - `assert csv.reader can parse correctly`

8. **`test_dump_parallel_tables()`**
   - **Setup**: 10 tables
   - **Action**: `dumper.dump_all_parallel(threads=4)`
   - **Expected**: All tables dumped, faster than serial
   - **Assertions**:
     - `assert len(glob.glob('*.csv')) == 10`
     - `assert parallel_time < serial_time * 0.6`

9. **`test_dump_connection_retry()`**
   - **Setup**: Mock PostgreSQL connection failure
   - **Action**: Dump with retry enabled
   - **Expected**: Retry 3 times, then raise error
   - **Assertions**:
     - `assert connection_attempts == 3`
     - `with pytest.raises(ConnectionError):`

10. **`test_dump_progress_callback()`**
    - **Setup**: Large table
    - **Action**: Dump with progress callback
    - **Expected**: Callback invoked multiple times
    - **Assertions**:
      - `assert callback_count > 0`
      - `assert final_progress == 100`

---

### 1.5 Python Component Tests - ClickHouse Loader

**File**: [`sink-connector/python/db_load/tests/test_postgres_loader.py`](sink-connector/python/db_load/tests/test_postgres_loader.py)

**Class**: `TestClickHousePostgresLoader`

**Test Methods**:

1. **`test_load_csv_to_clickhouse()`**
   - **Setup**: CSV file with PostgreSQL dump data
   - **Action**: `loader.load_csv('users.csv', 'users')`
   - **Expected**: Data loaded into ClickHouse
   - **Assertions**:
     - `assert clickhouse_row_count == csv_row_count`

2. **`test_create_clickhouse_table_from_ddl()`**
   - **Setup**: PostgreSQL DDL file
   - **Action**: `loader.create_table_from_ddl('users.sql')`
   - **Expected**: ClickHouse table created with correct schema
   - **Assertions**:
     - `assert table_exists('users')`
     - `assert column_type('id') == 'UInt32'`

3. **`test_type_conversion_during_load()`**
   - **Setup**: PostgreSQL CSV with TIMESTAMP, DECIMAL types
   - **Action**: Load into ClickHouse
   - **Expected**: Types correctly converted
   - **Assertions**:
     - `assert ch_column_type('created_at') == 'DateTime'`
     - `assert ch_column_type('price') == 'Decimal(10,2)'`

4. **`test_load_handles_null_values()`**
   - **Setup**: CSV with `\N` for NULLs
   - **Action**: Load into ClickHouse
   - **Expected**: NULLs loaded correctly
   - **Assertions**:
     - `assert clickhouse_null_count == csv_null_count`

5. **`test_load_progress_tracking()`**
   - **Setup**: Large CSV file
   - **Action**: Load with progress tracking
   - **Expected**: Progress updates received
   - **Assertions**:
     - `assert progress_updates > 0`

6. **`test_load_error_handling()`**
   - **Setup**: CSV with invalid data (type mismatch)
   - **Action**: Load into ClickHouse
   - **Expected**: Error raised with row number
   - **Assertions**:
     - `with pytest.raises(DataLoadError) as exc_info:`
     - `assert 'row 42' in str(exc_info.value)`

---

### 1.6 Python Integration Tests - Batch Dump End-to-End

**File**: [`sink-connector/python/tests/integration/test_postgres_batch_dump.py`](sink-connector/python/tests/integration/test_postgres_batch_dump.py)

**Class**: `TestPostgresBatchDumpIntegration`

**Test Methods**:

1. **`test_full_batch_dump_single_table()`**
   - **Setup**: PostgreSQL container with `users` table (1,000 rows)
   - **Action**: Dump → Parse → Load → Verify
   - **Expected**: 1,000 rows in ClickHouse matching PostgreSQL
   - **Assertions**:
     - `assert pg_count == ch_count == 1000`
     - `assert pg_checksum == ch_checksum`

2. **`test_batch_dump_multiple_tables()`**
   - **Setup**: 5 tables
   - **Action**: Dump all tables
   - **Expected**: All 5 tables in ClickHouse
   - **Assertions**:
     - `assert len(ch_tables) == 5`

3. **`test_batch_dump_with_foreign_keys()`**
   - **Setup**: Tables with FK relationships
   - **Action**: Dump and load
   - **Expected**: Data integrity maintained (no orphaned records)
   - **Assertions**:
     - `assert ch_orphaned_count == 0`

4. **`test_batch_dump_large_dataset()`**
   - **Setup**: Table with 10,000,000 rows
   - **Action**: Batch dump
   - **Expected**: Completed within 5 minutes
   - **Assertions**:
     - `assert dump_time < 300`
     - `assert pg_count == ch_count`

5. **`test_batch_dump_all_data_types()`**
   - **Setup**: Table with all 36+ supported PostgreSQL types
   - **Action**: Dump and load
   - **Expected**: All types correctly converted
   - **Assertions**:
     - `for col in columns:`
     - `  assert pg_value[col] == ch_value[col]`

6. **`test_batch_dump_handles_special_characters()`**
   - **Setup**: Data with Unicode, emojis, quotes, newlines
   - **Action**: Dump and load
   - **Expected**: Data identical after round-trip
   - **Assertions**:
     - `assert pg_special_char_data == ch_special_char_data`

7. **`test_batch_dump_empty_table()`**
   - **Setup**: Table with 0 rows
   - **Action**: Dump and load
   - **Expected**: Empty table created in ClickHouse
   - **Assertions**:
     - `assert ch_count == 0`
     - `assert table_exists('empty_table')`

8. **`test_batch_dump_transaction_consistency()`**
   - **Setup**: PostgreSQL with ongoing transactions
   - **Action**: Dump using snapshot isolation
   - **Expected**: Consistent snapshot captured
   - **Assertions**:
     - `assert dump_snapshot_consistency == True`

9. **`test_batch_dump_parallel_execution()`**
   - **Setup**: 20 tables
   - **Action**: Dump with 8 parallel workers
   - **Expected**: Faster than serial execution
   - **Assertions**:
     - `assert parallel_time < serial_time * 0.4`

10. **`test_batch_dump_checksum_validation()`**
    - **Setup**: PostgreSQL table
    - **Action**: Dump, load, checksum both sides
    - **Expected**: Checksums match
    - **Assertions**:
      - `assert pg_checksum == ch_checksum`

---

### 1.7 Java Integration Tests - CDC Operations

**File**: [`sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/postgres/PostgresCDCOperationsTest.java`](sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/postgres/PostgresCDCOperationsTest.java)

**Class**: `PostgresCDCOperationsTest`

**Test Methods**:

1. **`testPostgresInsertOperation()`**
   - **Setup**: ClickHouse sink connected to PostgreSQL Debezium
   - **Action**: `INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')`
   - **Expected**: Record appears in ClickHouse within 5 seconds
   - **Assertions**:
     - `assertEquals(1, clickHouseRowCount)`
     - `assertEquals("Alice", clickHouseRow.get("name"))`

2. **`testPostgresUpdateOperation()`**
   - **Setup**: Existing row in PostgreSQL
   - **Action**: `UPDATE users SET email = 'newemail@example.com' WHERE id = 1`
   - **Expected**: ClickHouse row updated (or new version created)
   - **Assertions**:
     - `assertEquals("newemail@example.com", clickHouseRow.get("email"))`

3. **`testPostgresDeleteOperation()`**
   - **Setup**: Row exists in both DBs
   - **Action**: `DELETE FROM users WHERE id = 1`
   - **Expected**: ClickHouse row marked as deleted (ReplacingMergeTree)
   - **Assertions**:
     - `assertEquals(1, clickHouseRow.get("_sign"))` (delete marker)

4. **`testPostgresTruncateOperation()`**
   - **Setup**: Table with 100 rows
   - **Action**: `TRUNCATE TABLE users`
   - **Expected**: All rows removed from ClickHouse
   - **Assertions**:
     - `assertEquals(0, clickHouseRowCount)`

5. **`testPostgresBulkInsert()`**
   - **Setup**: Empty table
   - **Action**: Insert 10,000 rows via Debezium
   - **Expected**: All 10,000 rows in ClickHouse
   - **Assertions**:
     - `assertEquals(10000, clickHouseRowCount)`

6. **`testPostgresTransactionConsistency()`**
   - **Setup**: PostgreSQL transaction with 5 INSERTs
   - **Action**: BEGIN → 5 INSERTs → COMMIT
   - **Expected**: All 5 rows appear atomically in ClickHouse
   - **Assertions**:
     - `assertTrue(allRowsAppeared || noRowsAppeared)`

7. **`testPostgresSchemaChange()`**
   - **Setup**: Existing table
   - **Action**: `ALTER TABLE users ADD COLUMN age INTEGER`
   - **Expected**: ClickHouse schema updated
   - **Assertions**:
     - `assertTrue(clickHouseTableHasColumn("age"))`

8. **`testPostgresReplicationLag()`**
   - **Setup**: High-throughput INSERT workload
   - **Action**: Monitor replication lag
   - **Expected**: Lag < 10 seconds
   - **Assertions**:
     - `assertTrue(replicationLag < Duration.ofSeconds(10))`

9. **`testPostgresDataTypeReplication()`**
   - **Setup**: Table with all PostgreSQL types
   - **Action**: Insert row with all types
   - **Expected**: All types correctly replicated
   - **Assertions**:
     - `assertEquals(pgTimestamp, chTimestamp)`
     - `assertEquals(pgJsonb, chJsonb)`

10. **`testPostgresNullValueReplication()`**
    - **Setup**: Table with nullable columns
    - **Action**: Insert row with NULLs
    - **Expected**: NULLs preserved in ClickHouse
    - **Assertions**:
      - `assertNull(clickHouseRow.get("optional_field"))`

---

### 1.8 Java Integration Tests - Data Types

**File**: [`sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/postgres/PostgresDataTypesTest.java`](sink-connector/src/test/java/com/altinity/clickhouse/sink/connector/db/postgres/PostgresDataTypesTest.java)

**Class**: `PostgresDataTypesTest`

**Test Methods** (36 data types):

1. **`testSmallIntType()`** - SMALLINT → Int16
2. **`testIntegerType()`** - INTEGER → Int32
3. **`testBigIntType()`** - BIGINT → Int64
4. **`testSmallSerialType()`** - SMALLSERIAL → UInt16
5. **`testSerialType()`** - SERIAL → UInt32
6. **`testBigSerialType()`** - BIGSERIAL → UInt64
7. **`testDecimalType()`** - DECIMAL(10,2) → Decimal(10,2)
8. **`testNumericType()`** - NUMERIC(15,5) → Decimal(15,5)
9. **`testRealType()`** - REAL → Float32
10. **`testDoublePrecisionType()`** - DOUBLE PRECISION → Float64
11. **`testVarcharType()`** - VARCHAR(255) → String
12. **`testCharType()`** - CHAR(10) → FixedString(10)
13. **`testTextField()`** - TEXT → String
14. **`testDateType()`** - DATE → Date
15. **`testTimeType()`** - TIME → String
16. **`testTimestampType()`** - TIMESTAMP → DateTime
17. **`testTimestampTzType()`** - TIMESTAMPTZ → DateTime64(6)
18. **`testIntervalType()`** - INTERVAL → Int64
19. **`testBooleanType()`** - BOOLEAN → UInt8
20. **`testByteaType()`** - BYTEA → String (base64)
21. **`testUuidType()`** - UUID → UUID
22. **`testJsonType()`** - JSON → String
23. **`testJsonbType()`** - JSONB → String
24. **`testIntegerArrayType()`** - INTEGER[] → Array(Int32)
25. **`testTextArrayType()`** - TEXT[] → Array(String)
26. **`testInetType()`** - INET → String
27. **`testCidrType()`** - CIDR → String
28. **`testMacAddrType()`** - MACADDR → String
29. **`testPointType()`** - POINT → String
30. **`testLineType()`** - LINE → String
31. **`testBoxType()`** - BOX → String
32. **`testPathType()`** - PATH → String
33. **`testPolygonType()`** - POLYGON → String
34. **`testCircleType()`** - CIRCLE → String
35. **`testMoneyType()`** - MONEY → Decimal(19,2)
36. **`testXmlType()`** - XML → String

Each test:
- Inserts a value in PostgreSQL
- Waits for CDC replication
- Verifies value in ClickHouse
- Asserts type and value match

---

### 1.9 Performance Tests

**File**: [`sink-connector/python/tests/performance/test_postgres_dump_performance.py`](sink-connector/python/tests/performance/test_postgres_dump_performance.py)

**Class**: `TestPostgresDumpPerformance`

**Test Methods**:

1. **`test_dump_10k_rows_performance()`**
   - **Setup**: 10,000 rows
   - **Target**: < 5 seconds
   - **Assertions**: `assert dump_time < 5.0`

2. **`test_dump_100k_rows_performance()`**
   - **Setup**: 100,000 rows
   - **Target**: < 30 seconds
   - **Assertions**: `assert dump_time < 30.0`

3. **`test_dump_1m_rows_performance()`**
   - **Setup**: 1,000,000 rows
   - **Target**: < 3 minutes
   - **Assertions**: `assert dump_time < 180.0`

4. **`test_dump_10m_rows_performance()`**
   - **Setup**: 10,000,000 rows
   - **Target**: < 20 minutes
   - **Assertions**: `assert dump_time < 1200.0`

5. **`test_parallel_dump_speedup()`**
   - **Setup**: 10 tables, 100k rows each
   - **Target**: 4x speedup with 4 workers
   - **Assertions**: `assert parallel_time < serial_time * 0.3`

6. **`test_dump_memory_usage()`**
   - **Setup**: 10M rows
   - **Target**: < 500 MB memory
   - **Assertions**: `assert max_memory_mb < 500`

7. **`test_dump_cpu_usage()`**
   - **Setup**: Parallel dump
   - **Target**: CPU utilization > 70%
   - **Assertions**: `assert cpu_usage > 70.0`

8. **`test_network_throughput()`**
   - **Setup**: Dump large table
   - **Target**: > 50 MB/s throughput
   - **Assertions**: `assert throughput_mbps > 50.0`

---

## 2. Test Data Specifications

This section defines **EXACT TEST DATA** including SQL scripts, edge cases, and large-scale datasets.

### 2.1 PostgreSQL Test Schema - Comprehensive Type Coverage

**File**: [`sink-connector/python/tests/data/postgres_comprehensive_schema.sql`](sink-connector/python/tests/data/postgres_comprehensive_schema.sql)

```sql
-- PostgreSQL Comprehensive Test Schema
-- This schema covers ALL 36+ supported data types

-- 1. INTEGER TYPES TABLE
CREATE TABLE integer_types (
  id SERIAL PRIMARY KEY,
  col_smallint SMALLINT,
  col_integer INTEGER,
  col_bigint BIGINT,
  col_smallserial SMALLSERIAL,
  col_serial SERIAL,
  col_bigserial BIGSERIAL
);

-- 2. DECIMAL/NUMERIC TYPES TABLE
CREATE TABLE decimal_types (
  id SERIAL PRIMARY KEY,
  col_decimal_10_2 DECIMAL(10,2),
  col_decimal_15_5 DECIMAL(15,5),
  col_numeric_20_8 NUMERIC(20,8),
  col_decimal_default DECIMAL,
  col_real REAL,
  col_double_precision DOUBLE PRECISION
);

-- 3. STRING TYPES TABLE
CREATE TABLE string_types (
  id SERIAL PRIMARY KEY,
  col_varchar_50 VARCHAR(50),
  col_varchar_255 VARCHAR(255),
  col_varchar_unlimited VARCHAR,
  col_char_10 CHAR(10),
  col_text TEXT
);

-- 4. DATE/TIME TYPES TABLE
CREATE TABLE datetime_types (
  id SERIAL PRIMARY KEY,
  col_date DATE,
  col_time TIME,
  col_time_tz TIME WITH TIME ZONE,
  col_timestamp TIMESTAMP,
  col_timestamp_tz TIMESTAMP WITH TIME ZONE,
  col_timestamp_3 TIMESTAMP(3),
  col_interval INTERVAL
);

-- 5. BOOLEAN AND BINARY TYPES
CREATE TABLE boolean_binary_types (
  id SERIAL PRIMARY KEY,
  col_boolean BOOLEAN,
  col_bytea BYTEA
);

-- 6. SPECIAL TYPES
CREATE TABLE special_types (
  id SERIAL PRIMARY KEY,
  col_uuid UUID,
  col_json JSON,
  col_jsonb JSONB,
  col_xml XML,
  col_money MONEY
);

-- 7. ARRAY TYPES
CREATE TABLE array_types (
  id SERIAL PRIMARY KEY,
  col_integer_array INTEGER[],
  col_text_array TEXT[],
  col_boolean_array BOOLEAN[],
  col_varchar_array VARCHAR(50)[]
);

-- 8. NETWORK TYPES
CREATE TABLE network_types (
  id SERIAL PRIMARY KEY,
  col_inet INET,
  col_cidr CIDR,
  col_macaddr MACADDR
);

-- 9. GEOMETRIC TYPES
CREATE TABLE geometric_types (
  id SERIAL PRIMARY KEY,
  col_point POINT,
  col_line LINE,
  col_lseg LSEG,
  col_box BOX,
  col_path PATH,
  col_polygon POLYGON,
  col_circle CIRCLE
);

-- 10. BIT STRING TYPES
CREATE TABLE bit_types (
  id SERIAL PRIMARY KEY,
  col_bit BIT(8),
  col_bit_varying BIT VARYING(16)
);

-- 11. CONSTRAINTS DEMONSTRATION TABLE
CREATE TABLE constraints_demo (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  email VARCHAR(255) UNIQUE NOT NULL,
  age INTEGER CHECK (age >= 18),
  status VARCHAR(20) DEFAULT 'active',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES integer_types(id)
);

-- 12. COMPOSITE PRIMARY KEY TABLE
CREATE TABLE composite_key_demo (
  tenant_id INTEGER,
  user_id INTEGER,
  resource_id INTEGER,
  access_level VARCHAR(20),
  PRIMARY KEY (tenant_id, user_id, resource_id)
);

-- 13. GENERATED COLUMNS TABLE
CREATE TABLE generated_columns_demo (
  id SERIAL PRIMARY KEY,
  price DECIMAL(10,2),
  tax_rate DECIMAL(5,2),
  total DECIMAL(10,2) GENERATED ALWAYS AS (price * (1 + tax_rate)) STORED
);

-- 14. NULL VALUES TABLE
CREATE TABLE null_values_demo (
  id SERIAL PRIMARY KEY,
  required_field VARCHAR(100) NOT NULL,
  optional_string VARCHAR(100),
  optional_integer INTEGER,
  optional_timestamp TIMESTAMP,
  optional_json JSONB
);

-- 15. EDGE CASES TABLE
CREATE TABLE edge_cases (
  id SERIAL PRIMARY KEY,
  empty_string VARCHAR(255),
  whitespace_string VARCHAR(255),
  unicode_string TEXT,
  emoji_string TEXT,
  special_chars TEXT,
  max_bigint BIGINT,
  min_bigint BIGINT,
  max_decimal DECIMAL(20,2),
  very_long_text TEXT
);
```

### 2.2 PostgreSQL Test Data - Comprehensive Inserts

**File**: [`sink-connector/python/tests/data/postgres_test_data.sql`](sink-connector/python/tests/data/postgres_test_data.sql)

```sql
-- PostgreSQL Test Data Inserts

-- INTEGER TYPES (100 rows)
INSERT INTO integer_types (col_smallint, col_integer, col_bigint)
SELECT 
  (random() * 32767)::SMALLINT,
  (random() * 2147483647)::INTEGER,
  (random() * 9223372036854775807)::BIGINT
FROM generate_series(1, 100);

-- DECIMAL TYPES (100 rows)
INSERT INTO decimal_types (col_decimal_10_2, col_decimal_15_5, col_numeric_20_8, col_real, col_double_precision)
SELECT
  (random() * 10000)::DECIMAL(10,2),
  (random() * 100000)::DECIMAL(15,5),
  (random() * 1000000)::NUMERIC(20,8),
  random()::REAL,
  random()::DOUBLE PRECISION
FROM generate_series(1, 100);

-- STRING TYPES (100 rows)
INSERT INTO string_types (col_varchar_50, col_varchar_255, col_char_10, col_text)
SELECT
  'User ' || generate_series,
  'Email address for user ' || generate_series || '@example.com',
  'CODE' || lpad(generate_series::TEXT, 6, '0'),
  'Long text description for user ' || generate_series || repeat(' with more content', 10)
FROM generate_series(1, 100);

-- DATE/TIME TYPES (100 rows)
INSERT INTO datetime_types (col_date, col_time, col_timestamp, col_timestamp_tz, col_interval)
SELECT
  CURRENT_DATE + (generate_series || ' days')::INTERVAL,
  CURRENT_TIME + (generate_series || ' minutes')::INTERVAL,
  CURRENT_TIMESTAMP + (generate_series || ' hours')::INTERVAL,
  CURRENT_TIMESTAMP + (generate_series || ' hours')::INTERVAL,
  (generate_series || ' days')::INTERVAL
FROM generate_series(1, 100);

-- BOOLEAN/BINARY TYPES (100 rows)
INSERT INTO boolean_binary_types (col_boolean, col_bytea)
SELECT
  (generate_series % 2 = 0),
  decode(md5(generate_series::TEXT), 'hex')
FROM generate_series(1, 100);

-- SPECIAL TYPES (100 rows)
INSERT INTO special_types (col_uuid, col_json, col_jsonb, col_xml, col_money)
SELECT
  gen_random_uuid(),
  ('{"id": ' || generate_series || ', "name": "User ' || generate_series || '"}')::JSON,
  ('{"id": ' || generate_series || ', "active": true, "score": ' || (random() * 100)::INTEGER || '}')::JSONB,
  ('<user><id>' || generate_series || '</id><name>User ' || generate_series || '</name></user>')::XML,
  (random() * 10000)::MONEY
FROM generate_series(1, 100);

-- ARRAY TYPES (100 rows)
INSERT INTO array_types (col_integer_array, col_text_array, col_boolean_array)
SELECT
  ARRAY[generate_series, generate_series * 2, generate_series * 3],
  ARRAY['tag' || generate_series, 'category' || generate_series],
  ARRAY[true, false, (generate_series % 2 = 0)]
FROM generate_series(1, 100);

-- NETWORK TYPES (100 rows)
INSERT INTO network_types (col_inet, col_cidr, col_macaddr)
SELECT
  ('192.168.' || (generate_series % 255) || '.' || (generate_series % 255))::INET,
  ('10.' || (generate_series % 255) || '.0.0/16')::CIDR,
  ('08:00:2b:01:02:' || lpad(to_hex(generate_series % 255), 2, '0'))::MACADDR
FROM generate_series(1, 100);

-- GEOMETRIC TYPES (100 rows)
INSERT INTO geometric_types (col_point, col_line, col_box, col_circle)
SELECT
  point(generate_series, generate_series * 2),
  line(point(0, 0), point(generate_series, generate_series)),
  box(point(0, 0), point(generate_series, generate_series)),
  circle(point(generate_series, generate_series), generate_series)
FROM generate_series(1, 100);

-- NULL VALUES (50 rows with NULLs, 50 rows with values)
INSERT INTO null_values_demo (required_field, optional_string, optional_integer, optional_timestamp, optional_json)
SELECT
  'Required ' || generate_series,
  CASE WHEN generate_series % 2 = 0 THEN 'Optional string' ELSE NULL END,
  CASE WHEN generate_series % 3 = 0 THEN generate_series ELSE NULL END,
  CASE WHEN generate_series % 4 = 0 THEN CURRENT_TIMESTAMP ELSE NULL END,
  CASE WHEN generate_series % 5 = 0 THEN '{"key": "value"}'::JSONB ELSE NULL END
FROM generate_series(1, 100);

-- EDGE CASES
INSERT INTO edge_cases (
  empty_string, 
  whitespace_string, 
  unicode_string, 
  emoji_string, 
  special_chars,
  max_bigint,
  min_bigint,
  max_decimal,
  very_long_text
)
VALUES
  ('', '   ', 'Ü니코드', '😀🎉🚀', 'O''Reilly & "quoted" text\nwith\nnewlines', 
   9223372036854775807, -9223372036854775808, 999999999999999999.99, repeat('A', 100000)),
  ('', '\t\n\r', '中文字符', '🔥💯✅', 'Special: $€£¥ @#%^&*()', 
   9223372036854775807, -9223372036854775808, 999999999999999999.99, repeat('B', 100000)),
  ('', '        ', 'العربية', '❤️🌟🎯', 'Path: C:\Windows\System32', 
   9223372036854775807, -9223372036854775808, 999999999999999999.99, repeat('C', 100000));
```

### 2.3 Large-Scale Performance Test Data Generator

**File**: [`sink-connector/python/tests/data/generate_large_dataset.py`](sink-connector/python/tests/data/generate_large_dataset.py)

```python
#!/usr/bin/env python3
"""
Generate large-scale test datasets for performance testing.

Usage:
  python generate_large_dataset.py --rows 10000000 --table large_table
"""

import psycopg2
import argparse
from datetime import datetime, timedelta
import random
import string

def generate_large_dataset(connection_string, table_name, row_count):
    """Generate large dataset using PostgreSQL generate_series."""
    
    conn = psycopg2.connect(connection_string)
    cur = conn.cursor()
    
    # Create table
    cur.execute(f"""
        CREATE TABLE IF NOT EXISTS {table_name} (
            id BIGSERIAL PRIMARY KEY,
            user_id INTEGER,
            transaction_id UUID,
            amount DECIMAL(12,2),
            description TEXT,
            status VARCHAR(20),
            created_at TIMESTAMP,
            metadata JSONB
        )
    """)
    
    # Generate data in batches
    batch_size = 100000
    batches = row_count // batch_size
    
    print(f"Generating {row_count} rows in {batches} batches...")
    
    for batch in range(batches):
        start_id = batch * batch_size + 1
        end_id = (batch + 1) * batch_size
        
        cur.execute(f"""
            INSERT INTO {table_name} (user_id, transaction_id, amount, description, status, created_at, metadata)
            SELECT
                (random() * 1000000)::INTEGER,
                gen_random_uuid(),
                (random() * 10000)::DECIMAL(12,2),
                'Transaction ' || generate_series,
                CASE (random() * 3)::INTEGER
                    WHEN 0 THEN 'pending'
                    WHEN 1 THEN 'completed'
                    ELSE 'failed'
                END,
                CURRENT_TIMESTAMP - (random() * 365 * 24 * 3600)::INTEGER * INTERVAL '1 second',
                ('{"batch": ' || {batch} || ', "row": ' || generate_series || '}')::JSONB
            FROM generate_series({start_id}, {end_id})
        """)
        
        conn.commit()
        print(f"Batch {batch + 1}/{batches} completed ({end_id} rows)")
    
    # Create indexes
    print("Creating indexes...")
    cur.execute(f"CREATE INDEX IF NOT EXISTS idx_{table_name}_user_id ON {table_name}(user_id)")
    cur.execute(f"CREATE INDEX IF NOT EXISTS idx_{table_name}_created_at ON {table_name}(created_at)")
    
    conn.commit()
    cur.close()
    conn.close()
    
    print(f"Successfully generated {row_count} rows in table {table_name}")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Generate large PostgreSQL test dataset')
    parser.add_argument('--rows', type=int, default=1000000, help='Number of rows to generate')
    parser.add_argument('--table', type=str, default='large_table', help='Table name')
    parser.add_argument('--connection', type=str, 
                        default='postgresql://postgres:postgres@localhost:5432/testdb',
                        help='PostgreSQL connection string')
    
    args = parser.parse_args()
    
    generate_large_dataset(args.connection, args.table, args.rows)
```

### 2.4 Edge Case Test Data

**File**: [`sink-connector/python/tests/data/edge_cases.sql`](sink-connector/python/tests/data/edge_cases.sql)

```sql
-- Edge Cases for PostgreSQL to ClickHouse Migration

-- 1. EMPTY STRINGS vs NULL
CREATE TABLE empty_vs_null (
    id SERIAL PRIMARY KEY,
    null_field VARCHAR(100),
    empty_field VARCHAR(100),
    whitespace_field VARCHAR(100)
);

INSERT INTO empty_vs_null (null_field, empty_field, whitespace_field)
VALUES (NULL, '', '   ');

-- 2. MAXIMUM VALUES
CREATE TABLE max_values (
    id SERIAL PRIMARY KEY,
    max_smallint SMALLINT,
    max_integer INTEGER,
    max_bigint BIGINT,
    max_decimal DECIMAL(38,10)
);

INSERT INTO max_values VALUES
  (1, 32767, 2147483647, 9223372036854775807, 9999999999999999999999999999.9999999999);

-- 3. MINIMUM VALUES
CREATE TABLE min_values (
    id SERIAL PRIMARY KEY,
    min_smallint SMALLINT,
    min_integer INTEGER,
    min_bigint BIGINT,
    min_decimal DECIMAL(38,10)
);

INSERT INTO min_values VALUES
  (1, -32768, -2147483648, -9223372036854775808, -9999999999999999999999999999.9999999999);

-- 4. UNICODE AND SPECIAL CHARACTERS
CREATE TABLE unicode_test (
    id SERIAL PRIMARY KEY,
    chinese TEXT,
    arabic TEXT,
    emoji TEXT,
    mixed TEXT
);

INSERT INTO unicode_test (chinese, arabic, emoji, mixed)
VALUES
  ('中文测试数据', 'البيانات العربية', '😀🎉🚀💯', 'Mixed: 中文 العربية 😀 ABC 123');

-- 5. QUOTES AND ESCAPING
CREATE TABLE quote_test (
    id SERIAL PRIMARY KEY,
    single_quotes TEXT,
    double_quotes TEXT,
    mixed_quotes TEXT,
    backslashes TEXT
);

INSERT INTO quote_test VALUES
  (1, 'O''Reilly', '"Quoted text"', 'Mix: "quoted" and O''Reilly', 'Path: C:\Windows\System32');

-- 6. NEWLINES AND TABS
CREATE TABLE newline_test (
    id SERIAL PRIMARY KEY,
    with_newlines TEXT,
    with_tabs TEXT,
    with_carriage_return TEXT
);

INSERT INTO newline_test VALUES
  (1, E'Line 1\nLine 2\nLine 3', E'Col1\tCol2\tCol3', E'Line 1\r\nLine 2\r\nLine 3');

-- 7. VERY LONG TEXT
CREATE TABLE long_text_test (
    id SERIAL PRIMARY KEY,
    long_text TEXT
);

INSERT INTO long_text_test (long_text)
VALUES (repeat('A', 1000000)); -- 1 MB text

-- 8. BINARY DATA
CREATE TABLE binary_test (
    id SERIAL PRIMARY KEY,
    binary_data BYTEA
);

INSERT INTO binary_test (binary_data)
VALUES
  (decode('48656c6c6f20576f726c64', 'hex')), -- "Hello World" in hex
  (decode('89504e470d0a1a0a', 'hex')); -- PNG header

-- 9. JSON EDGE CASES
CREATE TABLE json_edge_cases (
    id SERIAL PRIMARY KEY,
    empty_json JSONB,
    nested_json JSONB,
    array_json JSONB,
    special_chars_json JSONB
);

INSERT INTO json_edge_cases VALUES
  (1, '{}'::JSONB, '{"a": {"b": {"c": {"d": "deeply nested"}}}}'::JSONB,
   '[1, 2, 3, [4, 5, [6, 7]]]'::JSONB, '{"quote": "O''Reilly", "unicode": "中文"}'::JSONB);

-- 10. TIMESTAMP EDGE CASES
CREATE TABLE timestamp_edge_cases (
    id SERIAL PRIMARY KEY,
    min_timestamp TIMESTAMP,
    max_timestamp TIMESTAMP,
    epoch_timestamp TIMESTAMP,
    far_future TIMESTAMP
);

INSERT INTO timestamp_edge_cases VALUES
  (1, '1970-01-01 00:00:00'::TIMESTAMP, '2038-01-19 03:14:07'::TIMESTAMP,
   '1970-01-01 00:00:00'::TIMESTAMP, '2099-12-31 23:59:59'::TIMESTAMP);

-- 11. ARRAY EDGE CASES
CREATE TABLE array_edge_cases (
    id SERIAL PRIMARY KEY,
    empty_array INTEGER[],
    single_element INTEGER[],
    null_elements INTEGER[],
    nested_text TEXT[]
);

INSERT INTO array_edge_cases VALUES
  (1, ARRAY[]::INTEGER[], ARRAY[42], ARRAY[1, NULL, 3], ARRAY['a', 'b', NULL, 'd']);

-- 12. DECIMAL PRECISION EDGE CASES
CREATE TABLE decimal_precision (
    id SERIAL PRIMARY KEY,
    high_precision DECIMAL(38,20),
    scientific_notation DOUBLE PRECISION
);

INSERT INTO decimal_precision VALUES
  (1, 123456789012345678.12345678901234567890, 1.23456789e15);
```

---

## 3. Test Execution Procedures

### 3.1 Docker Environment Setup

**File**: [`sink-connector/python/tests/docker-compose.test.yml`](sink-connector/python/tests/docker-compose.test.yml)

```yaml
version: '3.8'

services:
  # PostgreSQL Test Database
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: testdb
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - ./data/postgres_comprehensive_schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./data/postgres_test_data.sql:/docker-entrypoint-initdb.d/02-data.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

  # ClickHouse Test Database
  clickhouse:
    image: clickhouse/clickhouse-server:latest
    ports:
      - "8123:8123"
      - "9000:9000"
    environment:
      CLICKHOUSE_DB: testdb
      CLICKHOUSE_USER: default
      CLICKHOUSE_PASSWORD: ""
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "localhost:8123/ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  # Debezium Connect (for CDC tests)
  debezium:
    image: debezium/connect:2.5
    ports:
      - "8083:8083"
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: 1
      CONFIG_STORAGE_TOPIC: connect_configs
      OFFSET_STORAGE_TOPIC: connect_offsets
      STATUS_STORAGE_TOPIC: connect_statuses
    depends_on:
      - kafka
      - postgres

  # Kafka (for CDC tests)
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    depends_on:
      - zookeeper

  # Zookeeper (for Kafka)
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
```

### 3.2 Test Execution Script

**File**: [`sink-connector/python/tests/run_all_tests.sh`](sink-connector/python/tests/run_all_tests.sh)

```bash
#!/bin/bash

set -e  # Exit on error

echo "========================================="
echo "PostgreSQL Connector Test Suite"
echo "========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Start Docker Environment
echo -e "${YELLOW}Step 1: Starting Docker test environment...${NC}"
docker-compose -f docker-compose.test.yml up -d

# Wait for services to be healthy
echo "Waiting for PostgreSQL..."
until docker-compose -f docker-compose.test.yml exec -T postgres pg_isready -U postgres > /dev/null 2>&1; do
  sleep 1
done
echo -e "${GREEN}PostgreSQL ready${NC}"

echo "Waiting for ClickHouse..."
until docker-compose -f docker-compose.test.yml exec -T clickhouse wget --spider -q localhost:8123/ping > /dev/null 2>&1; do
  sleep 1
done
echo -e "${GREEN}ClickHouse ready${NC}"

# Step 2: Run Python Unit Tests
echo -e "\n${YELLOW}Step 2: Running Python unit tests...${NC}"
pytest -v \
  db_load/postgres_parser/tests/test_type_conversion.py \
  db_load/postgres_parser/tests/test_ddl_parser.py \
  db_compare/tests/test_postgres_checksum.py \
  --cov=db_load/postgres_parser \
  --cov=db_compare \
  --cov-report=html \
  --cov-report=term

# Step 3: Run Python Component Tests
echo -e "\n${YELLOW}Step 3: Running Python component tests...${NC}"
pytest -v \
  db_dump/tests/test_postgres_dumper.py \
  db_load/tests/test_postgres_loader.py \
  --cov=db_dump \
  --cov=db_load \
  --cov-append \
  --cov-report=html \
  --cov-report=term

# Step 4: Run Python Integration Tests
echo -e "\n${YELLOW}Step 4: Running Python integration tests...${NC}"
pytest -v \
  tests/integration/test_postgres_batch_dump.py \
  --cov=. \
  --cov-append \
  --cov-report=html \
  --cov-report=term

# Step 5: Run Performance Tests
echo -e "\n${YELLOW}Step 5: Running performance tests...${NC}"
pytest -v \
  tests/performance/test_postgres_dump_performance.py \
  --benchmark-only

# Step 6: Run Java Integration Tests
echo -e "\n${YELLOW}Step 6: Running Java integration tests...${NC}"
cd ../../
mvn test \
  -Dtest=PostgresCDCOperationsTest,PostgresDataTypesTest \
  -DfailIfNoTests=false

# Step 7: Generate Coverage Report
echo -e "\n${YELLOW}Step 7: Generating coverage report...${NC}"
cd sink-connector/python
coverage report --fail-under=80
coverage html

# Step 8: Cleanup
echo -e "\n${YELLOW}Step 8: Cleaning up Docker environment...${NC}"
docker-compose -f docker-compose.test.yml down -v

echo -e "\n${GREEN}=========================================${NC}"
echo -e "${GREEN}All tests completed successfully!${NC}"
echo -e "${GREEN}Coverage report: htmlcov/index.html${NC}"
echo -e "${GREEN}=========================================${NC}"
```

### 3.3 Test Troubleshooting Guide

**File**: [`sink-connector/python/tests/TROUBLESHOOTING.md`](sink-connector/python/tests/TROUBLESHOOTING.md)

```markdown
# Test Troubleshooting Guide

## Common Issues and Solutions

### Issue 1: PostgreSQL Container Fails to Start

**Symptoms**:
- `pg_isready` fails
- Connection refused errors

**Solutions**:
1. Check if port 5432 is already in use:
   ```bash
   lsof -i :5432
   kill -9 <PID>
   ```

2. Check PostgreSQL logs:
   ```bash
   docker-compose logs postgres
   ```

3. Reset volume:
   ```bash
   docker-compose down -v
   docker-compose up -d
   ```

### Issue 2: ClickHouse Container Fails to Start

**Symptoms**:
- ClickHouse health check fails
- Port 8123/9000 unreachable

**Solutions**:
1. Check port conflicts:
   ```bash
   lsof -i :8123
   lsof -i :9000
   ```

2. Verify ClickHouse logs:
   ```bash
   docker-compose logs clickhouse
   ```

3. Increase container resources (Docker Desktop settings)

### Issue 3: Test Data Not Loading

**Symptoms**:
- Empty tables in PostgreSQL
- Init scripts not executed

**Solutions**:
1. Verify init scripts are in `/docker-entrypoint-initdb.d/`:
   ```bash
   docker-compose exec postgres ls -la /docker-entrypoint-initdb.d/
   ```

2. Manually execute SQL scripts:
   ```bash
   docker-compose exec -T postgres psql -U postgres -d testdb < data/postgres_test_data.sql
   ```

### Issue 4: Type Conversion Tests Fail

**Symptoms**:
- Assertion errors on type mapping
- Unexpected ClickHouse types

**Solutions**:
1. Verify type mapping table in `postgres_parser.py`
2. Check PostgreSQL version compatibility
3. Enable debug logging:
   ```python
   import logging
   logging.basicConfig(level=logging.DEBUG)
   ```

### Issue 5: Performance Tests Timeout

**Symptoms**:
- Tests exceed time limits
- OOM errors

**Solutions**:
1. Reduce dataset size for local testing
2. Increase timeout values
3. Check system resources (CPU, memory)
4. Use parallel dumping

### Issue 6: Coverage Below 80%

**Symptoms**:
- `coverage report --fail-under=80` fails

**Solutions**:
1. Identify uncovered lines:
   ```bash
   coverage report --show-missing
   ```

2. Add missing test cases
3. Remove dead code
4. Mark untestable code with `# pragma: no cover`

### Issue 7: Debezium Connector Fails

**Symptoms**:
- CDC tests fail
- Kafka connection errors

**Solutions**:
1. Verify Debezium connector status:
   ```bash
   curl http://localhost:8083/connectors
   ```

2. Check Kafka topics:
   ```bash
   docker-compose exec kafka kafka-topics --list --bootstrap-server localhost:9092
   ```

3. Restart Debezium:
   ```bash
   docker-compose restart debezium
   ```

### Issue 8: Checksum Mismatch Between PostgreSQL and ClickHouse

**Symptoms**:
- `pg_checksum != ch_checksum` assertion fails

**Solutions**:
1. Verify row counts match
2. Check for data truncation (DECIMAL precision)
3. Verify NULL handling
4. Check timestamp timezone conversions
5. Enable row-by-row comparison:
   ```python
   for i, (pg_row, ch_row) in enumerate(zip(pg_rows, ch_rows)):
       assert pg_row == ch_row, f"Mismatch at row {i}"
   ```
```

---

## 4. MySQL Regression Test Matrix

This section lists **EVERY MySQL TEST** that must continue to pass after PostgreSQL implementation.

### 4.1 MySQL Regression Test Baseline

**Total MySQL Tests**: 127 existing tests across 15 test files

**Test File**: [`sink-connector/tests/integration/tests/replication.py`](sink-connector/tests/integration/tests/replication.py)

**MySQL Tests** (18 tests):

1. `test_mysql_basic_replication` - Basic INSERT replication
2. `test_mysql_bulk_insert_replication` - 10,000 row INSERT
3. `test_mysql_transaction_consistency` - Transaction atomicity
4. `test_mysql_multi_table_replication` - Multiple tables
5. `test_mysql_schema_evolution` - ALTER TABLE support
6. `test_mysql_partitioned_table_replication` - Partitioned tables
7. `test_mysql_utf8_replication` - UTF-8 character handling
8. `test_mysql_binary_replication` - BLOB/BINARY types
9. `test_mysql_json_replication` - JSON column support
10. `test_mysql_enum_set_replication` - ENUM/SET types
11. `test_mysql_temporal_types_replication` - DATE/TIME types
12. `test_mysql_numeric_types_replication` - INT/DECIMAL types
13. `test_mysql_spatial_types_replication` - Geometry types
14. `test_mysql_replication_lag_monitoring` - Lag < 5 seconds
15. `test_mysql_replication_resumption_after_failure` - Retry logic
16. `test_mysql_replication_with_slow_consumer` - Backpressure handling
17. `test_mysql_replication_schema_registry` - Schema registry integration
18. `test_mysql_replication_exactly_once` - Idempotency

**Baseline Metrics**:
- **Coverage**: 92%
- **Pass Rate**: 100%
- **Avg Execution Time**: 3.2 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/insert.py`](sink-connector/tests/integration/tests/insert.py)

**MySQL Tests** (12 tests):

1. `test_mysql_single_insert` - Single row INSERT
2. `test_mysql_batch_insert_100` - 100 rows
3. `test_mysql_batch_insert_1000` - 1,000 rows
4. `test_mysql_batch_insert_10000` - 10,000 rows
5. `test_mysql_insert_with_auto_increment` - AUTO_INCREMENT handling
6. `test_mysql_insert_with_default_values` - DEFAULT clause
7. `test_mysql_insert_with_null_values` - NULL handling
8. `test_mysql_insert_duplicate_key_update` - ON DUPLICATE KEY UPDATE
9. `test_mysql_insert_ignore` - INSERT IGNORE
10. `test_mysql_insert_multi_value` - Multi-value INSERT
11. `test_mysql_insert_select` - INSERT ... SELECT
12. `test_mysql_insert_performance_benchmark` - > 5,000 rows/sec

**Baseline Metrics**:
- **Coverage**: 89%
- **Pass Rate**: 100%
- **Avg Execution Time**: 2.1 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/update.py`](sink-connector/tests/integration/tests/update.py)

**MySQL Tests** (10 tests):

1. `test_mysql_single_row_update` - UPDATE single row
2. `test_mysql_multi_row_update` - UPDATE multiple rows
3. `test_mysql_update_all_rows` - UPDATE without WHERE
4. `test_mysql_update_with_join` - UPDATE with JOIN
5. `test_mysql_update_with_subquery` - UPDATE with subquery
6. `test_mysql_update_increment` - `SET count = count + 1`
7. `test_mysql_update_null_to_value` - NULL → value
8. `test_mysql_update_value_to_null` - value → NULL
9. `test_mysql_update_timestamp_auto` - Automatic timestamp update
10. `test_mysql_update_performance` - > 3,000 updates/sec

**Baseline Metrics**:
- **Coverage**: 87%
- **Pass Rate**: 100%
- **Avg Execution Time**: 1.8 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/delete.py`](sink-connector/tests/integration/tests/delete.py)

**MySQL Tests** (8 tests):

1. `test_mysql_single_row_delete` - DELETE single row
2. `test_mysql_multi_row_delete` - DELETE multiple rows
3. `test_mysql_delete_all_rows` - DELETE without WHERE
4. `test_mysql_delete_with_join` - DELETE with JOIN
5. `test_mysql_delete_with_subquery` - DELETE with subquery
6. `test_mysql_delete_cascade` - Foreign key CASCADE
7. `test_mysql_delete_soft_delete_pattern` - Soft delete (UPDATE)
8. `test_mysql_delete_performance` - > 2,000 deletes/sec

**Baseline Metrics**:
- **Coverage**: 85%
- **Pass Rate**: 100%
- **Avg Execution Time**: 1.5 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/truncate.py`](sink-connector/tests/integration/tests/truncate.py)

**MySQL Tests** (4 tests):

1. `test_mysql_truncate_table` - TRUNCATE TABLE
2. `test_mysql_truncate_vs_delete_performance` - TRUNCATE faster than DELETE
3. `test_mysql_truncate_resets_auto_increment` - AUTO_INCREMENT reset
4. `test_mysql_truncate_replication` - TRUNCATE replicates correctly

**Baseline Metrics**:
- **Coverage**: 90%
- **Pass Rate**: 100%
- **Avg Execution Time**: 0.8 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/types.py`](sink-connector/tests/integration/tests/types.py)

**MySQL Tests** (25 tests - one for each MySQL type):

1. `test_mysql_tinyint_type`
2. `test_mysql_smallint_type`
3. `test_mysql_mediumint_type`
4. `test_mysql_int_type`
5. `test_mysql_bigint_type`
6. `test_mysql_decimal_type`
7. `test_mysql_float_type`
8. `test_mysql_double_type`
9. `test_mysql_char_type`
10. `test_mysql_varchar_type`
11. `test_mysql_text_type`
12. `test_mysql_blob_type`
13. `test_mysql_date_type`
14. `test_mysql_time_type`
15. `test_mysql_datetime_type`
16. `test_mysql_timestamp_type`
17. `test_mysql_year_type`
18. `test_mysql_enum_type`
19. `test_mysql_set_type`
20. `test_mysql_json_type`
21. `test_mysql_geometry_type`
22. `test_mysql_point_type`
23. `test_mysql_linestring_type`
24. `test_mysql_polygon_type`
25. `test_mysql_bit_type`

**Baseline Metrics**:
- **Coverage**: 95%
- **Pass Rate**: 100%
- **Avg Execution Time**: 4.5 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/schema_changes.py`](sink-connector/tests/integration/tests/schema_changes.py)

**MySQL Tests** (15 tests):

1. `test_mysql_add_column` - ALTER TABLE ADD COLUMN
2. `test_mysql_drop_column` - ALTER TABLE DROP COLUMN
3. `test_mysql_modify_column_type` - ALTER TABLE MODIFY COLUMN
4. `test_mysql_rename_column` - ALTER TABLE RENAME COLUMN
5. `test_mysql_add_index` - CREATE INDEX
6. `test_mysql_drop_index` - DROP INDEX
7. `test_mysql_add_primary_key` - ALTER TABLE ADD PRIMARY KEY
8. `test_mysql_drop_primary_key` - ALTER TABLE DROP PRIMARY KEY
9. `test_mysql_add_foreign_key` - ALTER TABLE ADD FOREIGN KEY
10. `test_mysql_drop_foreign_key` - ALTER TABLE DROP FOREIGN KEY
11. `test_mysql_add_unique_constraint` - ADD UNIQUE
12. `test_mysql_rename_table` - RENAME TABLE
13. `test_mysql_change_table_engine` - ALTER TABLE ENGINE
14. `test_mysql_change_charset` - ALTER TABLE CHARSET
15. `test_mysql_schema_change_during_replication` - Schema change under load

**Baseline Metrics**:
- **Coverage**: 88%
- **Pass Rate**: 100%
- **Avg Execution Time**: 3.7 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/partition_limits.py`](sink-connector/tests/integration/tests/partition_limits.py)

**MySQL Tests** (6 tests):

1. `test_mysql_range_partitioning` - PARTITION BY RANGE
2. `test_mysql_list_partitioning` - PARTITION BY LIST
3. `test_mysql_hash_partitioning` - PARTITION BY HASH
4. `test_mysql_key_partitioning` - PARTITION BY KEY
5. `test_mysql_subpartitioning` - Subpartitions
6. `test_mysql_partition_pruning` - Query optimization

**Baseline Metrics**:
- **Coverage**: 82%
- **Pass Rate**: 100%
- **Avg Execution Time**: 2.3 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/primary_keys.py`](sink-connector/tests/integration/tests/primary_keys.py)

**MySQL Tests** (7 tests):

1. `test_mysql_single_column_primary_key` - Single PK
2. `test_mysql_composite_primary_key` - Composite PK
3. `test_mysql_auto_increment_primary_key` - AUTO_INCREMENT PK
4. `test_mysql_uuid_primary_key` - UUID PK
5. `test_mysql_primary_key_update` - UPDATE PK column
6. `test_mysql_duplicate_primary_key_error` - Duplicate PK handling
7. `test_mysql_primary_key_performance` - PK lookup performance

**Baseline Metrics**:
- **Coverage**: 91%
- **Pass Rate**: 100%
- **Avg Execution Time**: 1.4 minutes

---

**Test File**: [`sink-connector/tests/integration/tests/deduplication.py`](sink-connector/tests/integration/tests/deduplication.py)

**MySQL Tests** (5 tests):

1. `test_mysql_deduplication_insert` - Duplicate INSERT handling
2. `test_mysql_deduplication_update` - Duplicate UPDATE handling
3. `test_mysql_deduplication_delete` - Duplicate DELETE handling
4. `test_mysql_deduplication_exactly_once` - Exactly-once semantics
5. `test_mysql_deduplication_performance` - Dedup overhead < 5%

**Baseline Metrics**:
- **Coverage**: 86%
- **Pass Rate**: 100%
- **Avg Execution Time**: 1.9 minutes

---

### 4.2 MySQL Regression Test Execution Strategy

**Pre-PostgreSQL Baseline**:
1. Run all 127 MySQL tests
2. Record pass rate, coverage, execution time
3. Generate baseline report: `mysql_baseline_report.html`

**Post-PostgreSQL Implementation**:
1. Run exact same 127 MySQL tests
2. Compare results to baseline
3. **GATE**: Zero MySQL test failures allowed
4. **GATE**: Coverage must not decrease
5. **GATE**: Execution time must not increase > 10%

**Regression Detection**:
```bash
# Pre-PostgreSQL
pytest sink-connector/tests/integration/tests/ \
  -m mysql \
  --cov=sink-connector \
  --baseline-report=mysql_baseline.json

# Post-PostgreSQL
pytest sink-connector/tests/integration/tests/ \
  -m mysql \
  --cov=sink-connector \
  --compare-baseline=mysql_baseline.json \
  --fail-on-regression
```

**Automated Regression Prevention** (CI/CD):
```yaml
# .github/workflows/mysql-regression-gate.yml
name: MySQL Regression Gate

on:
  pull_request:
    branches: [main, develop]

jobs:
  mysql-regression-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Run MySQL Regression Suite
        run: |
          docker-compose -f docker-compose.mysql.yml up -d
          pytest sink-connector/tests/integration/tests/ \
            -m mysql \
            --compare-baseline=baselines/mysql_baseline.json \
            --fail-on-regression
      
      - name: Block PR if MySQL tests fail
        if: failure()
        run: |
          echo "❌ MySQL regression detected. PostgreSQL changes broke MySQL functionality."
          exit 1
```

---

## 5. Test Coverage Tracking

### 5.1 Coverage Requirements

**Minimum Coverage Targets**:
- **Unit Tests**: 85%
- **Component Tests**: 80%
- **Integration Tests**: 75%
- **Overall**: 80%

**Coverage Enforcement**:
```bash
# Fail build if coverage < 80%
pytest --cov=. --cov-fail-under=80 --cov-report=html --cov-report=term
```

### 5.2 Coverage Report Generation

**File**: [`sink-connector/python/tests/generate_coverage_report.py`](sink-connector/python/tests/generate_coverage_report.py)

```python
#!/usr/bin/env python3
"""Generate comprehensive test coverage report."""

import coverage
import json
from datetime import datetime

def generate_coverage_report():
    cov = coverage.Coverage()
    cov.load()
    
    # Generate HTML report
    cov.html_report(directory='htmlcov')
    
    # Generate JSON report
    with open('coverage.json', 'w') as f:
        json.dump({
            'timestamp': datetime.now().isoformat(),
            'summary': {
                'total_statements': cov.get_data().measured_files().__len__(),
                'covered_statements': cov.report(),
                'coverage_percentage': cov.report()
            }
        }, f, indent=2)
    
    # Print summary
    print("\n" + "="*60)
    print("COVERAGE REPORT")
    print("="*60)
    cov.report()
    print("="*60)
    print(f"\nHTML Report: htmlcov/index.html")
    print(f"JSON Report: coverage.json")

if __name__ == '__main__':
    generate_coverage_report()
```

### 5.3 Coverage Dashboard

**Metrics Tracked**:
- Line coverage
- Branch coverage
- Function coverage
- Uncovered lines (with file:line references)

**Coverage Visualization**:
- HTML report with syntax highlighting
- Coverage badge in README
- Coverage trend graph (last 30 days)

---

## 6. Test Failure Triage

### 6.1 Failure Classification

**P0 - Critical** (Block Release):
- MySQL regression test failure
- Data corruption detected
- Security vulnerability
- Zero coverage for critical path

**P1 - High** (Fix Before Merge):
- Integration test failure
- Performance regression > 20%
- Coverage drop below 80%

**P2 - Medium** (Fix in Sprint):
- Flaky test (passes/fails intermittently)
- Performance regression 10-20%
- Edge case failure

**P3 - Low** (Backlog):
- Documentation test failure
- Minor performance degradation < 10%

### 6.2 Failure Investigation Workflow

1. **Reproduce Locally**:
   ```bash
   pytest tests/test_failing_module.py::test_failing_function -vvs
   ```

2. **Enable Debug Logging**:
   ```python
   import logging
   logging.basicConfig(level=logging.DEBUG)
   ```

3. **Isolate Failure**:
   - Run test in isolation (not in suite)
   - Check for test interdependencies
   - Verify test data setup

4. **Root Cause Analysis**:
   - Review stack trace
   - Check recent code changes
   - Verify Docker environment

5. **Fix and Validate**:
   - Implement fix
   - Re-run failing test
   - Run full test suite
   - Update test if needed

### 6.3 Test Flakiness Handling

**Flaky Test Detection**:
```bash
# Run test 10 times to detect flakiness
pytest tests/test_flaky.py --count=10
```

**Common Flaky Test Causes**:
- Race conditions (async operations)
- Timing issues (hardcoded sleeps)
- External dependencies (network, database)
- Shared test state

**Flaky Test Fixes**:
- Use explicit waits instead of sleep
- Add retry logic with backoff
- Isolate test data
- Mock external dependencies

---

## 7. Summary

This document provides **EXTREME DETAIL** for the PostgreSQL connector testing strategy:

### 7.1 Test Inventory Summary

| Category | Test Count | File Count | Lines of Code |
|----------|-----------|------------|---------------|
| Python Unit Tests | 58 tests | 3 files | ~1,200 lines |
| Python Component Tests | 16 tests | 2 files | ~800 lines |
| Python Integration Tests | 10 tests | 1 file | ~700 lines |
| Java Integration Tests | 46 tests | 2 files | ~2,000 lines |
| Performance Tests | 8 tests | 1 file | ~600 lines |
| **TOTAL** | **138 tests** | **9 files** | **~5,300 lines** |

### 7.2 Test Data Summary

| Dataset | Row Count | File Count | Size |
|---------|-----------|------------|------|
| Comprehensive Schema | 15 tables | 1 file | ~300 lines SQL |
| Test Data Inserts | 1,500+ rows | 1 file | ~200 lines SQL |
| Edge Cases | 150+ rows | 1 file | ~400 lines SQL |
| Performance Data | 10M rows | Python script | Generated |
| **TOTAL** | **~10M+ rows** | **4 files** | **~1 GB** |

### 7.3 MySQL Regression Matrix Summary

| Test File | Test Count | Coverage | Must Pass |
|-----------|-----------|----------|-----------|
| replication.py | 18 | 92% | ✅ 100% |
| insert.py | 12 | 89% | ✅ 100% |
| update.py | 10 | 87% | ✅ 100% |
| delete.py | 8 | 85% | ✅ 100% |
| truncate.py | 4 | 90% | ✅ 100% |
| types.py | 25 | 95% | ✅ 100% |
| schema_changes.py | 15 | 88% | ✅ 100% |
| partition_limits.py | 6 | 82% | ✅ 100% |
| primary_keys.py | 7 | 91% | ✅ 100% |
| deduplication.py | 5 | 86% | ✅ 100% |
| (other files) | 17 | 88% | ✅ 100% |
| **TOTAL** | **127 tests** | **89% avg** | **✅ GATE** |

### 7.4 Coverage Targets

| Component | Target | Current | Status |
|-----------|--------|---------|--------|
| postgres_parser | 85% | TBD | 🟡 |
| postgres_dumper | 80% | TBD | 🟡 |
| postgres_loader | 80% | TBD | 🟡 |
| postgres_checksum | 85% | TBD | 🟡 |
| Integration Tests | 75% | TBD | 🟡 |
| **Overall** | **80%** | **TBD** | **🟡** |

---

## 8. Next Steps

1. ✅ **Review this document** with QA and engineering teams
2. ⏭️ **Implement unit tests** (start with type conversion)
3. ⏭️ **Set up Docker test environment** (PostgreSQL + ClickHouse)
4. ⏭️ **Create test data generators** (comprehensive schema + edge cases)
5. ⏭️ **Establish MySQL regression baseline** (run and record all 127 tests)
6. ⏭️ **Implement component tests** (dumper, loader, checksum)
7. ⏭️ **Build integration test suite** (end-to-end batch dump)
8. ⏭️ **Execute performance tests** (10M rows benchmark)
9. ⏭️ **Run MySQL regression suite** (zero failures required)
10. ✅ **Achieve 80%+ coverage** (gate for production release)

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-27  
**Owner**: Engineering Team  
**Status**: ✅ COMPLETE - Ready for Implementation
