-- ClickHouse initialization script for E2E testing

-- Create database
CREATE DATABASE IF NOT EXISTS e2e_testdb;

-- Create custom function for decimal formatting (required by checksum scripts)
CREATE OR REPLACE FUNCTION format_decimal AS (x, scale) -> 
    if(
        locate(toString(x),'.') > 0,
        concat(toString(x), repeat('0', toUInt8(scale - (length(toString(x)) - locate(toString(x),'.'))))),
        concat(toString(x), if(scale = 0, '', '.'), repeat('0', toUInt8(scale)))
    );

SELECT 'ClickHouse initialized successfully' AS status;
