-- MySQL init script for the delete+reinsert offset-rewind reproduction.
-- Creates the target table (bulk-deleted then re-inserted) and a noise table
-- used to widen the binlog gap between the DELETE and the re-INSERT so the
-- connector can be stopped after the redelivered DELETEs but before the
-- re-INSERTs.
CREATE DATABASE IF NOT EXISTS repro_db;

USE repro_db;

CREATE TABLE target_table (
  id INT NOT NULL PRIMARY KEY,
  data VARCHAR(64) NOT NULL,
  version_num INT NOT NULL DEFAULT 1
);

CREATE TABLE noise (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payload VARCHAR(64)
);
