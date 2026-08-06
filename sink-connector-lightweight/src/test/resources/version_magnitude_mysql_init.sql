CREATE DATABASE IF NOT EXISTS magnitude_db;
USE magnitude_db;

CREATE TABLE items (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL
) ENGINE=InnoDB;

-- Seed data matching the pre-loaded ClickHouse rows
INSERT INTO items VALUES (1, 'item_1'), (2, 'item_2'), (3, 'item_3');
