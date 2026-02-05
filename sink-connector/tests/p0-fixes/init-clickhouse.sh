#!/bin/bash
# Initialize ClickHouse for P0 bug testing

set -e

# Wait for ClickHouse to be ready
sleep 5

# Create test database
clickhouse-client --query "CREATE DATABASE IF NOT EXISTS testdb"

# Create test tables matching MySQL schema

# Table for NULL handling tests (BUG-DATA-1)
clickhouse-client --query "
CREATE TABLE IF NOT EXISTS testdb.test_null_handling (
    id Int32,
    required_field String,
    optional_field Nullable(String),
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY id
"

# Table for binary data encoding tests (BUG-DATA-6)
clickhouse-client --query "
CREATE TABLE IF NOT EXISTS testdb.test_binary_data (
    id Int32,
    binary_col String,
    varbinary_col String,
    blob_col String,
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY id
"

# Table for concurrency tests (BUG-CONC-1, BUG-CONC-4, BUG-CONC-5)
clickhouse-client --query "
CREATE TABLE IF NOT EXISTS testdb.test_concurrency (
    id Int32,
    database_name String,
    table_name String,
    thread_id Int32,
    operation String,
    timestamp Int64,
    data String
) ENGINE = MergeTree()
ORDER BY (id, timestamp)
"

# Table for schema evolution tests (BUG-CONC-2)
clickhouse-client --query "
CREATE TABLE IF NOT EXISTS testdb.test_schema_evolution (
    id Int32,
    name String,
    email String,
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY id
"

# Table for multi-database tests
clickhouse-client --query "
CREATE TABLE IF NOT EXISTS testdb.test_multi_db (
    id Int32,
    value String,
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY id
"

# Show created tables
clickhouse-client --query "SHOW TABLES FROM testdb"

echo "ClickHouse initialization complete"
