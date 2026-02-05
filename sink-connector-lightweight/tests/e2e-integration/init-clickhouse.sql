-- ================================================================
-- ClickHouse Initialization Script for E2E Testing
-- ================================================================

-- Create test database
CREATE DATABASE IF NOT EXISTS testdb;

-- Create health check table
CREATE TABLE IF NOT EXISTS testdb._health_check (
    id Int32,
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY id;

SELECT 'ClickHouse initialized successfully' AS status;
