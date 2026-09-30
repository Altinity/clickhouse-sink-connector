# Known limitations

Source configurations and operations the lightweight connector does not support. Each entry says what
happens if you do it anyway and how to repair the replica. The spec for each is referenced; the
consolidated operator runbook is spec 10.08 (`specs/10-resilience-monitoring/`).

## MySQL `binlog_row_value_options=PARTIAL_JSON` is not supported

With `binlog_row_value_options=PARTIAL_JSON` (globally, or in any session), MySQL logs an UPDATE of a JSON
column as a partial update event (`PARTIAL_UPDATE_ROWS`, shown as `Update_rows_partial` by
`SHOW BINLOG EVENTS`) that carries only the JSON diff. Debezium has no handler for that event type and
ignores it, so **the update is lost silently**: the partial JSON change never reaches ClickHouse, and neither
do the other columns changed by the same UPDATE statement. This happens with and without
`binlog_transaction_compression`, and the connector logs no error. Row counts stay equal; only a value-level
comparison (`db_compare`, spec 11.02) shows the divergence.

- **Requirement:** run the source with `binlog_row_value_options=''` (the MySQL default):
  `SET PERSIST binlog_row_value_options = '';` and make sure no application sets it per session.
  Check with `SELECT @@GLOBAL.binlog_row_value_options;`.
- **Repair** after partial updates were logged: re-synchronise the affected tables from MySQL
  (`ch-mysql-resync`, spec 11.04).
- Spec 01.10 section 3.1; measured by `sink-connector-lightweight/tests/e2e/binlog_transaction_compression_edge.sh` (case E2).
