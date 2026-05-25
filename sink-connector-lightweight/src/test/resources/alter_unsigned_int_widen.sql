-- Reproduces production scenario: pos_agg_id SMALLINT UNSIGNED widened to BIGINT UNSIGNED.
-- See AlterUnsignedIntWidenIT for ALTER + post-widen INSERT validation.

CREATE TABLE unsigned_int_widen_test (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  pos_agg_id SMALLINT UNSIGNED NOT NULL,
  counter INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO unsigned_int_widen_test (name, pos_agg_id, counter) VALUES
  ('row_before_alter_1', 100, 1),
  ('row_before_alter_2', 65535, 2);
