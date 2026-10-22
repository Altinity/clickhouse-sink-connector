# Known limitations

Source configurations and operations the lightweight connector does not support, or handles in a way an operator
must know about. Each entry says what happens and how to repair the replica. The spec for each is referenced; the
consolidated operator runbook is spec 10.08 (`specs/10-resilience-monitoring/`).

## MySQL `binlog_row_value_options=PARTIAL_JSON` is not supported

With `binlog_row_value_options=PARTIAL_JSON`, MySQL logs an UPDATE of a JSON column as a partial update event
(`PARTIAL_UPDATE_ROWS`, shown as `Update_rows_partial` by `SHOW BINLOG EVENTS`) that carries only the JSON
diff. Debezium has no handler for that event type, and the other columns changed by the same UPDATE statement
are lost with it. This happens with and without `binlog_transaction_compression`.

The connector does not replicate such an update, so it refuses the configuration in two places:

- **At start, and before every in-process engine restart**: the connector refuses to start when the server's
  global `binlog_row_value_options` contains `PARTIAL_JSON`, or when that value cannot be read.
- **At the first partial update event it reads, from any session**: the connector logs an ERROR naming the
  table and the binlog position, and stops replication. The process exits with code 3 unless
  `exit.on.terminal.failure=false`. The start check cannot see a session's setting, so this event check is
  the guard for that case.

Requirement: run the server the connector reads with `binlog_row_value_options=''` (the MySQL default):
`SET PERSIST binlog_row_value_options = '';` and make sure no application sets it per session.
Check with `SELECT @@GLOBAL.binlog_row_value_options;`.

A MySQL replica re-logs what it applies with its own `binlog_row_value_options`. A connector that reads a
replica running with `''` receives full row images even when the primary logs partial updates, so the
requirement applies to the server the connector reads, not to the primary.

Repair after partial updates were logged: set the value empty as above, re-synchronise the affected tables
from MySQL (`ch-mysql-resync`, spec 11.04), and restart the connector.

Spec 01.10 section 3.1. Setting `binlog.row.image.check.skip=true` lets the start check pass; the event check
still stops replication at the first partial update event.

## MySQL before 9.6 does not binlog the rows a foreign-key cascade changes

With an `ON DELETE CASCADE` or `ON UPDATE CASCADE` foreign key, MySQL before 9.6 logs the parent change but not
the child rows the cascade removes or re-keys. The connector replicates only logged changes, so the child table
in ClickHouse keeps rows that MySQL no longer has. Verified on MySQL 8.0.46: one deleted parent left two child
rows in ClickHouse.

- **At start**: on MySQL below 9.6, the connector logs a WARN banner that names every captured child table with a
  cascading foreign key, the constraint and its rules. Nothing is refused, and replication continues.
- **Version note**: MySQL 9.6 adds the global variable `innodb_native_foreign_keys` (not dynamic). Its effect on
  cascade binlogging has not been verified here; check the 9.6 reference manual before relying on it.
- **Remedy**: upgrade the source to MySQL 9.6 or later, remove the cascading constraint and enforce the rule in the
  application, or re-synchronise the child table after each cascading change (`ch-mysql-resync`, spec 11.04).

Spec 01.10 section 3.4.

## Session `sql_log_bin=0` writes are never replicated

A session that sets `sql_log_bin=0` writes rows the binary log does not record, so the connector never sees them
and the table diverges. Verified on MySQL 8.0 (one missing row and one changed value). The connector does not
detect this at run time; only a comparison of source and ClickHouse rows shows it.

- **Remedy**: keep `sql_log_bin` on, the MySQL default, in every session that writes a replicated table. Re-synchronise
  any table written while it was off.

Spec 01.10 section 3.5.

## DROP and TRUNCATE PARTITION on a captured table are ignored by design

`ALTER TABLE t DROP PARTITION p` and `ALTER TABLE t TRUNCATE PARTITION p` remove rows on the source. The connector
does not run them in ClickHouse, so the rows stay there. This is deliberate. For a captured table the connector logs
a WARN that names the table and the partition, and replication continues. A partition operation on an uncaptured
table is silent.

- **Remedy**: if the rows must go in ClickHouse too, delete them there with a predicate that matches the partition, or
  re-synchronise the table (`ch-mysql-resync`, spec 11.04).

Spec 06.08 section 3.3.

## A primary-key change on the source is applied as a delete and an insert, and is not logged

An UPDATE that changes a primary-key column of a captured table is replicated as a tombstone for the old key and
an insert of the row at the new key. Measured on MySQL 8.0.46 (key `1` changed to `10`): the final state of the
ClickHouse table matched MySQL, with no differences. The connector logs nothing for the key change. It is not
refused.

- **Scope**: measured in standard mode. Replication-history mode keeps its own history for the row and was not
  measured for this case.
- **Remedy**: none needed when the final state is the goal. If the old key must stay visible in history, use
  replication-history mode and check the history rows after the change.

## ADD COLUMN on the source while the connector runs is picked up without a restart

Measured on MySQL 8.0.46 and ClickHouse 24.8: the connector reads the
`ALTER TABLE ... ADD COLUMN` from the binlog, adds the column in ClickHouse, rebuilds its writer schema, and writes
the rows from the ALTER onward with the new column. The log shows `Built DbWriter schema ... at cache version 2`
and `All added columns are now visible`. No restart was needed on this path.

To verify which columns each INSERT used, run `SYSTEM FLUSH LOGS` on ClickHouse, then read `system.query_log`
for `query_kind = 'Insert'` rows whose `tables` contain the table. The column list is the text between the first
pair of parentheses of `query`. The INSERTs written after the ALTER must list the new column.

- **Not measured**: a column added to the ClickHouse table by hand, without a source DDL, and a source ALTER made
  while the connector is stopped. Check both on a test replica before relying on them.

## OPEN: a redelivered row after a crash may be written below its first delivery (not reproduced)

After a crash, the connector restarts from its last stored offset and receives again the records after it. Each
delivery gets a version that decides which copy of a row is the newer one. The design requires that a redelivered record never ranks
below its first delivery. This was tested end to end: a process kill (SIGKILL) and blocked ClickHouse inserts, with
and without a changed value. In every run the final state matched MySQL.

The failure has not been produced. It needs all three of these to hold at once, and no test run met all three:

1. the redelivered record is replayed at a binlog position earlier than a record the same connector process had
   already delivered;
2. the first delivery of that record was lost before it reached ClickHouse;
3. the replayed content differs from the copy stored before the crash.

Until a run shows otherwise, compare the affected rows with MySQL after any crash that happened during a
ClickHouse outage (`ch-mysql-resync`, spec 11.04, for any table that differs).
