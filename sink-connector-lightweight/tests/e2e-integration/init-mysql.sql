-- ================================================================
-- MySQL Initialization Script for E2E Testing
-- ================================================================

-- Enable binary logging for CDC
SET GLOBAL binlog_format = 'ROW';
SET GLOBAL binlog_row_image = 'FULL';

-- Create test database
CREATE DATABASE IF NOT EXISTS testdb;
USE testdb;

-- Grant permissions
GRANT ALL PRIVILEGES ON testdb.* TO 'root'@'%';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'root'@'%';
FLUSH PRIVILEGES;

-- Create initial test table to verify connection
CREATE TABLE IF NOT EXISTS _health_check (
    id INT PRIMARY KEY AUTO_INCREMENT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO _health_check (id) VALUES (1);

SELECT 'MySQL initialized successfully' AS status;
