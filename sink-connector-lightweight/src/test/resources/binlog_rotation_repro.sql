CREATE DATABASE IF NOT EXISTS rotation_db;
USE rotation_db;

CREATE TABLE target (
  id INT NOT NULL PRIMARY KEY,
  data VARCHAR(64) NOT NULL DEFAULT 'seed'
) ENGINE=InnoDB;

-- Padding table used to generate binlog traffic and force rotation
CREATE TABLE padding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payload VARCHAR(255)
) ENGINE=InnoDB;

-- Seed row that will be deleted and re-inserted across a binlog rotation boundary
INSERT INTO target (id, data) VALUES (1, 'original');
