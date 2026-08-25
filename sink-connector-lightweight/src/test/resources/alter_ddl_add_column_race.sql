CREATE DATABASE IF NOT EXISTS employees;
USE employees;

CREATE TABLE race_test (
    id INT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

-- Seed row to trigger snapshot replication and prime the DbWriter cache
INSERT INTO race_test (id, name) VALUES (0, 'seed');
