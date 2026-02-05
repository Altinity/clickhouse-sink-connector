-- Initialize MySQL database for P0 concurrency and data type bug testing

-- Create test database
CREATE DATABASE IF NOT EXISTS testdb;
USE testdb;

-- Grant permissions for Debezium user
GRANT ALL PRIVILEGES ON testdb.* TO 'debezium'@'%';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;

-- Test table for BUG-DATA-1: NULL handling
CREATE TABLE test_null_handling (
    id INT PRIMARY KEY AUTO_INCREMENT,
    required_field VARCHAR(255) NOT NULL,
    optional_field VARCHAR(255) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Test table for BUG-DATA-6: Binary data encoding
CREATE TABLE test_binary_data (
    id INT PRIMARY KEY AUTO_INCREMENT,
    binary_col BINARY(16),
    varbinary_col VARBINARY(255),
    blob_col BLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Test table for concurrency testing
CREATE TABLE test_concurrency (
    id INT PRIMARY KEY AUTO_INCREMENT,
    database_name VARCHAR(100),
    table_name VARCHAR(100),
    thread_id INT,
    operation VARCHAR(50),
    timestamp BIGINT,
    data TEXT
) ENGINE=InnoDB;

-- Test table for schema evolution (BUG-CONC-2)
CREATE TABLE test_schema_evolution (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255),
    email VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Test table for multi-database scenario (BUG-CONC-1, BUG-CONC-5)
CREATE TABLE test_multi_db (
    id INT PRIMARY KEY AUTO_INCREMENT,
    value VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Insert initial test data for NULL handling
INSERT INTO test_null_handling (required_field, optional_field) VALUES
    ('value1', 'optional1'),
    ('value2', NULL),
    ('value3', 'optional3');

-- Insert initial test data for binary encoding
INSERT INTO test_binary_data (binary_col, varbinary_col, blob_col) VALUES
    (0x8F4B2C1A9D3E5F7B12345678, 0x0102030405, 0xDEADBEEF),
    (0x1122334455667788, 0xAABBCCDD, 0xCAFEBABE);

-- Insert initial test data for concurrency
INSERT INTO test_concurrency (database_name, table_name, thread_id, operation, timestamp, data) VALUES
    ('db1', 'table1', 1, 'INSERT', UNIX_TIMESTAMP(), 'data1'),
    ('db2', 'table2', 2, 'INSERT', UNIX_TIMESTAMP(), 'data2'),
    ('db1', 'table3', 1, 'UPDATE', UNIX_TIMESTAMP(), 'data3');

-- Insert initial test data for schema evolution
INSERT INTO test_schema_evolution (name, email) VALUES
    ('Alice', 'alice@example.com'),
    ('Bob', 'bob@example.com'),
    ('Charlie', 'charlie@example.com');

-- Insert initial test data for multi-database
INSERT INTO test_multi_db (value) VALUES
    ('value1'),
    ('value2'),
    ('value3');

-- Show tables
SHOW TABLES;

-- Display counts
SELECT 'test_null_handling' as table_name, COUNT(*) as count FROM test_null_handling
UNION ALL
SELECT 'test_binary_data', COUNT(*) FROM test_binary_data
UNION ALL
SELECT 'test_concurrency', COUNT(*) FROM test_concurrency
UNION ALL
SELECT 'test_schema_evolution', COUNT(*) FROM test_schema_evolution
UNION ALL
SELECT 'test_multi_db', COUNT(*) FROM test_multi_db;
