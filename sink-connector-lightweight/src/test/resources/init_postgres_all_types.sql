-- =============================================================================
-- PostgreSQL All Data Types Test Fixture
-- Used by PostgresDataTypesE2EIT and PostgresSnapshotInitialE2EIT
-- =============================================================================

-- Numeric types
CREATE TABLE IF NOT EXISTS all_types_test (
    id SERIAL PRIMARY KEY,
    -- Integer types
    col_smallint SMALLINT,
    col_integer INTEGER,
    col_bigint BIGINT,
    -- Decimal types
    col_decimal DECIMAL(18,6),
    col_numeric NUMERIC(20,8),
    col_real REAL,
    col_double DOUBLE PRECISION,
    -- Boolean
    col_boolean BOOLEAN,
    -- String types
    col_varchar VARCHAR(255),
    col_char CHAR(10),
    col_text TEXT,
    -- Binary
    col_bytea BYTEA,
    -- Date/Time types
    col_date DATE,
    col_time TIME,
    col_timetz TIME WITH TIME ZONE,
    col_timestamp TIMESTAMP,
    col_timestamptz TIMESTAMP WITH TIME ZONE,
    col_interval INTERVAL,
    -- UUID
    col_uuid UUID,
    -- JSON
    col_json JSON,
    col_jsonb JSONB,
    -- Network
    col_inet INET,
    col_cidr CIDR,
    col_macaddr MACADDR,
    -- Arrays
    col_int_array INTEGER[],
    col_text_array TEXT[],
    -- Range types
    col_int4range INT4RANGE,
    col_int8range INT8RANGE,
    col_numrange NUMRANGE,
    col_tsrange TSRANGE,
    col_tstzrange TSTZRANGE,
    col_daterange DATERANGE
);

-- Row 1: Normal values
INSERT INTO all_types_test VALUES (
    1,
    32767, 2147483647, 9223372036854775807,
    123456.789012, 12345678901234.56789012, 3.14, 2.718281828459045,
    true,
    'Hello World', 'ABCDEFGHIJ', 'Lorem ipsum dolor sit amet',
    E'\\xDEADBEEF',
    '2024-01-15', '14:30:00', '14:30:00+05:30', '2024-01-15 14:30:00', '2024-01-15 14:30:00+00', '1 year 2 months 3 days',
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    '{"key": "value", "number": 42}', '{"nested": {"array": [1,2,3]}}',
    '192.168.1.1', '10.0.0.0/8', '08:00:2b:01:02:03',
    '{1,2,3,4,5}', '{"hello","world"}',
    '[1,10)', '[100,200)', '[1.5,2.5)', '[2024-01-01,2024-12-31)', '[2024-01-01 00:00:00+00,2024-12-31 23:59:59+00)', '[2024-01-01,2024-12-31)'
);

-- Row 2: NULL values
INSERT INTO all_types_test (id) VALUES (2);

-- Row 3: Edge case / boundary values
INSERT INTO all_types_test VALUES (
    3,
    -32768, -2147483648, -9223372036854775808,
    -999999.999999, -99999999999999.99999999, -3.14, -2.718281828459045,
    false,
    '', '          ', '',
    E'\\x00',
    '1970-01-01', '00:00:00', '00:00:00+00', '1970-01-01 00:00:00', '1970-01-01 00:00:00+00', '0 seconds',
    '00000000-0000-0000-0000-000000000000',
    '{}', '[]',
    '0.0.0.0', '0.0.0.0/0', '00:00:00:00:00:00',
    '{}', '{}',
    'empty', 'empty', 'empty', 'empty', 'empty', 'empty'
);

-- =============================================================================
-- Additional table for snapshot row-count verification
-- =============================================================================
CREATE TABLE IF NOT EXISTS snapshot_test (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    value INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

INSERT INTO snapshot_test (name, value) VALUES
    ('row1', 100),
    ('row2', 200),
    ('row3', 300),
    ('row4', 400),
    ('row5', 500);

-- =============================================================================
-- Additional table for multi-table snapshot verification
-- =============================================================================
CREATE TABLE IF NOT EXISTS snapshot_secondary (
    id SERIAL PRIMARY KEY,
    description TEXT,
    amount NUMERIC(15,2),
    active BOOLEAN DEFAULT true
);

INSERT INTO snapshot_secondary (description, amount, active) VALUES
    ('First entry', 1000.50, true),
    ('Second entry', 2500.75, false),
    ('Third entry', 99.99, true);
