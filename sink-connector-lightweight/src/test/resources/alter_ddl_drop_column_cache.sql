CREATE DATABASE IF NOT EXISTS employees;
USE employees;

-- Create table with columns that will be modified during the test
CREATE TABLE cache_test (
    id INT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    age INT
) ENGINE=InnoDB;

-- Insert initial row to trigger replication and populate the cache
INSERT INTO cache_test (id, name, email, age) VALUES (1, 'John Doe', 'john@example.com', 30);
