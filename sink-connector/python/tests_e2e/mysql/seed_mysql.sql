-- Seed of the Python toolset MySQL e2e suite (rendered by mysql_e2e_support.render_seed).
--
-- ${Y}, ${T}, ${N} are yesterday, today and tomorrow (YYYY-MM-DD) of the run and
-- ${YK}, ${TK}, ${NK} the same days as YYYYMMDD: the date-partitioned tables have one
-- RANGE COLUMNS partition per day named p<yyyymmdd>, as the scheduled checksum jobs
-- expect (--include_partitions_regex "p.*" --partition_date <day>). ${N} is empty.
-- ${Y3} is three days before ${Y} (a row of partition ${YK} outside its day), ${N2}
-- the upper bound of partition ${NK}.
-- ${BT_*} are bitemporal db_from values around the window the per-table where of
-- pyops.positions_bt opens for partition ${Y}: (${Y} - 1 day) 16:30
-- America/Chicago, written in UTC (the source time zone).
--
-- Shapes covered: keyless table, BINARY/VARBINARY/BLOB, BIT(1)/BIT(16),
-- DATETIME(6)/TIMESTAMP(6), DECIMAL, JSON (ignored column), FLOAT (not compared),
-- NULLs including a lower-case `null` column definition, a source column named
-- _sign, a nullable STORED generated column, generated bit-flag columns in a database the
-- connector does not replicate (pyflags, snapshot path only), and tables the jobs exclude (temp_*, heartbeat, *_p<digit>). The temp_*
-- tables also carry shapes that dedicated runs exercise: a `$` in a table name,
-- "checksum" in column names, a TO_DAYS() partition expression.

SET SESSION time_zone = '+00:00';
-- DESTRUCTIVE: drops the suite's own three databases on the disposable e2e MySQL (compose or
-- external test stack) so the seed is reproducible; no other database is touched.
DROP DATABASE IF EXISTS pyops;
DROP DATABASE IF EXISTS pyref;
DROP DATABASE IF EXISTS pyflags;
CREATE DATABASE pyops;
CREATE DATABASE pyref;
CREATE DATABASE pyflags;
USE pyops;

-- ---------------------------------------------------------------- date-partitioned tables
CREATE TABLE fills (
  id BIGINT NOT NULL,
  trade_date DATE NOT NULL,
  account VARCHAR(32) NOT NULL,
  qty DECIMAL(18,4) NOT NULL,
  price DECIMAL(12,6) NULL,
  side BIT(1) NOT NULL,
  flags BIT(16) NULL,
  venue_id VARBINARY(16) NULL,
  executed_at DATETIME(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  note VARCHAR(64) NULL,
  PRIMARY KEY (id, trade_date)
) PARTITION BY RANGE COLUMNS (trade_date) (
  PARTITION p${YK} VALUES LESS THAN ('${T}'),
  PARTITION p${TK} VALUES LESS THAN ('${N}'),
  PARTITION p${NK} VALUES LESS THAN ('${N2}')
);
INSERT INTO fills VALUES
  (1, '${Y}', 'ACC-1', 100.0000, 10.500000, b'1', b'1010101111001101', X'00FF10A0', '${Y} 09:30:00.000001', '${Y} 14:30:00.123456', 'first'),
  (2, '${Y}', 'ACC-1', -25.5000, 10.250000, b'0', NULL, X'', '${Y} 09:31:00.500000', '${Y} 14:31:00.000000', NULL),
  (3, '${Y}', 'ACC-2', 0.0001, NULL, b'1', b'0000000000000001', NULL, '${Y} 15:59:59.999999', '${Y} 20:59:59.999999', ''),
  (4, '${Y}', 'ACC-3', 1234567.8900, 0.000001, b'0', b'1111111111111111', X'FFFFFFFF', '${Y} 23:59:59.000000', '${T} 04:59:59.000000', 'late'),
  (5, '${Y}', 'ACC-3', 7.0000, 99.990000, b'1', b'0000000000000000', X'0102', '${Y} 00:00:00.000000', '${Y} 05:00:00.000000', 'midnight'),
  (6, '${T}', 'ACC-1', 10.0000, 11.000000, b'1', NULL, X'AB', '${T} 09:30:00.000000', '${T} 14:30:00.000000', 'today'),
  (7, '${T}', 'ACC-2', 20.0000, 11.500000, b'0', b'0000000011111111', X'CD', '${T} 10:00:00.250000', '${T} 15:00:00.250000', NULL),
  (8, '${T}', 'ACC-4', 30.0000, NULL, b'1', NULL, NULL, '${T} 11:11:11.111111', '${T} 16:11:11.111111', 'x'),
  (9, '${Y3}', 'ACC-9', 1.0000, 1.000000, b'0', NULL, X'09', '${Y3} 12:00:00.000000', '${Y3} 17:00:00.000000', 'older');

-- the bitemporal table (not named "...bitemporal": the jobs' exclude regex "temp" would drop it)
CREATE TABLE positions_bt (
  position_id INT NOT NULL,
  trade_date DATE NOT NULL,
  db_from DATETIME(6) NOT NULL,
  db_to DATETIME(6) NOT NULL,
  quantity DECIMAL(18,2) NOT NULL,
  book VARCHAR(16) NULL,
  PRIMARY KEY (position_id, trade_date, db_from)
) PARTITION BY RANGE COLUMNS (trade_date) (
  PARTITION p${YK} VALUES LESS THAN ('${T}'),
  PARTITION p${TK} VALUES LESS THAN ('${N}'),
  PARTITION p${NK} VALUES LESS THAN ('${N2}')
);
INSERT INTO positions_bt VALUES
  (1, '${Y}', '${BT_INSIDE_FIRST}', '2299-12-31 23:59:59.000000', 100.00, 'A'),
  (2, '${Y}', '${BT_INSIDE_LATER}', '2299-12-31 23:59:59.000000', 250.50, NULL),
  (3, '${Y}', '${Y} 10:00:00.000000', '2299-12-31 23:59:59.000000', -10.00, 'B'),
  (4, '${Y}', '${BT_OUTSIDE_LAST}', '${BT_INSIDE_FIRST}', 99.00, 'A'),
  (5, '${Y}', '${BT_OUTSIDE_UTC_WALL}', '${BT_INSIDE_LATER}', 1.00, 'C'),
  (6, '${T}', '${T} 01:00:00.000000', '2299-12-31 23:59:59.000000', 5.00, 'A');

-- every row is in partition ${T}: the scheduled run for ${Y} compares an EMPTY partition
CREATE TABLE quotes (
  id INT NOT NULL,
  quote_date DATE NOT NULL,
  bid DECIMAL(10,4) NULL,
  ask DECIMAL(10,4) NULL,
  src BINARY(4) NULL,
  PRIMARY KEY (id, quote_date)
) PARTITION BY RANGE COLUMNS (quote_date) (
  PARTITION p${YK} VALUES LESS THAN ('${T}'),
  PARTITION p${TK} VALUES LESS THAN ('${N}'),
  PARTITION p${NK} VALUES LESS THAN ('${N2}')
);
INSERT INTO quotes VALUES
  (1, '${T}', 1.2500, 1.2600, X'51554F31'),
  (2, '${T}', NULL, 2.0000, NULL);

-- excluded by the jobs ("temp"): a partitioned table the partitioned run would otherwise pick
CREATE TABLE temp_x (
  id INT NOT NULL,
  d DATE NOT NULL,
  v VARCHAR(10) NULL,
  PRIMARY KEY (id, d)
) PARTITION BY RANGE COLUMNS (d) (
  PARTITION p${YK} VALUES LESS THAN ('${T}'),
  PARTITION p${TK} VALUES LESS THAN ('${N}')
);
INSERT INTO temp_x VALUES (1, '${Y}', 'a'), (2, '${T}', 'b');

-- a function partition expression (TO_DAYS); "temp" keeps it out of the scheduled runs,
-- the expression is exercised by a dedicated run
CREATE TABLE temp_events_by_days (
  id INT NOT NULL,
  ev_date DATE NOT NULL,
  payload VARCHAR(32) NULL,
  PRIMARY KEY (id, ev_date)
) PARTITION BY RANGE (TO_DAYS(ev_date)) (
  PARTITION p${YK} VALUES LESS THAN (TO_DAYS('${T}')),
  PARTITION p${TK} VALUES LESS THAN (TO_DAYS('${N}'))
);
INSERT INTO temp_events_by_days VALUES (1, '${Y}', 'one'), (2, '${Y}', NULL), (3, '${T}', 'three');

-- ---------------------------------------------------------------- non-partitioned tables
CREATE TABLE instruments (
  id INT NOT NULL PRIMARY KEY,
  symbol VARCHAR(32) NOT NULL,
  isin CHAR(12) null,
  code4 BINARY(4) NULL,
  token VARBINARY(32) NULL,
  blob_data BLOB NULL,
  active BIT(1) NOT NULL,
  mask BIT(16) NULL,
  listed_at DATETIME(6) NULL,
  updated_at TIMESTAMP(6) NULL,
  tick DECIMAL(12,6) NULL,
  ratio FLOAT NULL,
  attrs JSON NULL,
  comment_text TEXT NULL
);
INSERT INTO instruments VALUES
  (1, 'AAPL', 'US0378331005', X'00010203', X'DEADBEEF', REPEAT(X'00FF10', 70), b'1', b'1010101111001101', '2020-01-02 03:04:05.678901', '2024-06-30 23:59:59.999999', 0.010000, 0.5, '{"a": 1.0, "b": [1, 2]}', 'plain'),
  (2, 'MSFT', NULL, NULL, NULL, NULL, b'0', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  (3, '', '', X'FFFFFFFF', X'', X'', b'1', b'0000000000000000', '1950-06-15 12:00:00.250000', '1970-01-01 00:00:01.000000', -0.500000, 1.25, '{}', ''),
  (4, 'ES=F', 'XX0000000004', X'41424344', X'00', X'0A0D09', b'0', b'1111111111111111', '2299-12-31 23:59:59.000000', '2038-01-19 03:14:07.000000', 123456.123456, NULL, '[]', 'multi\nline'),
  (5, 'unicode', 'ÄÖÜ000000005', X'7F800001', REPEAT(X'AB', 32), REPEAT('x', 1000), b'1', b'0000000100000001', '2026-01-01 00:00:00.000000', '2026-03-08 08:00:00.000000', 1.500000, 3.75, '{"k": "v"}', 'ünïcödé');

-- keyless: no PRIMARY KEY and no UNIQUE key (the server would otherwise add an
-- invisible primary key, sql_generate_invisible_primary_key=1 in the compose MySQL)
SET SESSION sql_generate_invisible_primary_key = 0;
CREATE TABLE keyless_events (
  event_time DATETIME(6) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  amount DECIMAL(10,2) NULL,
  raw VARBINARY(8) NULL
);
SET SESSION sql_generate_invisible_primary_key = 1;
INSERT INTO keyless_events VALUES
  ('${Y} 01:00:00.000000', 'open', 1.00, X'01'),
  ('${Y} 01:00:00.000000', 'open', 2.00, X'02'),
  ('${Y} 02:00:00.000000', 'close', NULL, NULL),
  ('${Y} 03:00:00.000000', 'adjust', -3.50, X''),
  ('${T} 01:00:00.000000', 'open', 1.00, X'01'),
  ('${T} 04:00:00.000001', 'close', 0.00, X'FF00');

-- a source column named _sign (a connector bookkeeping name)
CREATE TABLE ledger (
  id INT NOT NULL PRIMARY KEY,
  _sign TINYINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL
);
INSERT INTO ledger VALUES (1, 1, 10.00), (2, -1, 20.00), (3, 0, 30.00);

-- a `$` in the table name ("temp": exercised by a dedicated run, outside the jobs)
CREATE TABLE `temp_fx$rates` (
  ccy CHAR(3) NOT NULL PRIMARY KEY,
  rate DECIMAL(18,8) NOT NULL
);
INSERT INTO `temp_fx$rates` VALUES ('EUR', 1.07500000), ('JPY', 0.00670000);

-- "checksum" in column names ("temp": exercised by a dedicated run, outside the jobs)
CREATE TABLE temp_checksum_named (
  id INT NOT NULL PRIMARY KEY,
  checksum_note VARCHAR(32) NULL,
  checksum_ratio FLOAT NULL
);
INSERT INTO temp_checksum_named VALUES (1, 'checksum for table x', 0.5), (2, NULL, NULL);

-- excluded by the non-partitioned job ("_p[0-9]")
CREATE TABLE fills_p1 (
  id INT NOT NULL PRIMARY KEY,
  v VARCHAR(10) NULL
);
INSERT INTO fills_p1 VALUES (1, 'archived');

-- excluded by both jobs ("heartbeat"); the suite writes to it to move the binlog
CREATE TABLE heartbeat (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  note VARCHAR(32) NULL,
  ts TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
INSERT INTO heartbeat (note) VALUES ('seed');

-- the resync guard scenarios write only to this table ("temp": outside the jobs)
CREATE TABLE temp_resync_guard (
  id INT NOT NULL PRIMARY KEY,
  v VARCHAR(16) NOT NULL
);
INSERT INTO temp_resync_guard VALUES (1, 'one'), (2, 'two'), (3, 'three');

-- ---------------------------------------------------------------- second database
-- written to ClickHouse as pyref_ch (clickhouse.database.override.map)
USE pyref;
CREATE TABLE accounts (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  balance DECIMAL(20,2) NULL,
  opened DATE NULL,
  updated_at TIMESTAMP(6) NULL
);
INSERT INTO accounts VALUES
  (1, 'alpha', 1000.00, '2001-02-03', '${Y} 12:00:00.000000'),
  (2, 'beta', NULL, NULL, NULL),
  (3, 'gamma', -0.01, '1999-12-31', '${T} 00:00:00.000001');

-- columns MySQL declares nullable that never hold NULL: a STORED generated column (no NOT
-- NULL, so IS_NULLABLE = 'YES') and an explicitly nullable varchar. test_mysql_06 declares
-- their ClickHouse twins non-Nullable, as hand-written replica DDL does (spec 13.06 D-13.06-40).
CREATE TABLE position_flags (
  id INT NOT NULL PRIMARY KEY,
  qty INT NOT NULL,
  label VARCHAR(16) NULL,
  is_valid TINYINT(1) GENERATED ALWAYS AS (qty > 0) STORED
);
INSERT INTO position_flags (id, qty, label) VALUES
  (1, 10, 'alpha'),
  (2, 0, 'beta'),
  (3, -5, 'gamma'),
  (4, 7, ''),
  (5, 1, 'epsilon');

CREATE TABLE daily_marks (
  instrument_id INT NOT NULL,
  mark_date DATE NOT NULL,
  mark DECIMAL(18,6) NOT NULL,
  src VARBINARY(8) NULL,
  PRIMARY KEY (instrument_id, mark_date)
) PARTITION BY RANGE COLUMNS (mark_date) (
  PARTITION p${YK} VALUES LESS THAN ('${T}'),
  PARTITION p${TK} VALUES LESS THAN ('${N}'),
  PARTITION p${NK} VALUES LESS THAN ('${N2}')
);
INSERT INTO daily_marks VALUES
  (1, '${Y}', 101.250000, X'4D31'),
  (2, '${Y}', 99.000000, NULL),
  (1, '${T}', 102.000000, X'4D32');

-- ---------------------------------------------------------------- third database, not replicated
-- Generated columns over a bit-flag column (two VIRTUAL, one STORED). The connector copies a generated
-- expression verbatim and ClickHouse has no & or << operators, so it cannot create this table; pyflags is
-- outside its database.include.list and only the snapshot path (dump, load, checksum) uses it
-- (test_mysql_03_snapshot, spec 13.04 D-13.04-33).
USE pyflags;
CREATE TABLE trade_flags (
  id INT NOT NULL PRIMARY KEY,
  attrs BIGINT DEFAULT '0',
  is_reversal INT GENERATED ALWAYS AS (((attrs & (1 << 0)) > 0)) VIRTUAL,
  is_pending INT GENERATED ALWAYS AS (((attrs & (1 << 2)) > 0)) VIRTUAL,
  is_manual TINYINT(1) GENERATED ALWAYS AS (((attrs & (1 << 4)) > 0)) STORED
);
INSERT INTO trade_flags (id, attrs) VALUES (1, 5), (2, NULL), (3, 16), (4, -1), (5, 0), (6, 21);
