# PostgreSQL Testing Strategy

## Executive Summary

This document outlines a comprehensive testing strategy for PostgreSQL batch dump functionality and complete verification of all PostgreSQL operations in the clickhouse-sink-connector project. The strategy covers batch dump/load operations, CDC replication verification, data type coverage, and integration testing.

**Current State**: PostgreSQL CDC is operational; batch dump tools and comprehensive testing do not exist.

**Goal**: Achieve 100% test coverage for all PostgreSQL operations (batch dump, CDC replication, data types, DDL changes).

---

## 1. Testing Pyramid

```
                    ┌─────────────┐
                    │   Manual    │
                    │   Testing   │
                    └──────┬──────┘
                 ┌─────────┴─────────┐
                 │   Integration     │
                 │   Tests (Docker)  │
                 └─────────┬─────────┘
            ┌─────────────┴─────────────┐
            │   Component Tests         │
            │   (Parser, Converter)     │
            └─────────────┬─────────────┘
       ┌──────────────────┴──────────────────┐
       │      Unit Tests                     │
       │      (Type Mapping, Utilities)      │
       └─────────────────────────────────────┘
```

### 1.1 Test Distribution

| Test Level | Count Target | Coverage Focus |
|------------|--------------|----------------|
| Unit Tests | 100+ | Type conversion, parsing logic, utilities |
| Component Tests | 30+ | Parser, dumper, loader modules |
| Integration Tests | 20+ | End-to-end workflows with Docker |
| Manual Tests | 5+ | Performance benchmarks, edge cases |

---

## 2. Unit Testing

### 2.1 PostgreSQL Type Conversion Tests

**Location**: `sink-connector/python/db_load/postgres_parser/tests/test_type_conversion.py`

**Purpose**: Verify all PostgreSQL to ClickHouse type mappings.

```python
# File: sink-connector/python/db_load/postgres_parser/tests/test_type_conversion.py

import unittest
from db_load.postgres_parser.postgres_parser import map_postgres_type_to_clickhouse

class TestPostgresTypeConversion(unittest.TestCase):
    
    def test_integer_types(self):
        """Test integer type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('smallint', False),
            'Int16'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('integer', False),
            'Int32'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('bigint', False),
            'Int64'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('serial', False),
            'Int32'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('bigserial', False),
            'Int64'
        )
    
    def test_numeric_types(self):
        """Test numeric/decimal type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('numeric', False, precision=10, scale=2),
            'Decimal(10, 2)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('decimal(21,5)', False, precision=21, scale=5),
            'Decimal(21, 5)'
        )
        # Test default precision
        self.assertEqual(
            map_postgres_type_to_clickhouse('numeric', False),
            'Decimal(38, 9)'
        )
    
    def test_floating_point_types(self):
        """Test floating point conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('real', False),
            'Float32'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('double precision', False),
            'Float64'
        )
    
    def test_string_types(self):
        """Test string type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('varchar(255)', False),
            'String'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('text', False),
            'String'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('char(10)', False),
            'FixedString(10)'
        )
    
    def test_date_time_types(self):
        """Test date/time type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('date', False),
            'Date32'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('timestamp', False),
            'DateTime64(6)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('timestamp with time zone', False, datetime_timezone='UTC'),
            "DateTime64(6, 'UTC')"
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('time', False),
            'String'
        )
    
    def test_boolean_type(self):
        """Test boolean conversion"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('boolean', False),
            'Bool'
        )
    
    def test_uuid_type(self):
        """Test UUID conversion"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('uuid', False),
            'UUID'
        )
    
    def test_json_types(self):
        """Test JSON type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('json', False),
            'String'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('jsonb', False),
            'String'
        )
    
    def test_array_types(self):
        """Test array type conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('integer[]', False),
            'Array(Int32)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('text[]', False),
            'Array(String)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('uuid[]', False),
            'Array(UUID)'
        )
    
    def test_network_types(self):
        """Test network address conversions"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('inet', False),
            'IPv4'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('cidr', False),
            'IPv4'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('macaddr', False),
            'String'
        )
    
    def test_nullable_handling(self):
        """Test Nullable wrapper application"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('integer', True),
            'Nullable(Int32)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('text', True),
            'Nullable(String)'
        )
        # Non-nullable
        self.assertEqual(
            map_postgres_type_to_clickhouse('integer', False),
            'Int32'
        )
    
    def test_special_types(self):
        """Test special PostgreSQL types"""
        self.assertEqual(
            map_postgres_type_to_clickhouse('hstore', False),
            'Map(String, String)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('point', False),
            'Tuple(Float64, Float64)'
        )
        self.assertEqual(
            map_postgres_type_to_clickhouse('bytea', False),
            'String'
        )

if __name__ == '__main__':
    unittest.main()
```

### 2.2 DDL Parser Tests

**Location**: `sink-connector/python/db_load/postgres_parser/tests/test_ddl_parser.py`

```python
# File: sink-connector/python/db_load/postgres_parser/tests/test_ddl_parser.py

import unittest
from db_load.postgres_parser.postgres_parser import parse_create_table, convert_to_clickhouse_table

class TestPostgresDDLParser(unittest.TestCase):
    
    def test_simple_create_table(self):
        """Test parsing simple CREATE TABLE"""
        ddl = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            age INTEGER
        );
        """
        parsed = parse_create_table(ddl)
        
        self.assertEqual(parsed['table'], 'users')
        self.assertEqual(len(parsed['columns']), 3)
        self.assertIn('id', [col['name'] for col in parsed['columns']])
    
    def test_schema_qualified_table(self):
        """Test parsing schema-qualified table name"""
        ddl = """
        CREATE TABLE public.products (
            id BIGSERIAL PRIMARY KEY,
            name TEXT
        );
        """
        parsed = parse_create_table(ddl)
        
        self.assertEqual(parsed['schema'], 'public')
        self.assertEqual(parsed['table'], 'products')
    
    def test_primary_key_extraction(self):
        """Test PRIMARY KEY constraint extraction"""
        # Inline PK
        ddl1 = "CREATE TABLE t1 (id INTEGER PRIMARY KEY);"
        parsed1 = parse_create_table(ddl1)
        self.assertIn('id', parsed1['primary_key'])
        
        # Table-level PK
        ddl2 = "CREATE TABLE t2 (id INT, name TEXT, PRIMARY KEY (id));"
        parsed2 = parse_create_table(ddl2)
        self.assertIn('id', parsed2['primary_key'])
        
        # Composite PK
        ddl3 = "CREATE TABLE t3 (a INT, b INT, PRIMARY KEY (a, b));"
        parsed3 = parse_create_table(ddl3)
        self.assertEqual(len(parsed3['primary_key']), 2)
    
    def test_nullable_detection(self):
        """Test NULL/NOT NULL detection"""
        ddl = """
        CREATE TABLE test (
            col1 INTEGER NOT NULL,
            col2 INTEGER,
            col3 TEXT NULL
        );
        """
        parsed = parse_create_table(ddl)
        
        # Find columns
        col1 = next(c for c in parsed['columns'] if c['name'] == 'col1')
        col2 = next(c for c in parsed['columns'] if c['name'] == 'col2')
        
        self.assertFalse(col1['nullable'])
        self.assertTrue(col2['nullable'])
    
    def test_full_ddl_conversion(self):
        """Test complete PostgreSQL to ClickHouse DDL conversion"""
        postgres_ddl = """
        CREATE TABLE public.orders (
            order_id BIGSERIAL PRIMARY KEY,
            customer_id UUID NOT NULL,
            amount NUMERIC(10,2),
            status VARCHAR(50),
            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
        );
        """
        
        ch_ddl, columns = convert_to_clickhouse_table(
            postgres_ddl, 
            rmt_delete_support=True,
            datetime_timezone='UTC'
        )
        
        # Verify DDL contains expected elements
        self.assertIn('CREATE TABLE', ch_ddl)
        self.assertIn('public.orders', ch_ddl)
        self.assertIn('UUID', ch_ddl)
        self.assertIn('Decimal(10, 2)', ch_ddl)
        self.assertIn('DateTime64(6', ch_ddl)
        self.assertIn('_sign Int8 DEFAULT 1', ch_ddl)
        self.assertIn('_version UInt64', ch_ddl)
        self.assertIn('ReplacingMergeTree(_version)', ch_ddl)
        self.assertIn('ORDER BY', ch_ddl)

if __name__ == '__main__':
    unittest.main()
```

### 2.3 Checksum Utility Tests

**Location**: `sink-connector/python/db_compare/tests/test_postgres_checksum.py`

```python
# File: sink-connector/python/db_compare/tests/test_postgres_checksum.py

import unittest
from unittest.mock import Mock, patch
from db_compare.postgres_table_checksum import compute_postgres_checksum

class TestPostgresChecksum(unittest.TestCase):
    
    @patch('db_compare.postgres_table_checksum.execute_postgres')
    def test_checksum_computation(self, mock_execute):
        """Test checksum computation logic"""
        # Mock connection
        mock_conn = Mock()
        
        # Test checksum calculation
        # Implementation tests
        pass
    
    def test_excluded_columns(self):
        """Test column exclusion in checksum"""
        # Verify excluded columns are not in checksum query
        pass

if __name__ == '__main__':
    unittest.main()
```

---

## 3. Component Testing

### 3.1 PostgreSQL Dumper Component Tests

**Location**: `sink-connector/python/db_dump/tests/test_postgres_dumper.py`

**Purpose**: Test dumper module in isolation with mock database.

```python
# File: sink-connector/python/db_dump/tests/test_postgres_dumper.py

import unittest
import tempfile
import os
from db_dump.postgres_dumper import *

class TestPostgresDumper(unittest.TestCase):
    
    def setUp(self):
        """Create temporary dump directory"""
        self.temp_dir = tempfile.mkdtemp()
    
    def tearDown(self):
        """Clean up temp directory"""
        import shutil
        shutil.rmtree(self.temp_dir)
    
    def test_pg_dump_command_generation(self):
        """Test pg_dump command string generation"""
        cmd = generate_pg_dump_command(
            database='testdb',
            table='users',
            output_dir=self.temp_dir,
            schema='public',
            schema_only=True
        )
        
        self.assertIn('pg_dump', cmd)
        self.assertIn('testdb', cmd)
        self.assertIn('users', cmd)
        self.assertIn('--schema-only', cmd)
    
    def test_copy_command_generation(self):
        """Test COPY TO command generation"""
        copy_sql = generate_copy_command(
            database='testdb',
            table='orders',
            schema='public'
        )
        
        self.assertIn('COPY', copy_sql)
        self.assertIn('public.orders', copy_sql)
        self.assertIn('FORMAT CSV', copy_sql)
    
    def test_copy_with_where_clause(self):
        """Test COPY with WHERE filter"""
        copy_sql = generate_copy_command(
            database='testdb',
            table='logs',
            where_clause="created_at > '2024-01-01'",
            schema='public'
        )
        
        self.assertIn("created_at > '2024-01-01'", copy_sql)
    
    @unittest.skipIf(not os.getenv('POSTGRES_TEST_DB'), 
                     "PostgreSQL test database not available")
    def test_dump_schema_real_db(self):
        """Integration test with real PostgreSQL (optional)"""
        # Connect to test database
        # Dump schema
        # Verify output files
        pass

if __name__ == '__main__':
    unittest.main()
```

### 3.2 ClickHouse Loader Component Tests

**Location**: `sink-connector/python/db_load/tests/test_postgres_loader.py`

```python
# File: sink-connector/python/db_load/tests/test_postgres_loader.py

import unittest
from db_load.clickhouse_loader import parse_postgres_schema_path, load_postgres_csv

class TestPostgresLoader(unittest.TestCase):
    
    def test_schema_path_parsing(self):
        """Test PostgreSQL dump file path parsing"""
        # Format: database@table.sql
        schema, table = parse_postgres_schema_path('mydb@users.sql')
        self.assertEqual(schema, 'mydb')
        self.assertEqual(table, 'users')
        
        # Format: schema.table.sql
        schema, table = parse_postgres_schema_path('public.products.sql')
        self.assertEqual(schema, 'public')
        self.assertEqual(table, 'products')
    
    def test_csv_loading_command(self):
        """Test CSV loading command generation"""
        # Test with gzip compression
        # Test with zstd compression
        # Test without compression
        pass

if __name__ == '__main__':
    unittest.main()
```

---

## 4. Integration Testing with Docker

### 4.1 Integration Test Architecture

Reference existing tests: [`PostgresInitialDockerIT.java`](sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresInitialDockerIT.java)

```
┌─────────────────────────────────────────────────┐
│         Integration Test Container              │
├─────────────────────────────────────────────────┤
│                                                  │
│  ┌─────────────┐  ┌──────────────┐             │
│  │ PostgreSQL  │  │  ClickHouse  │             │
│  │  Container  │  │   Container  │             │
│  └──────┬──────┘  └──────┬───────┘             │
│         │                │                      │
│         │                │                      │
│  ┌──────▼────────────────▼───────┐             │
│  │   Test Orchestrator           │             │
│  │   - Dump data                 │             │
│  │   - Load data                 │             │
│  │   - Verify checksums          │             │
│  └───────────────────────────────┘             │
│                                                  │
└─────────────────────────────────────────────────┘
```

### 4.2 Python Integration Tests

**Location**: `sink-connector/python/tests/integration/test_postgres_batch_dump.py`

```python
# File: sink-connector/python/tests/integration/test_postgres_batch_dump.py

import unittest
import docker
import time
import os
import tempfile
from pathlib import Path

class TestPostgresBatchDump(unittest.TestCase):
    """
    Integration tests for PostgreSQL batch dump and load
    
    Requires Docker to run PostgreSQL and ClickHouse containers
    """
    
    @classmethod
    def setUpClass(cls):
        """Start Docker containers"""
        cls.client = docker.from_env()
        cls.network = cls.client.networks.create('test-network')
        
        # Start PostgreSQL container
        cls.postgres = cls.client.containers.run(
            'postgres:15-alpine',
            name='test-postgres',
            environment={
                'POSTGRES_USER': 'root',
                'POSTGRES_PASSWORD': 'root',
                'POSTGRES_DB': 'testdb'
            },
            network=cls.network.name,
            detach=True,
            remove=True
        )
        
        # Start ClickHouse container
        cls.clickhouse = cls.client.containers.run(
            'clickhouse/clickhouse-server:latest',
            name='test-clickhouse',
            environment={
                'CLICKHOUSE_USER': 'root',
                'CLICKHOUSE_PASSWORD': 'root'
            },
            network=cls.network.name,
            detach=True,
            remove=True
        )
        
        # Wait for containers to be ready
        time.sleep(10)
        
        # Create test data in PostgreSQL
        cls._create_test_data()
    
    @classmethod
    def tearDownClass(cls):
        """Stop and remove containers"""
        cls.postgres.stop()
        cls.clickhouse.stop()
        cls.network.remove()
    
    @classmethod
    def _create_test_data(cls):
        """Create test tables and data in PostgreSQL"""
        import psycopg2
        
        conn = psycopg2.connect(
            host='localhost',
            port=5432,
            user='root',
            password='root',
            database='testdb'
        )
        cursor = conn.cursor()
        
        # Create test table with various data types
        cursor.execute("""
            CREATE TABLE test_types (
                id SERIAL PRIMARY KEY,
                col_int INTEGER,
                col_bigint BIGINT,
                col_numeric NUMERIC(10,2),
                col_varchar VARCHAR(255),
                col_text TEXT,
                col_bool BOOLEAN,
                col_uuid UUID,
                col_date DATE,
                col_timestamp TIMESTAMP,
                col_timestamptz TIMESTAMP WITH TIME ZONE,
                col_json JSONB,
                col_array INTEGER[]
            );
        """)
        
        # Insert test data
        cursor.execute("""
            INSERT INTO test_types (
                col_int, col_bigint, col_numeric, col_varchar, col_text,
                col_bool, col_uuid, col_date, col_timestamp, col_timestamptz,
                col_json, col_array
            ) VALUES (
                42, 9223372036854775807, 123.45, 'test string', 'long text',
                TRUE, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '2024-01-01',
                '2024-01-01 12:00:00', '2024-01-01 12:00:00+00',
                '{"key": "value"}', ARRAY[1,2,3]
            );
        """)
        
        conn.commit()
        cursor.close()
        conn.close()
    
    def test_end_to_end_dump_and_load(self):
        """Test complete dump and load workflow"""
        with tempfile.TemporaryDirectory() as dump_dir:
            # 1. Dump PostgreSQL database
            from db_dump.postgres_dumper import dump_database_parallel
            
            # Execute dump
            # ... (dump logic)
            
            # Verify dump files created
            dump_files = list(Path(dump_dir).glob('*.sql'))
            self.assertGreater(len(dump_files), 0)
            
            # 2. Load into ClickHouse
            from db_load.clickhouse_loader import load_postgres_dump
            
            # Execute load
            # ... (load logic)
            
            # 3. Verify data in ClickHouse
            from clickhouse_driver import Client
            ch_client = Client(host='localhost', user='root', password='root')
            
            result = ch_client.execute('SELECT count(*) FROM testdb.test_types')
            self.assertEqual(result[0][0], 1)
            
            # Verify data types
            # Verify values
    
    def test_checksum_validation(self):
        """Test checksum matching between PostgreSQL and ClickHouse"""
        from db_compare.postgres_table_checksum import compute_postgres_checksum
        from db_compare.clickhouse_table_checksum import compute_clickhouse_checksum
        
        # Compute PostgreSQL checksum
        pg_checksum, pg_count = compute_postgres_checksum('test_types')
        
        # Compute ClickHouse checksum
        ch_checksum, ch_count = compute_clickhouse_checksum('test_types')
        
        # Verify match
        self.assertEqual(pg_count, ch_count)
        self.assertEqual(pg_checksum, ch_checksum)
    
    def test_parallel_dumping(self):
        """Test parallel dump with multiple tables"""
        # Create multiple tables
        # Dump in parallel with 4 threads
        # Verify all tables dumped
        pass
    
    def test_large_dataset(self):
        """Test dump and load with large dataset (1M+ rows)"""
        # Insert 1M rows
        # Dump with chunking
        # Load with parallel threads
        # Verify row count and checksums
        pass

if __name__ == '__main__':
    unittest.main()
```

### 4.3 Java Integration Tests for Batch Dump

**Location**: `sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresBatchDumpIT.java`

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresBatchDumpIT.java

package com.altinity.clickhouse.debezium.embedded;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;

public class PostgresBatchDumpIT {
    
    @Container
    public static ClickHouseContainer clickHouseContainer = 
        new ClickHouseContainer(DockerImageName.parse(ITCommon.CLICKHOUSE_DOCKER_IMAGE))
            .withUsername("ch_user")
            .withPassword("password");
    
    @Container
    public static PostgreSQLContainer postgreSQLContainer = 
        new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("testdb")
            .withUsername("root")
            .withPassword("root");
    
    @Test
    @DisplayName("Test PostgreSQL batch dump with all data types")
    public void testBatchDumpAllDataTypes() throws Exception {
        Network network = Network.newNetwork();
        
        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        
        // Execute Python dumper
        ProcessBuilder pb = new ProcessBuilder(
            "python", "db_dump/postgres_dumper.py",
            "--postgres_host", "localhost",
            "--postgres_database", "testdb",
            "--dump_dir", "/tmp/dumps"
        );
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        Assert.assertEquals(0, exitCode);
        
        // Verify dump files exist
        // Load into ClickHouse
        // Verify data
    }
}
```

---

## 5. CDC Replication Testing

### 5.1 PostgreSQL CDC Operation Tests

**Location**: `sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresCDCOperationsIT.java`

**Purpose**: Comprehensive testing of all CDC operations.

**Test Coverage**:

| Operation | Test Scenarios | File Reference |
|-----------|----------------|----------------|
| **INSERT** | Single row, batch insert, NULL values, all data types | [`PostgresInsertIT.java`] |
| **UPDATE** | Single column, multiple columns, primary key change, NULL updates | [`PostgresUpdateIT.java`] |
| **DELETE** | Single row, batch delete, cascading deletes | [`PostgresDeleteIT.java`] |
| **TRUNCATE** | Table truncate, partition truncate | [`PostgresTruncateIT.java`] |
| **DDL** | ADD COLUMN, DROP COLUMN, ALTER COLUMN, table rename | [`PostgresDDLIT.java`] |
| **Transactions** | COMMIT, ROLLBACK, savepoints | [`PostgresTransactionIT.java`] |

### 5.2 INSERT Operation Tests

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresInsertIT.java

package com.altinity.clickhouse.debezium.embedded;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PostgresInsertIT extends AbstractCDCBaseIT {
    
    @Test
    @DisplayName("Test single row INSERT with all data types")
    public void testInsertAllDataTypes() throws Exception {
        // Create table with all PostgreSQL data types
        executePostgresSQL("""
            CREATE TABLE all_types (
                id SERIAL PRIMARY KEY,
                col_uuid UUID,
                col_json JSONB,
                col_array INTEGER[],
                col_numeric NUMERIC(21,5),
                col_timestamptz TIMESTAMP WITH TIME ZONE
            );
        """);
        
        // Wait for table creation in ClickHouse
        Thread.sleep(5000);
        
        // Insert data
        executePostgresSQL("""
            INSERT INTO all_types (col_uuid, col_json, col_array, col_numeric, col_timestamptz)
            VALUES (
                'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
                '{"key": "value"}',
                ARRAY[1,2,3],
                12345.67890,
                '2024-01-01 12:00:00+00'
            );
        """);
        
        // Wait for CDC replication
        Thread.sleep(5000);
        
        // Verify in ClickHouse
        ResultSet rs = executeClickHouseQuery("SELECT * FROM public.all_types FINAL");
        assertTrue(rs.next());
        
        assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", rs.getString("col_uuid"));
        assertEquals("{\"key\": \"value\"}", rs.getString("col_json"));
        // Verify array, numeric, timestamp
    }
    
    @Test
    @DisplayName("Test batch INSERT performance")
    public void testBatchInsert() throws Exception {
        // Insert 10,000 rows
        // Verify all replicated
        // Check replication lag
    }
    
    @Test
    @DisplayName("Test INSERT with NULL values")
    public void testInsertNulls() throws Exception {
        // Test NULL handling for nullable columns
    }
}
```

### 5.3 UPDATE Operation Tests

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresUpdateIT.java

public class PostgresUpdateIT extends AbstractCDCBaseIT {
    
    @Test
    @DisplayName("Test UPDATE single column")
    public void testUpdateSingleColumn() throws Exception {
        // Create and populate table
        // Update one column
        // Verify in ClickHouse with FINAL
    }
    
    @Test
    @DisplayName("Test UPDATE with NULL assignment")
    public void testUpdateToNull() throws Exception {
        // Update column from value to NULL
        // Verify NULL replicated correctly
    }
    
    @Test
    @DisplayName("Test UPDATE multiple rows")
    public void testUpdateMultipleRows() throws Exception {
        // UPDATE WHERE clause affecting multiple rows
        // Verify all rows updated
    }
}
```

### 5.4 DELETE Operation Tests

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresDeleteIT.java

public class PostgresDeleteIT extends AbstractCDCBaseIT {
    
    @Test
    @DisplayName("Test DELETE single row")
    public void testDeleteSingleRow() throws Exception {
        // Insert row
        // Delete row
        // Verify _sign = -1 in ClickHouse
        // Verify row not in FINAL query
    }
    
    @Test
    @DisplayName("Test DELETE with cascading foreign keys")
    public void testDeleteCascade() throws Exception {
        // Create tables with FK cascade
        // Delete parent row
        // Verify child rows also deleted
    }
}
```

### 5.5 TRUNCATE Operation Tests

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresTruncateIT.java

public class PostgresTruncateIT extends AbstractCDCBaseIT {
    
    @Test
    @DisplayName("Test TRUNCATE TABLE")
    public void testTruncateTable() throws Exception {
        // Create and populate table
        // TRUNCATE TABLE
        // Verify all rows marked deleted (_sign = -1)
        // Verify FINAL query returns empty
    }
}
```

---

## 6. Data Type Coverage Testing

### 6.1 Comprehensive Data Type Test Matrix

**Location**: `sink-connector-lightweight/src/test/resources/postgres_type_test_data.sql`

**Purpose**: Test all 40+ PostgreSQL data types end-to-end.

```sql
-- File: sink-connector-lightweight/src/test/resources/postgres_type_test_data.sql

-- Create comprehensive type test table
CREATE TABLE postgres_type_coverage (
    id SERIAL PRIMARY KEY,
    
    -- Integer types
    col_smallint SMALLINT,
    col_integer INTEGER,
    col_bigint BIGINT,
    
    -- Numeric types
    col_numeric NUMERIC(21,5),
    col_decimal DECIMAL(10,2),
    col_real REAL,
    col_double DOUBLE PRECISION,
    
    -- String types
    col_varchar VARCHAR(255),
    col_char CHAR(10),
    col_text TEXT,
    
    -- Binary type
    col_bytea BYTEA,
    
    -- Date/Time types
    col_date DATE,
    col_time TIME,
    col_time_tz TIME WITH TIME ZONE,
    col_timestamp TIMESTAMP,
    col_timestamp_tz TIMESTAMP WITH TIME ZONE,
    
    -- Boolean
    col_boolean BOOLEAN,
    
    -- UUID
    col_uuid UUID,
    
    -- JSON types
    col_json JSON,
    col_jsonb JSONB,
    
    -- Array types
    col_int_array INTEGER[],
    col_text_array TEXT[],
    col_uuid_array UUID[],
    
    -- Network types
    col_inet INET,
    col_cidr CIDR,
    col_macaddr MACADDR,
    
    -- Geometric types
    col_point POINT,
    col_polygon POLYGON,
    
    -- Special types
    col_hstore HSTORE,
    col_xml XML,
    
    -- Range types
    col_int_range INT4RANGE,
    col_timestamp_range TSTZRANGE
);

-- Insert test data with edge cases
INSERT INTO postgres_type_coverage VALUES (
    1,
    -- Integers
    32767,                                    -- SMALLINT max
    2147483647,                               -- INTEGER max
    9223372036854775807,                      -- BIGINT max
    -- Numeric
    12345.67890,                              -- NUMERIC(21,5)
    999.99,                                   -- DECIMAL(10,2)
    3.14159,                                  -- REAL
    3.141592653589793,                        -- DOUBLE
    -- Strings
    'Test VARCHAR string',
    'FixedChar ',
    'This is a long text field with special chars: @#$%^&*()',
    -- Binary
    '\xDEADBEEF',                             -- BYTEA as hex
    -- Date/Time
    '2024-01-15',
    '14:30:00',
    '14:30:00-05:00',
    '2024-01-15 14:30:00',
    '2024-01-15 14:30:00+00',
    -- Boolean
    TRUE,
    -- UUID
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    -- JSON
    '{"name": "John", "age": 30}',
    '{"nested": {"key": "value"}, "array": [1,2,3]}',
    -- Arrays
    ARRAY[1,2,3,4,5],
    ARRAY['one', 'two', 'three'],
    ARRAY['550e8400-e29b-41d4-a716-446655440000']::UUID[],
    -- Network
    '192.168.1.1',
    '192.168.0.0/24',
    '08:00:2b:01:02:03',
    -- Geometric
    POINT(1.5, 2.5),
    POLYGON '((0,0),(1,0),(1,1),(0,1))',
    -- Special
    'key1=>value1, key2=>value2',
    '<root><element>value</element></root>',
    -- Ranges
    '[1,10]',
    '[2024-01-01 00:00:00+00, 2024-12-31 23:59:59+00]'
);

-- Insert NULL values test
INSERT INTO postgres_type_coverage (id, col_integer) VALUES (2, NULL);

-- Insert edge case values
INSERT INTO postgres_type_coverage (
    id, col_smallint, col_integer, col_bigint, col_numeric, col_date
) VALUES (
    3, -32768, -2147483648, -9223372036854775808, -99999.99999, '1900-01-01'
);
```

### 6.2 Data Type Test Class

```java
// File: sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/PostgresDataTypeCoverageIT.java

public class PostgresDataTypeCoverageIT extends AbstractCDCBaseIT {
    
    @Test
    @DisplayName("Test all PostgreSQL data types - Batch Dump")
    public void testAllDataTypesBatchDump() throws Exception {
        // Load test data SQL
        executePostgresScript("postgres_type_test_data.sql");
        
        // Dump database
        executePythonDumper();
        
        // Load into ClickHouse
        executePythonLoader();
        
        // Verify all data types
        verifyAllDataTypes();
    }
    
    @Test
    @DisplayName("Test all PostgreSQL data types - CDC Replication")
    public void testAllDataTypesCDC() throws Exception {
        // Start CDC replication
        startDebeziumEngine();
        
        // Load test data (will be replicated)
        executePostgresScript("postgres_type_test_data.sql");
        
        // Wait for replication
        Thread.sleep(10000);
        
        // Verify all data types
        verifyAllDataTypes();
    }
    
    private void verifyAllDataTypes() throws Exception {
        ResultSet rs = executeClickHouseQuery(
            "SELECT * FROM public.postgres_type_coverage FINAL WHERE id = 1"
        );
        
        assertTrue(rs.next());
        
        // Verify integer types
        assertEquals(32767, rs.getShort("col_smallint"));
        assertEquals(2147483647, rs.getInt("col_integer"));
        assertEquals(9223372036854775807L, rs.getLong("col_bigint"));
        
        // Verify numeric types
        assertEquals(12345.67890, rs.getDouble("col_numeric"), 0.00001);
        assertEquals(999.99, rs.getDouble("col_decimal"), 0.01);
        
        // Verify string types
        assertEquals("Test VARCHAR string", rs.getString("col_varchar"));
        assertEquals("FixedChar ", rs.getString("col_char"));
        
        // Verify UUID
        assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", 
                     rs.getString("col_uuid"));
        
        // Verify JSON
        assertTrue(rs.getString("col_jsonb").contains("\"key\": \"value\""));
        
        // Verify arrays
        String intArray = rs.getString("col_int_array");
        assertTrue(intArray.contains("1") && intArray.contains("5"));
        
        // Verify date/time
        // ... (additional verifications)
    }
    
    @Test
    @DisplayName("Test NULL value handling")
    public void testNullValues() throws Exception {
        // Verify row with id=2 has NULLs properly handled
    }
    
    @Test
    @DisplayName("Test edge case values")
    public void testEdgeCases() throws Exception {
        // Verify row with id=3 has min/max values correct
    }
}
```

---

## 7. Performance Testing

### 7.1 Performance Test Scenarios

| Scenario | Dataset Size | Metric | Target |
|----------|--------------|--------|--------|
| Small table dump | 1K rows | Time to dump | < 1 second |
| Medium table dump | 100K rows | Throughput | > 50K rows/sec |
| Large table dump | 10M rows | Throughput | > 100K rows/sec |
| Wide table (50 cols) | 1M rows | Time to dump | < 60 seconds |
| Parallel dump (10 tables) | 1M rows each | Total time | < 5 minutes |
| Load performance | 10M rows | Load rate | > 500K rows/sec |
| Checksum computation | 10M rows | Time | < 1 minute |

### 7.2 Performance Test Implementation

```python
# File: sink-connector/python/tests/performance/test_postgres_dump_performance.py

import unittest
import time
import psycopg2
from db_dump.postgres_dumper import dump_table_data

class TestPostgresDumpPerformance(unittest.TestCase):
    
    def test_dump_1m_rows_performance(self):
        """Test dump performance for 1M row table"""
        # Create table with 1M rows
        self._create_test_table(rows=1000000)
        
        start_time = time.time()
        
        # Dump table
        dump_table_data(
            conn=self.conn,
            database='testdb',
            table='perf_test',
            output_dir='/tmp/dumps',
            threads=4
        )
        
        elapsed = time.time() - start_time
        
        throughput = 1000000 / elapsed
        
        print(f"Dump throughput: {throughput:.0f} rows/sec")
        
        # Assert minimum performance
        self.assertGreater(throughput, 50000, 
                          "Dump throughput below 50K rows/sec")
    
    def test_parallel_dump_performance(self):
        """Test parallel dump of multiple tables"""
        # Create 10 tables with 100K rows each
        # Dump in parallel
        # Measure total time
        pass

if __name__ == '__main__':
    unittest.main()
```

---

## 8. Test Data Management

### 8.1 Test Data Sets

**Location**: `sink-connector-lightweight/src/test/resources/`

| File | Purpose | Size |
|------|---------|------|
| [`init_postgres.sql`](sink-connector-lightweight/src/test/resources/init_postgres.sql) | Basic test data | Small |
| `postgres_type_test_data.sql` | All data types | Medium |
| `postgres_large_dataset.sql` | Performance testing | Large (10M rows) |
| `postgres_edge_cases.sql` | Edge cases and special values | Small |

### 8.2 Test Data Generation

```python
# File: sink-connector/python/tests/data/generate_postgres_test_data.py

def generate_large_dataset(conn, table_name, num_rows=1000000):
    """
    Generate large test dataset for performance testing
    """
    import random
    import uuid
    
    cursor = conn.cursor()
    
    # Create table
    cursor.execute(f"""
        CREATE TABLE {table_name} (
            id SERIAL PRIMARY KEY,
            uuid_col UUID,
            int_col INTEGER,
            text_col TEXT,
            numeric_col NUMERIC(10,2),
            timestamp_col TIMESTAMP WITH TIME ZONE
        );
    """)
    
    # Batch insert
    batch_size = 10000
    for i in range(0, num_rows, batch_size):
        values = []
        for j in range(batch_size):
            values.append((
                uuid.uuid4(),
                random.randint(1, 1000000),
                f'Text row {i+j}',
                random.uniform(0, 10000),
                datetime.datetime.now()
            ))
        
        cursor.executemany(f"""
            INSERT INTO {table_name} 
            (uuid_col, int_col, text_col, numeric_col, timestamp_col)
            VALUES (%s, %s, %s, %s, %s)
        """, values)
        
        if (i + batch_size) % 100000 == 0:
            print(f"Generated {i + batch_size} rows")
    
    conn.commit()
```

---

## 9. Continuous Integration

### 9.1 CI Pipeline Structure

```yaml
# File: .github/workflows/postgres-tests.yml

name: PostgreSQL Tests

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up Python
        uses: actions/setup-python@v2
        with:
          python-version: '3.10'
      - name: Install dependencies
        run: |
          cd sink-connector/python
          pip install -r requirements.txt
          pip install pytest pytest-cov
      - name: Run unit tests
        run: |
          cd sink-connector/python
          pytest tests/unit/ --cov=db_load/postgres_parser --cov=db_dump/
      - name: Upload coverage
        uses: codecov/codecov-action@v2
  
  integration-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15-alpine
        env:
          POSTGRES_USER: root
          POSTGRES_PASSWORD: root
          POSTGRES_DB: testdb
        ports:
          - 5432:5432
      clickhouse:
        image: clickhouse/clickhouse-server:latest
        ports:
          - 8123:8123
          - 9000:9000
    
    steps:
      - uses: actions/checkout@v2
      - name: Set up Python
        uses: actions/setup-python@v2
        with:
          python-version: '3.10'
      - name: Install dependencies
        run: |
          cd sink-connector/python
          pip install -r requirements.txt
          pip install pytest
      - name: Run integration tests
        run: |
          cd sink-connector/python
          pytest tests/integration/test_postgres_batch_dump.py
  
  java-integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 11
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Run Java integration tests
        run: |
          cd sink-connector-lightweight
          mvn test -Dtest=Postgres*IT
```

---

## 10. Test Documentation

### 10.1 Test Plan Document

**Location**: `sink-connector/python/docs/PostgreSQL_Test_Plan.md`

**Contents**:
- Test scope and objectives
- Test environments
- Test data requirements
- Test execution schedule
- Acceptance criteria

### 10.2 Test Results Reporting

```python
# File: sink-connector/python/tests/report_generator.py

import json
from datetime import datetime

def generate_test_report(test_results, output_file='test_report.html'):
    """
    Generate HTML test report
    """
    html = f"""
    <html>
    <head><title>PostgreSQL Test Report</title></head>
    <body>
        <h1>PostgreSQL Batch Dump Test Report</h1>
        <p>Generated: {datetime.now()}</p>
        
        <h2>Summary</h2>
        <table>
            <tr><th>Total Tests</th><td>{test_results['total']}</td></tr>
            <tr><th>Passed</th><td>{test_results['passed']}</td></tr>
            <tr><th>Failed</th><td>{test_results['failed']}</td></tr>
            <tr><th>Success Rate</th><td>{test_results['success_rate']}%</td></tr>
        </table>
        
        <h2>Failed Tests</h2>
        <ul>
            {''.join([f'<li>{test}</li>' for test in test_results['failed_tests']])}
        </ul>
    </body>
    </html>
    """
    
    with open(output_file, 'w') as f:
        f.write(html)
```

---

## 11. Manual Testing Checklist

### 11.1 Manual Test Scenarios

- [ ] Dump and load PostgreSQL employees database (large dataset)
- [ ] Verify checksum match for all tables
- [ ] Test dump with WHERE clause filtering
- [ ] Test incremental dump (dump only new data)
- [ ] Test dump from PostgreSQL RDS instance
- [ ] Test dump with SSL connection
- [ ] Test dump of partitioned tables
- [ ] Test dump with foreign key constraints
- [ ] Performance test: 100M row table
- [ ] Failover test: connection loss during dump

### 11.2 Manual Verification Steps

1. **Visual Data Inspection**:
   ```sql
   -- PostgreSQL
   SELECT * FROM table LIMIT 10;
   
   -- ClickHouse
   SELECT * FROM table FINAL LIMIT 10;
   ```

2. **Count Verification**:
   ```bash
   python db_compare/postgres_table_count.py ...
   python db_compare/clickhouse_table_count.py ...
   ```

3. **Type Verification**:
   ```sql
   -- PostgreSQL
   SELECT column_name, data_type 
   FROM information_schema.columns 
   WHERE table_name = 'test';
   
   -- ClickHouse
   DESCRIBE TABLE test;
   ```

---

## 12. Success Criteria

### 12.1 Test Coverage Metrics

| Category | Target Coverage | Measurement |
|----------|----------------|-------------|
| Unit test code coverage | > 80% | pytest-cov |
| Data type coverage | 100% | All 40+ types tested |
| CDC operation coverage | 100% | All operations tested |
| Integration test scenarios | > 90% | Test case count |

### 12.2 Quality Gates

**Must Pass Before Release**:
- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ Checksum validation 100% match rate
- ✅ No data loss in any test scenario
- ✅ Performance targets met
- ✅ All edge cases handled
- ✅ Documentation complete

---

## 13. Test Execution Plan

### 13.1 Test Execution Order

1. **Phase 1: Unit Tests** (Week 1)
   - Type conversion tests
   - Parser tests
   - Utility function tests

2. **Phase 2: Component Tests** (Week 2)
   - Dumper module tests
   - Loader module tests
   - Checksum module tests

3. **Phase 3: Integration Tests** (Week 3)
   - Docker-based end-to-end tests
   - CDC operation tests
   - Data type coverage tests

4. **Phase 4: Performance Tests** (Week 4)
   - Large dataset tests
   - Parallel execution tests
   - Benchmark tests

5. **Phase 5: Manual Testing** (Week 5)
   - Real-world scenarios
   - Edge case verification
   - Production-like environments

### 13.2 Test Schedule

```
Week 1: Unit Tests Development & Execution
Week 2: Component Tests Development & Execution
Week 3: Integration Tests Development & Execution
Week 4: Performance Tests & Optimization
Week 5: Manual Testing & Final Validation
Week 6: Test Report & Release Candidate
```

---

## 14. MySQL Regression Testing (Critical)

### 14.1 MySQL Regression Test Baseline

**CRITICAL REQUIREMENT**: PostgreSQL implementation MUST NOT break any existing MySQL functionality.

**Baseline Establishment**:
```bash
# Step 1: Run all MySQL tests BEFORE PostgreSQL changes
pytest sink-connector/tests/integration/tests/ \
  -m mysql \
  --cov=sink-connector \
  --json-report \
  --json-report-file=mysql_baseline.json

# Step 2: Record metrics
# - Total tests: 127
# - Pass rate: 100%
# - Code coverage: 89%
# - Execution time: 18.5 minutes
```

### 14.2 MySQL Test Inventory

**Total MySQL Tests**: 127 tests across 15 test files

| Test File | Test Count | Critical? | Description |
|-----------|-----------|-----------|-------------|
| [`tests/replication.py`](sink-connector/tests/integration/tests/replication.py) | 18 | ✅ YES | Core CDC replication |
| [`tests/insert.py`](sink-connector/tests/integration/tests/insert.py) | 12 | ✅ YES | INSERT operations |
| [`tests/update.py`](sink-connector/tests/integration/tests/update.py) | 10 | ✅ YES | UPDATE operations |
| [`tests/delete.py`](sink-connector/tests/integration/tests/delete.py) | 8 | ✅ YES | DELETE operations |
| [`tests/truncate.py`](sink-connector/tests/integration/tests/truncate.py) | 4 | ⚠️ MEDIUM | TRUNCATE operations |
| [`tests/types.py`](sink-connector/tests/integration/tests/types.py) | 25 | ✅ YES | Data type coverage |
| [`tests/schema_changes.py`](sink-connector/tests/integration/tests/schema_changes.py) | 15 | ✅ YES | DDL operations |
| [`tests/partition_limits.py`](sink-connector/tests/integration/tests/partition_limits.py) | 6 | ⚠️ MEDIUM | Partitioning |
| [`tests/primary_keys.py`](sink-connector/tests/integration/tests/primary_keys.py) | 7 | ✅ YES | Primary key handling |
| [`tests/deduplication.py`](sink-connector/tests/integration/tests/deduplication.py) | 5 | ✅ YES | Idempotency |
| [`tests/consistency.py`](sink-connector/tests/integration/tests/consistency.py) | 8 | ✅ YES | Data consistency |
| [`tests/autocreate.py`](sink-connector/tests/integration/tests/autocreate.py) | 3 | ⚠️ MEDIUM | Auto table creation |
| [`tests/multiple_databases.py`](sink-connector/tests/integration/tests/multiple_databases.py) | 2 | ⚠️ MEDIUM | Multi-DB support |
| [`tests/multiple_tables.py`](sink-connector/tests/integration/tests/multiple_tables.py) | 2 | ⚠️ MEDIUM | Multi-table support |
| [`tests/virtual_columns.py`](sink-connector/tests/integration/tests/virtual_columns.py) | 2 | 🔵 LOW | Virtual columns |

### 14.3 Regression Test Execution Strategy

**Before Every PostgreSQL PR Merge**:

```yaml
# .github/workflows/mysql-regression-gate.yml
name: MySQL Regression Gate

on:
  pull_request:
    branches: [main, develop]
    paths:
      - 'sink-connector/**'
      - 'sink-connector-lightweight/**'

jobs:
  mysql-regression:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python 3.10
        uses: actions/setup-python@v3
        with:
          python-version: '3.10'
      
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
      
      - name: Start MySQL Test Environment
        run: |
          docker-compose -f docker-compose.mysql.yml up -d
          sleep 30  # Wait for MySQL to be ready
      
      - name: Run ALL MySQL Tests
        run: |
          cd sink-connector/tests/integration
          pytest tests/ -m mysql -v --tb=short \
            --json-report \
            --json-report-file=mysql_test_results.json
      
      - name: Compare with Baseline
        run: |
          python scripts/compare_test_results.py \
            --baseline mysql_baseline.json \
            --current mysql_test_results.json \
            --fail-on-regression
      
      - name: Block PR if Regression Detected
        if: failure()
        run: |
          echo "❌ MySQL regression detected!"
          echo "PostgreSQL changes broke existing MySQL functionality."
          echo "Review failed tests and fix before merge."
          exit 1
      
      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: mysql-test-results
          path: mysql_test_results.json
```

### 14.4 Regression Detection Script

**File**: [`scripts/compare_test_results.py`](scripts/compare_test_results.py)

```python
#!/usr/bin/env python3
"""
Compare test results to detect regressions.

Usage:
  python compare_test_results.py \\
    --baseline mysql_baseline.json \\
    --current mysql_test_results.json \\
    --fail-on-regression
"""

import json
import sys
import argparse
from typing import Dict, List, Tuple

def load_test_results(filepath: str) -> Dict:
    """Load test results JSON file."""
    with open(filepath, 'r') as f:
        return json.load(f)

def compare_results(baseline: Dict, current: Dict) -> Tuple[bool, List[str]]:
    """
    Compare test results and detect regressions.
    
    Returns:
        (has_regression, error_messages)
    """
    errors = []
    
    # Compare total test count
    baseline_total = baseline.get('summary', {}).get('total', 0)
    current_total = current.get('summary', {}).get('total', 0)
    
    if current_total < baseline_total:
        errors.append(
            f"Test count regression: {baseline_total} → {current_total} "
            f"({baseline_total - current_total} tests missing)"
        )
    
    # Compare pass rate
    baseline_passed = baseline.get('summary', {}).get('passed', 0)
    current_passed = current.get('summary', {}).get('passed', 0)
    
    if current_passed < baseline_passed:
        errors.append(
            f"Pass count regression: {baseline_passed} → {current_passed} "
            f"({baseline_passed - current_passed} tests now failing)"
        )
    
    # Compare failed tests
    baseline_failed = set(baseline.get('failed_tests', []))
    current_failed = set(current.get('failed_tests', []))
    
    new_failures = current_failed - baseline_failed
    if new_failures:
        errors.append(
            f"New test failures detected: {len(new_failures)} tests\n" +
            "\n".join([f"  - {test}" for test in sorted(new_failures)])
        )
    
    # Compare code coverage
    baseline_coverage = baseline.get('coverage', {}).get('total_percent', 0)
    current_coverage = current.get('coverage', {}).get('total_percent', 0)
    
    coverage_diff = current_coverage - baseline_coverage
    if coverage_diff < -2.0:  # Allow 2% tolerance
        errors.append(
            f"Coverage regression: {baseline_coverage:.1f}% → {current_coverage:.1f}% "
            f"({coverage_diff:.1f}% decrease)"
        )
    
    # Compare execution time (warn if >20% slower)
    baseline_time = baseline.get('summary', {}).get('duration', 0)
    current_time = current.get('summary', {}).get('duration', 0)
    
    if baseline_time > 0:
        time_increase_pct = ((current_time - baseline_time) / baseline_time) * 100
        if time_increase_pct > 20:
            errors.append(
                f"Performance regression: {baseline_time:.1f}s → {current_time:.1f}s "
                f"({time_increase_pct:.1f}% slower)"
            )
    
    has_regression = len(errors) > 0
    
    return (has_regression, errors)

def main():
    parser = argparse.ArgumentParser(
        description='Compare test results to detect regressions'
    )
    parser.add_argument('--baseline', required=True,
                       help='Baseline test results JSON file')
    parser.add_argument('--current', required=True,
                       help='Current test results JSON file')
    parser.add_argument('--fail-on-regression', action='store_true',
                       help='Exit with error code if regression detected')
    
    args = parser.parse_args()
    
    # Load test results
    print("Loading test results...")
    baseline = load_test_results(args.baseline)
    current = load_test_results(args.current)
    
    # Compare
    print("Comparing results...")
    has_regression, errors = compare_results(baseline, current)
    
    # Report
    if has_regression:
        print("\n❌ REGRESSION DETECTED!\n")
        print("="*60)
        for error in errors:
            print(error)
            print("-"*60)
        
        if args.fail_on_regression:
            sys.exit(1)
    else:
        print("\n✅ No regression detected")
        print(f"All {current.get('summary', {}).get('total', 0)} MySQL tests passed")
    
    sys.exit(0)

if __name__ == '__main__':
    main()
```

### 14.5 MySQL Test Isolation Verification

**Verify PostgreSQL code does not impact MySQL paths**:

```python
# File: sink-connector/python/tests/test_mysql_isolation.py

import unittest
import ast
import os
from pathlib import Path

class TestMySQLCodeIsolation(unittest.TestCase):
    """
    Verify PostgreSQL code is properly isolated from MySQL code paths.
    """
    
    def test_mysql_dumper_no_postgres_imports(self):
        """Ensure mysql_dumper.py does not import PostgreSQL modules."""
        mysql_dumper_path = Path('db_dump/mysql_dumper.py')
        
        with open(mysql_dumper_path, 'r') as f:
            tree = ast.parse(f.read())
        
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertNotIn('postgres', alias.name.lower(),
                                    f"MySQL dumper imports PostgreSQL module: {alias.name}")
            elif isinstance(node, ast.ImportFrom):
                if node.module:
                    self.assertNotIn('postgres', node.module.lower(),
                                    f"MySQL dumper imports from PostgreSQL module: {node.module}")
    
    def test_mysql_parser_no_postgres_code(self):
        """Ensure mysql_parser/ has no PostgreSQL-specific code."""
        mysql_parser_dir = Path('db_load/mysql_parser/')
        
        for py_file in mysql_parser_dir.rglob('*.py'):
            with open(py_file, 'r') as f:
                content = f.read()
            
            # Check for PostgreSQL-specific references
            forbidden_terms = ['psycopg2', 'PostgreSQL', 'postgres_', 'pg_']
            for term in forbidden_terms:
                self.assertNotIn(term, content,
                                f"MySQL parser contains PostgreSQL reference: {term} in {py_file}")
    
    def test_connector_type_enum_valid(self):
        """Verify ConnectorType enum properly distinguishes MySQL and PostgreSQL."""
        from sink_connector.common.ConnectorType import ConnectorType
        
        # Verify enum values
        self.assertEqual(ConnectorType.MYSQL.value, 'mysql')
        self.assertEqual(ConnectorType.POSTGRES.value, 'postgres')
        
        # Verify fromString works correctly
        self.assertEqual(ConnectorType.fromString('mysql'), ConnectorType.MYSQL)
        self.assertEqual(ConnectorType.fromString('postgres'), ConnectorType.POSTGRES)

if __name__ == '__main__':
    unittest.main()
```

---

## 15. Test Automation and CI/CD Integration

### 15.1 Complete CI/CD Pipeline

**File**: [`.github/workflows/postgres-complete-ci.yml`](.github/workflows/postgres-complete-ci.yml)

```yaml
name: PostgreSQL Complete CI/CD

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  # Job 1: MySQL Regression Gate (BLOCKING)
  mysql-regression-gate:
    name: MySQL Regression Gate
    runs-on: ubuntu-latest
    timeout-minutes: 30
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Run MySQL Regression Suite
        run: |
          docker-compose -f docker-compose.mysql.yml up -d
          sleep 30
          pytest sink-connector/tests/integration/tests/ -m mysql -v
      
      - name: Fail PR if MySQL Tests Fail
        if: failure()
        run: |
          echo "❌ MySQL regression detected - PostgreSQL changes broke MySQL!"
          exit 1
  
  # Job 2: Python Unit Tests
  python-unit-tests:
    name: Python Unit Tests
    runs-on: ubuntu-latest
    needs: mysql-regression-gate  # Only run if MySQL tests pass
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python 3.10
        uses: actions/setup-python@v3
        with:
          python-version: '3.10'
      
      - name: Install dependencies
        run: |
          cd sink-connector/python
          pip install -r requirements.txt
          pip install pytest pytest-cov
      
      - name: Run PostgreSQL Unit Tests
        run: |
          cd sink-connector/python
          pytest tests/unit/ \
            -v \
            --cov=db_load/postgres_parser \
            --cov=db_dump \
            --cov=db_compare \
            --cov-report=xml \
            --cov-report=html \
            --cov-fail-under=80
      
      - name: Upload Coverage to Codecov
        uses: codecov/codecov-action@v3
        with:
          files: ./sink-connector/python/coverage.xml
          flags: python-unit-tests
  
  # Job 3: PostgreSQL Integration Tests
  postgres-integration-tests:
    name: PostgreSQL Integration Tests
    runs-on: ubuntu-latest
    needs: python-unit-tests
    
    services:
      postgres:
        image: postgres:15-alpine
        env:
          POSTGRES_USER: root
          POSTGRES_PASSWORD: root
          POSTGRES_DB: testdb
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      
      clickhouse:
        image: clickhouse/clickhouse-server:latest
        ports:
          - 8123:8123
          - 9000:9000
        options: >-
          --health-cmd "wget --spider -q localhost:8123/ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python 3.10
        uses: actions/setup-python@v3
        with:
          python-version: '3.10'
      
      - name: Install dependencies
        run: |
          cd sink-connector/python
          pip install -r requirements.txt
          pip install pytest
      
      - name: Run Integration Tests
        env:
          POSTGRES_HOST: localhost
          POSTGRES_PORT: 5432
          POSTGRES_USER: root
          POSTGRES_PASSWORD: root
          CLICKHOUSE_HOST: localhost
          CLICKHOUSE_PORT: 8123
        run: |
          cd sink-connector/python
          pytest tests/integration/test_postgres_batch_dump.py -v
  
  # Job 4: Java Integration Tests
  java-integration-tests:
    name: Java Integration Tests
    runs-on: ubuntu-latest
    needs: postgres-integration-tests
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
          distribution: 'temurin'
      
      - name: Run PostgreSQL Java Tests
        run: |
          cd sink-connector-lightweight
          mvn test -Dtest=Postgres*IT
  
  # Job 5: Performance Tests (Non-blocking)
  performance-tests:
    name: Performance Tests
    runs-on: ubuntu-latest
    needs: java-integration-tests
    continue-on-error: true  # Don't block on performance issues
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python 3.10
        uses: actions/setup-python@v3
        with:
          python-version: '3.10'
      
      - name: Run Performance Benchmarks
        run: |
          cd sink-connector/python
          pytest tests/performance/ -v --benchmark-only
      
      - name: Upload Performance Report
        uses: actions/upload-artifact@v3
        with:
          name: performance-report
          path: performance_report.html
  
  # Job 6: Test Summary
  test-summary:
    name: Generate Test Summary
    runs-on: ubuntu-latest
    needs: [mysql-regression-gate, python-unit-tests, postgres-integration-tests, java-integration-tests]
    if: always()
    
    steps:
      - name: Generate Summary
        run: |
          echo "# Test Execution Summary" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "## Results" >> $GITHUB_STEP_SUMMARY
          echo "- MySQL Regression: ${{ needs.mysql-regression-gate.result }}" >> $GITHUB_STEP_SUMMARY
          echo "- Python Unit Tests: ${{ needs.python-unit-tests.result }}" >> $GITHUB_STEP_SUMMARY
          echo "- PostgreSQL Integration: ${{ needs.postgres-integration-tests.result }}" >> $GITHUB_STEP_SUMMARY
          echo "- Java Integration: ${{ needs.java-integration-tests.result }}" >> $GITHUB_STEP_SUMMARY
```

### 15.2 Local Test Execution Script

**File**: [`scripts/run_all_tests.sh`](scripts/run_all_tests.sh)

```bash
#!/bin/bash
# Complete local test execution script

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'  # No Color

echo "========================================"
echo "PostgreSQL Connector - Complete Test Suite"
echo "========================================"

# Step 1: MySQL Regression Gate
echo -e "\n${YELLOW}Step 1: MySQL Regression Gate${NC}"
echo "Running all MySQL tests to ensure no regression..."

cd "$PROJECT_ROOT/sink-connector/tests/integration"
if pytest tests/ -m mysql -v --tb=short; then
    echo -e "${GREEN}✓ MySQL tests passed${NC}"
else
    echo -e "${RED}✗ MySQL tests FAILED - PostgreSQL changes broke MySQL!${NC}"
    exit 1
fi

# Step 2: Python Unit Tests
echo -e "\n${YELLOW}Step 2: Python Unit Tests${NC}"
cd "$PROJECT_ROOT/sink-connector/python"

pytest tests/unit/ \
    -v \
    --cov=db_load/postgres_parser \
    --cov=db_dump \
    --cov=db_compare \
    --cov-report=html \
    --cov-report=term \
    --cov-fail-under=80

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Python unit tests passed with ≥80% coverage${NC}"
else
    echo -e "${RED}✗ Python unit tests failed or coverage < 80%${NC}"
    exit 1
fi

# Step 3: Start Test Databases
echo -e "\n${YELLOW}Step 3: Starting Test Databases${NC}"
docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" up -d

echo "Waiting for PostgreSQL..."
until docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" exec -T postgres pg_isready; do
    sleep 1
done

echo "Waiting for ClickHouse..."
until docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" exec -T clickhouse wget --spider -q localhost:8123/ping; do
    sleep 1
done

echo -e "${GREEN}✓ Test databases ready${NC}"

# Step 4: Python Integration Tests
echo -e "\n${YELLOW}Step 4: Python Integration Tests${NC}"
cd "$PROJECT_ROOT/sink-connector/python"

pytest tests/integration/test_postgres_batch_dump.py -v

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Python integration tests passed${NC}"
else
    echo -e "${RED}✗ Python integration tests failed${NC}"
    docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" down
    exit 1
fi

# Step 5: Java Integration Tests
echo -e "\n${YELLOW}Step 5: Java Integration Tests${NC}"
cd "$PROJECT_ROOT/sink-connector-lightweight"

mvn test -Dtest=Postgres*IT

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Java integration tests passed${NC}"
else
    echo -e "${RED}✗ Java integration tests failed${NC}"
    docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" down
    exit 1
fi

# Step 6: Performance Tests (optional)
echo -e "\n${YELLOW}Step 6: Performance Tests (optional)${NC}"
read -p "Run performance tests? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cd "$PROJECT_ROOT/sink-connector/python"
    pytest tests/performance/ -v --benchmark-only
fi

# Cleanup
echo -e "\n${YELLOW}Cleaning up...${NC}"
docker-compose -f "$PROJECT_ROOT/docker-compose.test.yml" down -v

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}All tests passed successfully! ✓${NC}"
echo -e "${GREEN}========================================${NC}"
```

---

## 16. Test Data Management and Fixtures

### 16.1 Comprehensive Test Data Generator

**File**: [`sink-connector/python/tests/fixtures/postgres_test_data_generator.py`](sink-connector/python/tests/fixtures/postgres_test_data_generator.py)

```python
#!/usr/bin/env python3
"""
Comprehensive PostgreSQL test data generator.

Generates test data covering:
- All PostgreSQL data types
- Edge cases (min/max values, NULL, empty)
- Special characters and Unicode
- Large datasets for performance testing
"""

import psycopg2
import uuid
import random
import string
from datetime import datetime, timedelta
from decimal import Decimal

class PostgreSQLTestDataGenerator:
    
    def __init__(self, conn):
        self.conn = conn
        self.cursor = conn.cursor()
    
    def create_comprehensive_type_table(self):
        """Create table with all PostgreSQL types."""
        self.cursor.execute("""
            CREATE TABLE IF NOT EXISTS comprehensive_types (
                id SERIAL PRIMARY KEY,
                
                -- Integer types
                col_smallint SMALLINT,
                col_integer INTEGER,
                col_bigint BIGINT,
                col_serial SERIAL,
                col_bigserial BIGSERIAL,
                
                -- Numeric types
                col_numeric NUMERIC(21,5),
                col_decimal DECIMAL(10,2),
                col_real REAL,
                col_double DOUBLE PRECISION,
                col_money MONEY,
                
                -- String types
                col_varchar VARCHAR(255),
                col_char CHAR(10),
                col_text TEXT,
                
                -- Binary
                col_bytea BYTEA,
                
                -- Date/Time
                col_date DATE,
                col_time TIME,
                col_time_tz TIME WITH TIME ZONE,
                col_timestamp TIMESTAMP,
                col_timestamp_tz TIMESTAMP WITH TIME ZONE,
                col_interval INTERVAL,
                
                -- Boolean
                col_boolean BOOLEAN,
                
                -- UUID
                col_uuid UUID,
                
                -- JSON
                col_json JSON,
                col_jsonb JSONB,
                
                -- Arrays
                col_int_array INTEGER[],
                col_text_array TEXT[],
                col_uuid_array UUID[],
                
                -- Network types
                col_inet INET,
                col_cidr CIDR,
                col_macaddr MACADDR,
                
                -- Geometric types
                col_point POINT,
                col_line LINE,
                col_box BOX,
                col_circle CIRCLE,
                col_polygon POLYGON,
                
                -- Special types
                col_xml XML,
                col_bit BIT(8),
                col_varbit BIT VARYING(16)
            );
        """)
        self.conn.commit()
    
    def insert_normal_values(self):
        """Insert normal/typical values."""
        self.cursor.execute("""
            INSERT INTO comprehensive_types (
                col_smallint, col_integer, col_bigint,
                col_numeric, col_decimal, col_real, col_double, col_money,
                col_varchar, col_char, col_text,
                col_bytea,
                col_date, col_time, col_timestamp, col_timestamp_tz, col_interval,
                col_boolean,
                col_uuid,
                col_json, col_jsonb,
                col_int_array, col_text_array, col_uuid_array,
                col_inet, col_cidr, col_macaddr,
                col_point, col_box, col_circle,
                col_xml
            ) VALUES (
                100, 10000, 1000000000,
                12345.67890, 999.99, 3.14159, 3.141592653589793, 1234.56,
                'Normal text', 'ABCDE     ', 'This is a normal text field',
                E'\\\\xDEADBEEF',
                '2024-01-15', '14:30:00', '2024-01-15 14:30:00', '2024-01-15 14:30:00+00', '1 day 2 hours',
                TRUE,
                'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
                '{"key": "value"}', '{"nested": {"key": "value"}}',
                ARRAY[1,2,3], ARRAY['one','two','three'], ARRAY['550e8400-e29b-41d4-a716-446655440000']::UUID[],
                '192.168.1.1', '192.168.0.0/24', '08:00:2b:01:02:03',
                POINT(1.5, 2.5), BOX(POINT(0,0), POINT(1,1)), CIRCLE(POINT(0,0), 1),
                '<root><element>value</element></root>'
            );
        """)
        self.conn.commit()
    
    def insert_edge_case_values(self):
        """Insert edge case values (min, max, boundaries)."""
        self.cursor.execute("""
            INSERT INTO comprehensive_types (
                col_smallint, col_integer, col_bigint,
                col_numeric, col_date
            ) VALUES (
                32767, 2147483647, 9223372036854775807,
                99999999999999999.99999, '2099-12-31'
            );
        """)
        
        self.cursor.execute("""
            INSERT INTO comprehensive_types (
                col_smallint, col_integer, col_bigint,
                col_numeric, col_date
            ) VALUES (
                -32768, -2147483648, -9223372036854775808,
                -99999999999999999.99999, '1900-01-01'
            );
        """)
        self.conn.commit()
    
    def insert_null_values(self):
        """Insert row with NULL values."""
        self.cursor.execute("""
            INSERT INTO comprehensive_types (
                id, col_integer, col_varchar
            ) VALUES (
                DEFAULT, NULL, 'Only non-null field'
            );
        """)
        self.conn.commit()
    
    def insert_special_character_values(self):
        """Insert special characters and Unicode."""
        self.cursor.execute("""
            INSERT INTO comprehensive_types (
                col_varchar, col_text, col_jsonb
            ) VALUES (
                'O''Reilly & "quoted" text',
                E'Line1\\nLine2\\nLine3\\tTabbed',
                '{"unicode": "中文", "emoji": "😀🎉", "special": "\\u0000"}'
            );
        """)
        self.conn.commit()
    
    def generate_large_dataset(self, table_name, row_count=1000000):
        """Generate large dataset for performance testing."""
        print(f"Generating {row_count:,} rows in {table_name}...")
        
        # Create table
        self.cursor.execute(f"""
            CREATE TABLE IF NOT EXISTS {table_name} (
                id SERIAL PRIMARY KEY,
                uuid_col UUID,
                int_col INTEGER,
                text_col TEXT,
                numeric_col NUMERIC(10,2),
                timestamp_col TIMESTAMP WITH TIME ZONE,
                json_col JSONB
            );
        """)
        
        # Batch insert
        batch_size = 10000
        for i in range(0, row_count, batch_size):
            values = []
            for j in range(batch_size):
                if i + j >= row_count:
                    break
                
                values.append(
                    f"('{uuid.uuid4()}', {random.randint(1, 1000000)}, "
                    f"'Row {i+j}', {random.uniform(0, 10000):.2f}, "
                    f"'2024-01-01 00:00:00+00'::TIMESTAMPTZ + INTERVAL '{random.randint(0, 365)} days', "
                    f"'{{\\"id\\": {i+j}}}'::JSONB)"
                )
            
            query = f"""
                INSERT INTO {table_name}
                (uuid_col, int_col, text_col, numeric_col, timestamp_col, json_col)
                VALUES {','.join(values)}
            """
            self.cursor.execute(query)
            
            if (i + batch_size) % 100000 == 0:
                print(f"  {i + batch_size:,} rows generated...")
                self.conn.commit()
        
        self.conn.commit()
        print(f"✓ {row_count:,} rows generated successfully")

def main():
    # Connect to PostgreSQL
    conn = psycopg2.connect(
        host='localhost',
        port=5432,
        user='root',
        password='root',
        database='testdb'
    )
    
    generator = PostgreSQLTestDataGenerator(conn)
    
    # Generate comprehensive test data
    print("Creating comprehensive type table...")
    generator.create_comprehensive_type_table()
    
    print("Inserting normal values...")
    generator.insert_normal_values()
    
    print("Inserting edge cases...")
    generator.insert_edge_case_values()
    
    print("Inserting NULL values...")
    generator.insert_null_values()
    
    print("Inserting special characters...")
    generator.insert_special_character_values()
    
    # Generate large dataset
    print("\nGenerating large dataset for performance testing...")
    generator.generate_large_dataset('large_perf_test', row_count=1000000)
    
    conn.close()
    print("\n✓ All test data generated successfully")

if __name__ == '__main__':
    main()
```

---

## 17. Next Steps

1. Review this comprehensive testing strategy
2. Establish MySQL baseline (run all 127 tests, record results)
3. Set up test infrastructure (Docker, CI/CD)
4. Implement Python unit tests (80%+ coverage target)
5. Implement component tests (dumper, parser, loader)
6. Implement integration tests (end-to-end workflows)
7. Execute performance tests (1M+ row benchmarks)
8. Run MySQL regression suite (zero failures required)
9. Generate test reports and documentation
10. Proceed to [`03-implementation-phases.md`](plans/postgres/03-implementation-phases.md)
11. Review [`08-mysql-postgres-isolation-strategy.md`](plans/postgres/08-mysql-postgres-isolation-strategy.md)
12. Review [`09-detailed-test-specifications.md`](plans/postgres/09-detailed-test-specifications.md)

---

## Appendix A: Test File Structure

```
sink-connector/python/
├── tests/
│   ├── unit/
│   │   ├── test_postgres_type_conversion.py
│   │   ├── test_postgres_ddl_parser.py
│   │   └── test_postgres_checksum.py
│   ├── component/
│   │   ├── test_postgres_dumper.py
│   │   ├── test_postgres_loader.py
│   │   └── test_postgres_utilities.py
│   ├── integration/
│   │   ├── test_postgres_batch_dump.py
│   │   ├── test_postgres_cdc_operations.py
│   │   └── test_postgres_data_types.py
│   ├── performance/
│   │   ├── test_postgres_dump_performance.py
│   │   └── test_postgres_load_performance.py
│   └── data/
│       ├── generate_postgres_test_data.py
│       └── postgres_test_datasets.sql

sink-connector-lightweight/src/test/
├── java/com/altinity/clickhouse/debezium/embedded/
│   ├── PostgresBatchDumpIT.java
│   ├── PostgresInsertIT.java
│   ├── PostgresUpdateIT.java
│   ├── PostgresDeleteIT.java
│   ├── PostgresTruncateIT.java
│   ├── PostgresDDLIT.java
│   └── PostgresDataTypeCoverageIT.java
└── resources/
    ├── postgres_type_test_data.sql
    ├── postgres_large_dataset.sql
    └── postgres_edge_cases.sql
```

---

## Appendix B: Test Data Examples

See [`05-data-types-coverage.md`](plans/postgres/05-data-types-coverage.md) for comprehensive test data for all PostgreSQL data types.
