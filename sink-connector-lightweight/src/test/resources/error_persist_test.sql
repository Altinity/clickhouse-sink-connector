CREATE DATABASE IF NOT EXISTS employees;
USE employees;

-- Table whose ClickHouse counterpart is pre-created with an incompatible schema
-- (name mapped to Int32) so that replicating a non-numeric value fails and the
-- connector logs the failure to the error table.
CREATE TABLE error_persist_test (
    id INT NOT NULL PRIMARY KEY,
    name VARCHAR(100),
    amount INT
) ENGINE=InnoDB;
