-- =============================================================================
-- PostgreSQL DDL Test Fixture
-- Used by PostgresDDLCdcE2EIT
-- =============================================================================

-- Initial table for DDL operations
CREATE TABLE IF NOT EXISTS ddl_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    value INTEGER
);

INSERT INTO ddl_test VALUES (1, 'initial', 100);
INSERT INTO ddl_test VALUES (2, 'setup', 200);
