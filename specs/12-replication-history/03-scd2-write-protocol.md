# Spec 12.03: SCD2 Write Protocol — INSERT, UPDATE (2 or 3 SELECTs), DELETE (2 SELECTs), TRUNCATE / DROP TABLE (bulk close)

## 1. Executive Summary & Purpose
Specifies, statement by statement, how a mode-2 connector
(`replication.history.enable=true`, spec 12.01) turns each source DML event
into rows of the SCD2 table of spec 12.02. The standard protocol (bind one
row per event, spec 04.x) is replaced for UPDATE, DELETE, TRUNCATE TABLE and
DROP TABLE by an `INSERT ... SELECT ... UNION ALL ...` statement that **reads
the current open row(s) back out of the target** and re-inserts them closed,
then appends the new version or a delete marker. The protocol has five
properties the rest of the system relies on — continuity of validity ranges,
exactly one open row per key under `FINAL`, the current-state view being a
correct replica, every closed version being visible in the as-of history,
and the history window surviving a source truncate — and this revision
states the **corrected** design (S1–S9) that the code now implements. The
defects of the previous revision (G-12.03-1..6) are kept in §6 as resolved
gaps, each with its machine-checked `old_*` counterexample and the theorem
of the new property, so the change can be audited.

---

## 2. Codebase Mapping on 2.11.0
- **Dispatch**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` — `executePreparedStatement`: constructs a `ReplicationHistoryHandler` per batch when `replication.history.enable=true`; flushes the staged statement (`executeBatch()`) before **both** the DELETE and the UPDATE history statements; routes the `op = t` record to the bulk close instead of `DBMetadata.truncateTable`; suppresses the relocation tombstone in history mode.
- **Statement builder**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/ReplicationHistoryHandler.java` — `resolveVersion` (derives the record's standard version, refused when not derivable, and returns its history version) and `historyVersion` (the snowflake encoding of the standard version's ordering key: the history version domain of §3.5.1, shared by the INSERT binding and the DDL-path bulk close), `buildUpdateQueryParams` (sentinel, event time, version, whole primary key from the **before** image, `keyChanged`), `generateUpdateQuery`, `generateDeleteQuery`, `executeHistoryUpdate`, `executeHistoryBulkClose`.
- **SQL text**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java` — `getInsertQueryForUpdate` (two SELECTs, three when the key changed), `getInsertQueryForDelete` (two SELECTs), `getInsertQueryForBulkClose` (column-agnostic `SELECT * REPLACE`), `formatPrimaryKeyPredicate`, `isTemporalTrackingColumn`, `isConnectorManagedColumn`.
- **Column binding**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` — `handleReplicationHistoryColumns` (INSERT path values of `_valid_from`, `_valid_to`, `_operation`), `isReplicationHistoryColumn` (the only columns deferred in history mode), `rejectUnderivableVersion` (shared with the history statements), the history-mode exemptions in `requireEngineColumnPlaceholder` and `requireDeleteColumn`.
- **Grouping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java` — one grouped entry per UPDATE (04.01 §3.2, 04.04 §3.1); `isTruncate` marks the `op = t` group.
- **Version source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getVersion`, `calculateVersion` (the standard version of spec 02.01 / 02.02 whose ordering key `(ts_ms, d)` every history row of the event carries, re-encoded by `historyVersion`), `getGtid`, `getSequenceNumber`, `getVersionTs`, `getTsSec`, `getPrimaryKey`, `getBeforeStruct`, `getAfterStruct`.
- **DDL path (lightweight engine)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` — `enterTruncateTable`, `enterDropTable` (history mode: the bulk close instead of `TRUNCATE TABLE` / `DROP TABLE`), `enterCreateDatabase`, `enterDropDatabase` (history mode: ignored); `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `performDDLOperation`.
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `insertRow`, `closeRow`, `afterRow`, `deleteMarker`, `keyChangeMarker`, `bulkMarker`, `updateRows`, `deleteRows`, `bulkCloseRows`, `openRows`, `applyInsert`, `applyUpdate`, `applyDelete`, `applyBulkClose`; old behaviour as `oldBeforeRow`, `oldUpdateRows`, `oldApplyUpdate`, `oldApplyUpdateInline`.

---

## 3. Operational Specification

Notation: `k` = the source primary key (all its columns); `kb` / `ka` = the
key of the before / after image of an UPDATE; `ts` = the event's `ts_sec`
rendered as `DateTime` "without timezone adjustment" from the source
timezone to the server timezone; `S` = the sentinel `2100-01-01 00:00:00`
(12.02 §3.1); `V = historyVersion(record)` — the snowflake encoding of the event's **standard** version (§3.5, §3.5.1); `tz` = the resolved server timezone id. All FINAL reads below are
`SELECT ... FROM <table> FINAL WHERE <key predicate> AND `_valid_to` =
toDateTime('S', 'tz') [AND `is_deleted` = 0]` — the **open-row predicate**;
the `is_deleted` conjunct is present when the table has that column.

The protocol in one line: **every event emits rows carrying one version
`V`; a change closes the visible open row at the key it replaces
(`_valid_to = ts`) and writes what is true afterwards at `(key, S)` — the new
image, or a delete marker.**

### 3.1 INSERT / snapshot READ (`op = c` / `op = r`) — S1
Standard path (`CDC_RECORD_STATE_AFTER`): the after image is bound into the
per-table INSERT template. `handleReplicationHistoryColumns` binds:
- `_valid_from` = `ts`;
- `_valid_to` = `S`;
- `_operation` = the single-letter code `'C'` (or `'r'` for a snapshot row);
- `is_deleted` = 0 and `_version` = `V` (`handleVersionColumn` binds
  `historyVersion(record)` in history mode — the snowflake encoding of the
  standard version `ClickHouseStruct.calculateVersion` derives, §3.5.1).
Exactly one row: `(k, image, ts, S, is_deleted=0, V, 'C')`.
Formal: `Replication.History.insertRow`.

### 3.2 UPDATE (`op = u`) — flush, then one statement, two SELECTs (three when the key changed) — S3, S4, S6
Dispatch (`CDC_RECORD_STATE_BOTH`):
1. On a `CollapsingMergeTree` target the before image is staged as a `-1`
   cancel row first (05.04) — engine-generic, not history-specific.
2. The sorting-key relocation tombstone of 05.02 is **skipped**
   (`replicationHistoryHandler == null && ...`): history mode retires the
   previous version itself (the close row, and on a key change the marker of
   step 4).
3. **`ps.executeBatch()` — the staged INSERTs of the batch are flushed
   first**, exactly as the DELETE branch does (§3.3), so that a row created
   and updated in the same batch is visible to the SELECTs below. Without
   the flush the pre-update version never entered the history (resolved
   Gap G-12.03-1).
4. `executeHistoryUpdate(isDelete=false)` builds and executes, on its own
   `PreparedStatement`:

```
INSERT INTO `t`(<all columns>)
SELECT <close>   FROM `t` FINAL WHERE <kb> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0  -- (1)
UNION ALL
SELECT <after>                                                                                            -- (2) no FROM: parameters
[UNION ALL
SELECT <marker>  FROM `t` FINAL WHERE <kb> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0] -- (3) only when kb != ka
```

**Key unchanged (`kb = ka = k`) — two SELECTs:**

| SELECT | data columns | `_valid_from` | `_valid_to` | `is_deleted` | `_version` | `_operation` |
|---|---|---|---|---|---|---|
| (1) close | copied from the open row at `k` | copied | `ts` | `0` | `V` | copied |
| (2) after | **bound parameters** from the after image | `ts` (bound) | `S` (bound) | `0` (literal) | `V` (literal) | `'U'` (literal) |

Rows produced when the open row is visible: (1) the previous version, now
closed at sorting key `(k, ts)`; (2) the new open version at `(k, S)`. The
after row supersedes the previous open row under `ReplacingMergeTree`
because that row came from an **earlier event** and therefore carries a
version `≤ V` (I2; on a tie `FINAL` keeps the later inserted row). The close
row and the after row have **different sorting keys**, so they never
compete. The former third SELECT — a deleted copy of the before image at
`(k, ts)` with the same version as the close row — is **removed**: it shared
the close row's sorting key and version and made the closed history version
disappear under `FINAL` depending on physical insertion order (resolved Gap
G-12.03-2).
Formal: `Replication.History.closeRow`, `afterRow`, `updateRows`,
`applyUpdate`; properties `Replication.History.update_supersedes_open_row`,
`Replication.History.closed_row_visible_at_close_key`,
`Replication.History.closed_row_continuity`.

**Key changed (`kb != ka`) — three SELECTs:**

| SELECT | data columns | `_valid_from` | `_valid_to` | `is_deleted` | `_version` | `_operation` |
|---|---|---|---|---|---|---|
| (1) close | copied from the open row at **`kb`** | copied | `ts` | `0` | `V` | copied |
| (2) after | bound parameters from the after image (key `ka`) | `ts` | `S` | `0` | `V` | `'U'` |
| (3) delete marker | copied from the open row at `kb` (key stays **`kb`**) | `ts` | `S` | `1` | `V` | `'U'` |

The close predicate is built from the **before image's** key: that is the row
the event replaces. Row (3) retires the old key's open sorting key
`(kb, S)` — deleted, version `V` — so `kb` has no current row and `ka` has
exactly one, the after row. Before this change the predicate used the after
key and a key-changing UPDATE left **two** current rows for one source row
(resolved Gap G-12.03-3). `buildUpdateQueryParams` sets `keyChanged` when any
primary-key column differs between the two images; a record with only an
after image (never an UPDATE today) is keyed by it.
Formal: `Replication.History.keyChangeMarker`; properties
`Replication.History.update_closes_before_image_key`,
`Replication.History.key_change_retires_old_key`,
`Replication.History.key_change_opens_new_key`.

**Key predicate.** Every key column is used (`formatPrimaryKeyPredicate`,
composite keys, 02.01 §3.5 a) in every table-reading SELECT. A record
without a primary key, or without any row image, is refused with
`IllegalStateException` naming the topic (I9).

**Binding.** Only SELECT (2) has parameters: the after image's data columns
and `_valid_from` / `_valid_to`
(`PreparedStatementFieldMapperUnboundColumnTest.testTemporalColumnsAreBoundParameters()`);
`_version`, `is_deleted`, `_operation` are SQL literals and deliberately have
no placeholder, which is why `requireEngineColumnPlaceholder` is exempt in
history mode
(`PreparedStatementFieldMapperEngineColumnTest.testHistoryModeStatementWithLiteralEngineColumnsIsAccepted()`).
**Unknown columns (S9):** a ClickHouse column absent from the record's
schema raises the standard loud `StaleSchemaCacheException` of 08.03 in
history mode too; only the connector's own history columns (`_valid_from`,
`_valid_to`, `_operation` — `isReplicationHistoryColumn`) are deferred to
`handleReplicationHistoryColumns`. The previous blanket NULL-binding of
every unknown column in history mode disabled the stale-cache defence for
SCD2 tables (resolved Gap G-12.03-5).

### 3.3 DELETE (`op = d`) — flush, then one statement, two SELECTs — S5
Dispatch (`CDC_RECORD_STATE_BEFORE` with `DELETE`):
1. `ps.executeBatch()` — the staged INSERTs of the batch are flushed first,
   so that a row created and deleted in the same batch is visible.
2. `executeHistoryUpdate(isDelete=true)`:

```
INSERT INTO `t`(<all columns>)
SELECT <close>  FROM `t` FINAL WHERE <k> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0   -- (1)
UNION ALL
SELECT <marker> FROM `t` FINAL WHERE <k> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0   -- (2)
```

| SELECT | data columns | `_valid_from` | `_valid_to` | `is_deleted` | `_version` | `_operation` |
|---|---|---|---|---|---|---|
| (1) close | copied | copied | `ts` | `0` | `V` | copied |
| (2) marker | copied from the open row (**not** from the event's before image) | `ts` | `S` | `1` | `V` | `'D'` |

No parameters are bound; the key is the before image's key. The marker at
`(k, S)` with version `V` supersedes the open row (earlier event, version
`≤ V`; later row on a tie) and, being deleted, hides the key under `FINAL`;
the closed row keeps the last version's validity range `[_valid_from, ts)`.
If no open row is visible, both SELECTs return nothing and the DELETE writes
zero rows with no error — the flush of step 1 makes this impossible within
a batch, and across batches the open row is always flushed.
Formal: `Replication.History.deleteMarker`, `deleteRows`, `applyDelete`;
properties `Replication.History.delete_hides_open_row`,
`Replication.History.no_open_row_after_delete_marker`,
`Replication.History.delete_rows_share_one_version`.

### 3.4 TRUNCATE TABLE and DROP TABLE — bulk close on both paths — S7
In history mode the SCD2 table is **never truncated and never dropped**: a
source `TRUNCATE TABLE` / `DROP TABLE` empties the current-state view while
the closed versions survive, exactly as every other change. One
column-agnostic statement (`QueryFormatter.getInsertQueryForBulkClose`)
closes every open row and writes one marker per open row:

```
INSERT INTO `db`.`t`
SELECT * REPLACE (toDateTime('ts','tz') AS `_valid_to`, V AS `_version`)
  FROM `db`.`t` FINAL WHERE `_valid_to` = toDateTime('S','tz') [AND `is_deleted` = 0]                                  -- (1)
UNION ALL
SELECT * REPLACE (toDateTime('ts','tz') AS `_valid_from`, [1 AS `is_deleted`,] 'T' AS `_operation`, V AS `_version`)
  FROM `db`.`t` FINAL WHERE `_valid_to` = toDateTime('S','tz') [AND `is_deleted` = 0]                                  -- (2)
```

| SELECT | data columns | `_valid_from` | `_valid_to` | `is_deleted` | `_version` | `_operation` |
|---|---|---|---|---|---|---|
| (1) close, per open row | copied | copied | `ts` | `0` | `V` | copied |
| (2) marker, per open row | copied | `ts` | `S` | `1` | `V` | `'T'` (TRUNCATE TABLE) / `'D'` (DROP TABLE) |

`SELECT * REPLACE` keeps the table's column order, so the positional INSERT
needs no column map; there is no primary-key predicate — every open row is
closed; when the table has no `is_deleted` column the `1 AS is_deleted`
replacement and the predicate conjunct are omitted. The statement only
inserts: nothing is destroyed, and the statement binds no parameters.

Both arrival paths use it:
- **`op = t` record path** (`PreparedStatementExecutor`): the TRUNCATE
  segment of 04.05 §3 calls `executeHistoryBulkClose` instead of
  `DBMetadata.truncateTable`; the segment ordering of 04.05 (flush before,
  own segment) is unchanged, so rows staged before the truncate are closed
  by it and rows after it open fresh versions.
- **DDL path** (`MySqlDDLParserListenerImpl.enterTruncateTable` /
  `enterDropTable` in the lightweight engine, executed by
  `performDDLOperation`): this is how a MySQL `TRUNCATE TABLE` actually
  arrives, since Debezium skips `t` events by default (`skipped.operations`
  contains `t`); the translator emits the bulk close in place of the
  ClickHouse `TRUNCATE TABLE` / `DROP TABLE`. `disable.drop.truncate` keeps
  its meaning (the statement is still classified as a drop/truncate for that
  gate), and the DDL row is audited as usual (12.04 §3.4). The translator
  records one request per table named (`historyBulkCloses()`); the engine
  skips a request whose table does not exist in ClickHouse (nothing to close)
  and refuses one whose event time, version or column set cannot be
  established. **Snapshot-phase statements close nothing**: Debezium's schema
  snapshot replays `DROP TABLE IF EXISTS` before every `CREATE TABLE` as a
  bootstrap, not as a source event, and on a re-snapshot into an existing
  history database it would otherwise retire every open row with a `'D'`
  marker only for the snapshot to re-open it; the DDL row is still audited.

A source table dropped and re-created keeps its history: a later `CREATE
TABLE` is `IF NOT EXISTS` (12.02 §3.7) and the new rows open new versions
after the `'D'` markers. **Database-level DDL** (`CREATE DATABASE`, `DROP
DATABASE`) is **ignored** in history mode (S8, 12.01 §3.3): the history
database is fixed by configuration, so a source database statement names
nothing of the connector's — and Debezium's snapshot replays `DROP DATABASE
IF EXISTS <src>` for every captured database.
Formal: `Replication.History.bulkMarker`, `openRows`, `bulkCloseRows`,
`applyBulkClose`; properties
`Replication.History.bulk_close_hides_every_open_row`,
`Replication.History.bulk_close_preserves_history`,
`Replication.History.database_ddl_ignored_in_history_mode`.

### 3.5 One version per event — S2
Every row an event emits — INSERT row, close row, after row, key-change
marker, delete marker, bulk-close rows — carries the **same** version
`V = historyVersion(record)`: the snowflake encoding (§3.5.1) of the standard,
floor-clamped, monotonic version of spec 02.01 / 02.02
(`ReplicationHistoryHandler.resolveVersion`, which derives the standard version lazily with the same `snowflake.id` flag the field mapper uses,
because the history statements are built before any binding and the DELETE
and bulk-close statements bind nothing). There is no second domain for the
UPDATE/DELETE rows (2.11.0 used `SnowFlakeId(ts_ms, gtid)` there and the standard
version on the INSERT row) and no `V+1`:
- rows of one event never compete with each other — the close row lives at
  `(k, ts)`, the open-key rows at `(k, S)` (or at `(kb, S)` vs `(ka, S)` on
  a key change);
- at `(k, S)` the after row or marker beats the previous open row because
  that row came from an earlier event and, by I2, has a version `≤ V`; on
  equality `FINAL` keeps the later inserted row;
- `V+1` **collided with the next event's version** on the sequence path of
  02.01 §3.2, so an INSERT + UPDATE + UPDATE sequence could leave the older
  image current — one version per event removes the collision (resolved
  Gap G-12.03-4).
A version that cannot be derived (`record.getVersion() <= 0`, or an event millisecond not after the snowflake epoch) is refused
loudly (`rejectUnderivableVersion`, `IllegalStateException`; 02.05 §3.2):
bound as `-1` it would become the maximum `UInt64` and win every merge for
the key forever.
Formal: `Replication.History.update_rows_share_one_version`,
`Replication.History.delete_rows_share_one_version`; the hypothesis of every
convergence theorem is exactly "every existing open row of the key has
version `≤ V`".

#### 3.5.1 The history version domain — S10 (upgrade and downgrade safety)
`V` is **not the raw number** of the standard version: it is the snowflake
encoding of the standard version's ordering key,
`V = SnowFlakeId.generate(ts_ms, d, false)` (`ReplicationHistoryHandler.historyVersion`),
where

| standard version of 02.01 | `ts_ms` | `d` (low 22 bits) |
|---|---|---|
| GTID, `snowflake.id=true` (`SnowFlakeId(versionTs \| ts_ms, gtid)`) | `versionTs`, else `ts_ms` | `gtid` — `V` **is** the standard version |
| GTID, `snowflake.id=false` (raw transaction number) | `versionTs`, else `ts_ms` | `gtid` |
| lightweight sequence `effectiveTs · 10^6 + counter` — every row of a GTID-less source and the **snapshot rows of any source** (their Debezium offset carries no GTID) | `effectiveTs − 1` (`versionTs` set by the dispatch loop; without it `seq / 10^6 − 1`) | `counter − seed` (`counter = seq − effectiveTs · 10^6`; `seed` = `SEQUENCE_START_INITIAL` 500 000 000 on the first window of a run, `SEQUENCE_START` 1 000 000 000 after a reset — 02.01 §4 freezes both); refused loudly when `≥ 2^22` |
| LSN / Kafka-offset fallback | `ts_ms` | low 22 bits of the standard version |

`SnowFlakeId.generate` is `(ts_ms − 1288834974657) · 2^22 + d` for
`d < 2^22`, so it is **strictly monotone** in `(ts_ms, d)`: the history
version orders events exactly as the standard version does — the number
changes, the order does not, and every property of §3.5 (one version per
event, redelivery ranks `≤` the first delivery via the floor of 02.02) is
inherited unchanged. Two details of the sequence row make that true across
the two paths of 02.01 §3.1:
- the counter is taken **less its seed**, because the ten-digit seeds carry
  whole milliseconds into `seq / 10^6` (02.01 §3.3) — within a window the
  counter only increments, a reset moves the window to a later millisecond,
  and a run starts above the previous high-water mark, so `(effectiveTs,
  counter − seed)` orders exactly as the sequence does;
- the millisecond is the one **below** the effective millisecond, because the
  first GTID rows after a snapshot are floored to the snapshot's last
  effective millisecond (02.02): in the standard domain they outrank the
  snapshot rows by *domain* (any snowflake is above any sequence), which one
  history domain cannot reproduce, so the snapshot rows step one millisecond
  down and every later GTID row of the same key ranks above them whatever
  the two discriminators are (`ReplicationHistoryVersionDomainTest.snapshotRowRanksBelowAGtidRowFlooredToTheSameMillisecond()`,
  `Replication.History.sequence_row_below_gtid_row_of_its_millisecond`). The
  first iteration of this change encoded `(seq / 10^6, seq mod 10^6)` and
  the history suite on a GTID source failed on exactly this: the snapshot
  rows sat 500 ms above their effective millisecond and the first UPDATE of
  every snapshotted key was merged away. Every row of an SCD2 table is written in this domain:
the INSERT row bound by `PreparedStatementFieldMapper.handleVersionColumn`
in history mode, the close/after rows, the key-change and delete markers,
and the bulk-close rows of §3.4 on both the record path and the DDL path
(`DebeziumChangeEventCapture.executeHistoryBulkCloses`). An event whose
millisecond is not after the snowflake epoch is refused loudly (the
timestamp field would wrap).

**Why (the 2.11.0 upgrade freeze).** Releases up to and including 2.11.0
bound the INSERT row with the standard version but wrote the UPDATE close
row with `SnowFlakeId(ts_ms, gtid)` and the after row / delete marker with
that value `+ 1`. On a source without GTIDs the standard version is the
sequence, ≈ `1.8·10^18` in 2026, while a snowflake of the same instant is
≈ `2.1·10^18`; the two domains do not cross until 2032. `FINAL` keeps the
greatest `_version` at a sorting key, so every open row or delete marker a
2.11.0 connector left at `(k, S)` outranked every sequence version the
fixed connector could produce: had `V` been the raw sequence, **every key a
2.11.0 connector had ever updated or deleted would have frozen at the
upgrade** — later updates and deletes entered the history and never became
current (`old_raw_sequence_version_loses_to_legacy_open_row`), and a
re-insert after a 2.11.0 delete stayed hidden behind the marker. With `V`
in the snowflake domain a later event is a greater number on both sides of
an upgrade **and** of a downgrade, whichever release wrote the earlier row:

| written by | row at `(k, S)` | `_version` | superseded by the other release's next event? |
|---|---|---|---|
| 2.11.0 | after row / delete marker | `SnowFlakeId(ts_old, gtid) + 1` | yes — `SnowFlakeId(ts_new, d)` with `ts_new > ts_old` |
| 2.11.0 | INSERT row | sequence ≈ `1.8·10^18` | yes — any snowflake is greater |
| fixed | any row | `SnowFlakeId(ts_new, d)` | yes — 2.11.0 writes `SnowFlakeId(ts_later, gtid) + 1` |

The only ordering the two releases can disagree on is inside one
millisecond, which a restart between them cannot produce. No table
migration is needed in either direction; the end-to-end suite
`csc_e2e_updown` hops OLD → NEW → OLD → NEW on the same tables with and
without GTIDs and checks the current state value-by-value after every hop.
Formal: `Replication.History.snowflake_encode_strict_mono`,
`Replication.History.legacy_open_row_superseded_by_later_event`,
`Replication.History.old_raw_sequence_version_loses_to_legacy_open_row`.

### 3.6 Timezones
`ts` and `S` are converted with `convertWithoutTimeZoneAdjustment` from the
source timezone (`source.datetime.timezone`, default UTC) to the server
timezone; the statement literals wrap them in `toDateTime(..., 'tz')`. See
12.02 §3.1 / Gap G-12.02-1 for the equality requirement between the
statement timezone and the column timezone.

### 3.7 Operation codes
SCD2 tables store the **single-letter** codes: `'C'`, `'r'`, `'U'`, `'D'`
from `CDC_OPERATION.getOperation()`, plus `'T'` for the bulk-close marker of
a `TRUNCATE TABLE` (a `DROP TABLE` marker is `'D'`). The audit table of
12.04 stores the enum **name** (`CREATE`, `UPDATE`, `DELETE`, `READ`,
`TRUNCATE`); a consumer joining the two tables must translate (02.01 §3.5 c,
G-12.04-2).

### 3.8 What a reader must do
- **Current state**: `SELECT ... FROM t FINAL WHERE _valid_to =
  toDateTime('S') AND is_deleted = 0` — equals the source table (I3 on open
  rows, 12.02 §4); empty after a source truncate or drop.
- **As-of `T`**: `WHERE _valid_from <= T AND T < _valid_to AND is_deleted = 0`
  (with `FINAL`) — every closed version is visible
  (`Replication.History.closed_row_visible_at_close_key`), subject only to
  the second-granularity limitation of §3.9.
- Row counts of a mode-2 table are meaningless for verification (11.02 Rule
  0): they count versions.

### 3.9 Known limitations (stated, inherent)
0. **A redelivered UPDATE or DELETE leaves a zero-length version.** Delivery
   is at-least-once (spec 02.04): when a batch is retried after its history
   statement already ran, the retried UPDATE closes the row it opened the
   first time (`_valid_from = _valid_to = ts`, a zero-length version at
   `(k, ts)`) and re-opens it identically; a retried DELETE finds no open row
   and writes nothing. The current-state view is unaffected and no version is
   lost. Excluding the event's own after row from the close predicate would
   also exclude a same-second, same-version predecessor and lose its closed
   version, so the artifact is accepted (observed after a transient batch
   failure in the end-to-end history suite).
1. **Second granularity of `_valid_to`.** `_valid_to` is a `DateTime`
   (seconds). Two versions of one key closed **within the same second**
   share the sorting key `(k, ts)`; under `ReplacingMergeTree` the later
   one (higher or equal version, later inserted) wins and the earlier closed
   version is not visible under `FINAL`. The **current-state view is
   unaffected** — only the as-of history inside that second is coarsened.
   The theorem `closed_row_visible_at_close_key` states its hypothesis
   accordingly (`hfresh`: no earlier row of the key was closed at `ts`).
2. **GTID version tie within one millisecond.** On the GTID version path of
   02.01 §3.1 all rows of one transaction inside one millisecond carry **one
   version**. `FINAL` resolves such ties to the later inserted row, so the
   **order of the statements within a batch** — binlog order, which 04.05
   and the per-event flush of §3.2/§3.3 preserve — is what keeps the current
   state right; the theorems model this with `≤ V` and the later-row tie
   rule of `hMaxStep`.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)** on the open rows:
  `Replication.History.update_supersedes_open_row` (after a same-key UPDATE
  the live row at `(k, S)` is the new image),
  `Replication.History.key_change_retires_old_key` and
  `Replication.History.key_change_opens_new_key` (after a key-changing
  UPDATE the old key has no live row and the new key has the new image),
  `Replication.History.delete_hides_open_row` (after a DELETE there is none),
  `Replication.History.bulk_close_hides_every_open_row` (after a TRUNCATE /
  DROP TABLE no key has one). Hypothesis of each: every existing open row of
  the key has version `≤ V` — the one-version-per-event rule of §3.5 plus I2.
- **Invariant I2 (Deterministic Version Monotonicity)**: history rows carry
  the standard version, so the monotonicity of 02.02 applies to them
  unchanged (`update_rows_share_one_version`,
  `delete_rows_share_one_version`); no second version domain exists.
- **Invariant I4 (Sorting Key Mutation Integrity)**: the relocation
  tombstone is suppressed; the close row at `(kb, ts)` and the key-change
  marker at `(kb, S)` are the retirement mechanism
  (`update_closes_before_image_key`, `key_change_retires_old_key`).
- **Invariant I9 (Loud Failure)**: missing primary key, missing row image
  and underivable version are refused loudly; an unknown column raises
  `StaleSchemaCacheException` as in standard mode (S9). The zero-row DELETE
  of §3.3 is unreachable within a batch because of the flush.
- **Continuity**: the closed version's `_valid_to` equals the successor's
  `_valid_from` (`Replication.History.closed_row_continuity`); ranges never
  overlap and never leave a gap.
- **History preservation**: no statement of this protocol deletes or
  rewrites a physical row (`Replication.History.bulk_close_preserves_history`
  for the only statement that used to).
- **Uniqueness**: `Replication.History.at_most_one_live_row_per_sort_key`.
- **As-of completeness**: `Replication.History.closed_row_visible_at_close_key`
  under the second-granularity limitation of §3.9.

---

## 5. Verification Criteria
- `QueryFormatterTest.testGetInsertQueryForUpdate()` — §3.2 statement text for a same-key UPDATE: exactly one `UNION ALL`, open-row predicate with the sentinel and `is_deleted = 0`, close row at the binlog timestamp, `_valid_from` / `_valid_to` in the parameter map.
- `QueryFormatterTest.updateEmitsNoDeletedBeforeCopy()` — §3.2: no `is_deleted = 1` row at `_valid_to = ts`; the deleted before copy is gone.
- `QueryFormatterTest.keyChangingUpdateEmitsDeleteMarkerAtOldKey()` — §3.2 key change: a third SELECT, deleted, at the before key's open sorting key, `_operation = 'U'`.
- `QueryFormatterTest.allRowsOfOneEventShareOneVersion()` — §3.5: every `_version` literal in the UPDATE, key-change and DELETE statements equals the event version; no `V+1`.
- `QueryFormatterTest.truncateClosesEveryOpenRowAndMarksIt()` — §3.4: the bulk-close statement (`SELECT * REPLACE`, no key predicate, close at `ts`, marker `'T'` at the sentinel, `is_deleted` handling).
- `QueryFormatterTest.testGetInsertQueryForDelete()` — §3.3: one `UNION ALL`, `'D'`, empty parameter map, both rows at `V`.
- `QueryFormatterTest.updateAndDeleteQueriesUseEveryPrimaryKeyColumn()`, `ReplicationHistoryHandlerTest.compositePrimaryKeyClosesOnlyTheMatchingRow()` — composite-key predicate in every table-reading SELECT.
- `ReplicationHistoryHandlerTest.closePredicateUsesBeforeImageKey()` — §3.2: the key values come from the before image.
- `ReplicationHistoryHandlerTest.keyChangingUpdateClosesAndRetiresTheOldKey()` — §3.2: `keyChanged` is set when a key column differs and the generated statement carries the marker.
- `ReplicationHistoryHandlerTest.everyHistoryRowCarriesTheEventsHistoryVersion()` — §3.5 / §3.5.1: every `_version` literal of the UPDATE and DELETE statements is `historyVersion(record)`; no `V+1`; the raw standard version never appears.
- `ReplicationHistoryHandlerTest.versionIsDerivedTheStandardWayWhenNotYetCalculated()` — §3.5: the standard version is derived lazily with the configured `snowflake.id` flag, left on the record, and encoded for the history rows.
- `ReplicationHistoryVersionDomainTest.gtidSourceIsTheStandardSnowflakeVersionItself()`, `ReplicationHistoryVersionDomainTest.gtidSourceUsesTheFlooredVersionTimestampWhenTheDispatchLoopSetOne()`, `ReplicationHistoryVersionDomainTest.rawGtidVersionIsReEncoded()`, `ReplicationHistoryVersionDomainTest.sequenceRowEncodesTheMillisecondBelowItsEffectiveOneAndTheCounterLessItsSeed()`, `ReplicationHistoryVersionDomainTest.sequenceRowWithoutAnEffectiveMillisecondRecoversItFromTheSequence()`, `ReplicationHistoryVersionDomainTest.fallbackSourcesUseTheLowBitsOfTheStandardVersion()` — §3.5.1: the encoding on each source path.
- `ReplicationHistoryVersionDomainTest.sequenceEncodingIsStrictlyMonotoneLikeTheSequence()` — §3.5.1: the encoding preserves the order of the sequence (same millisecond, next counter; next millisecond, counter reset).
- `ReplicationHistoryVersionDomainTest.snapshotRowRanksBelowAGtidRowFlooredToTheSameMillisecond()` — §3.5.1: a snapshot (sequence) row ranks below a GTID row floored to the same effective millisecond whatever the discriminators — the case the history suite exposed on a GTID source.
- `ReplicationHistoryVersionDomainTest.underivableOrUnencodableVersionsAreRefused()` — §3.5.1: the `-1` sentinel and an event millisecond not after the snowflake epoch are refused.
- `ReplicationHistoryVersionDomainTest.legacyOpenRowIsSupersededOnUpgrade()`, `ReplicationHistoryVersionDomainTest.fixedOpenRowIsSupersededOnDowngrade()` — §3.5.1 (S10): a 2.11.0 open row (`SnowFlakeId(ts, -1) + 1`) ranks `<=` the fixed connector's next event and the raw sequence would have ranked below it; a 2.11.0 connector's next event outranks a fixed open row.
- `ReplicationHistoryVersionDomainTest.insertPathBindsTheHistoryVersionInHistoryMode()` — §3.1 / §3.5.1: `handleVersionColumn` binds the history version in history mode and the standard version otherwise.
- `ReplicationHistoryHandlerTest.underivableVersionIsRefused()` — §3.5: a record whose version cannot be derived is refused with `IllegalStateException`.
- `ReplicationHistoryHandlerTest.bulkCloseUsesEventTimeAndVersion()` — §3.4: the bulk close is built with the event time `ts` and the event version.
- `PreparedStatementExecutorHistoryFlushTest` — §3.2 step 3: the staged statement is flushed (`executeBatch()`) before the UPDATE history statement runs, as before a DELETE.
- `PreparedStatementFieldMapperUnboundColumnTest.testTemporalColumnsAreBoundParameters()` — §3.2 binding of `_valid_from` / `_valid_to`.
- `PreparedStatementFieldMapperEngineColumnTest.testHistoryModeStatementWithLiteralEngineColumnsIsAccepted()` — §3.2 literal engine columns.
- `QueryFormatterOperationColumnTest.testOperationColumnSurvives()` — `_operation` stays in the INSERT column list.
- `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()`, `GroupInsertQueryHistoryMultiRowTest.recordsAfterTheFirstUpdateSurviveInHistoryMode()`, `GroupInsertQueryHistoryMultiRowTest.allRowsOfAMultiRowUpdateAreGroupedInHistoryMode()` — one grouped entry per UPDATE, all rows of a multi-row statement.
- `VersionHistoryIT.testValidToValidFromColumnsOnUpdateDelete()` — §3.1–§3.3 end to end without `FINAL`: after INSERT one open row; after UPDATE the close row (`is_deleted=0`, old image) and the new open row, and **no** deleted before copy; after DELETE a closed row and a `'D'` marker; after `OPTIMIZE ... FINAL` no active row.
- `VersionHistoryInitialIT.testValidToValidFromColumnsOnUpdateDeleteWithInitialSnapshot()` — the same protocol read **with** `FINAL`: exactly two rows after the UPDATE (closed original + open new), deterministically, and the delete marker hidden after the DELETE.
- `VersionHistoryIT.testDecimalPrecisionOnUpdate()` — bound decimal columns in SELECT (2) keep their precision (`CAST(?, 'Decimal(...)')`, 07.02).
- `BinLogHistoryIT.testBinLogHistory()` — mode 2 end to end including the audit table.
- The end-to-end history suite (three connectors side by side, kill -9 restart, single-threaded variants, degenerate combination) — INSERT/UPDATE/DELETE/key-change/TRUNCATE sequences compared against the source under `FINAL`, on both execution engines, across a restart, with and without GTIDs on the source.
- The end-to-end upgrade / downgrade suite (`csc_e2e_updown`: the three connectors hop 2.11.0 → fixed → 2.11.0 → fixed on the same tables, offsets and schema history, then a kill -9 restart on the fixed build; after every hop a workload touches the keys the OTHER build last wrote and the current state is compared value-by-value against the source, with and without GTIDs) — §3.5.1 (S10). Against the raw-sequence binding this suite fails at the first hop on a GTID-less source: the upgraded connector's UPDATE of a key the 2.11.0 build had updated is written and never becomes current, and a key change leaves the old key live.
- Lean (`formal_specs/lean/Replication/History.lean`): `Replication.History.update_supersedes_open_row`, `Replication.History.update_closes_before_image_key`, `Replication.History.key_change_retires_old_key`, `Replication.History.key_change_opens_new_key`, `Replication.History.closed_row_visible_at_close_key`, `Replication.History.update_rows_share_one_version`, `Replication.History.delete_rows_share_one_version`, `Replication.History.delete_hides_open_row`, `Replication.History.closed_row_continuity`, `Replication.History.no_open_row_after_delete_marker`, `Replication.History.at_most_one_live_row_per_sort_key`, `Replication.History.bulk_close_hides_every_open_row`, `Replication.History.bulk_close_preserves_history`, `Replication.History.database_ddl_ignored_in_history_mode`, `Replication.History.snowflake_encode_strict_mono`, `Replication.History.sequence_row_below_gtid_row_of_its_millisecond`, `Replication.History.legacy_open_row_superseded_by_later_event`, `Replication.History.fixed_open_row_superseded_by_later_legacy_event`; old-behaviour witnesses `Replication.History.old_inline_update_writes_no_closed_row`, `Replication.History.old_close_and_before_shared_sort_key_and_version`, `Replication.History.old_closed_history_row_depended_on_insert_order`, `Replication.History.old_update_only_closed_after_image_key`, `Replication.History.old_raw_sequence_version_loses_to_legacy_open_row`.
- **Coverage gaps**: no automated test exercises the two limitations of §3.9 (two closes of one key within one second; a GTID same-millisecond tie across a batch boundary), or a DROP TABLE followed by CREATE TABLE of the same name in history mode.

---

## 6. Gaps resolved in this change
| Id | Where | Old behaviour | New behaviour | New-property theorem | Old witness |
|---|---|---|---|---|---|
| G-12.03-1 | `PreparedStatementExecutor` UPDATE branch | history statement ran inline without the `executeBatch()` flush; an INSERT + UPDATE in one batch wrote no closed row | flush before the UPDATE statement, as before a DELETE (§3.2 step 3) | `Replication.History.update_closes_before_image_key` | `Replication.History.old_inline_update_writes_no_closed_row` |
| G-12.03-2 | `QueryFormatter.getInsertQueryForUpdate` | close row and deleted before copy shared `(k, ts)` and `V`; `FINAL` kept one of them by physical order | the before copy is removed; the close row is the only row at `(k, ts)` (§3.2) | `Replication.History.closed_row_visible_at_close_key` | `Replication.History.old_close_and_before_shared_sort_key_and_version`, `Replication.History.old_closed_history_row_depended_on_insert_order` |
| G-12.03-3 | `ReplicationHistoryHandler.buildUpdateQueryParams` | close predicate from the after key; a key change left the old key open | before-image key, plus a delete marker at `(kb, S)` on a key change (§3.2) | `Replication.History.key_change_retires_old_key`, `Replication.History.key_change_opens_new_key` | `Replication.History.old_update_only_closed_after_image_key` |
| G-12.03-4 | `ReplicationHistoryHandler` version | `SnowFlakeId(ts_ms, gtid)` on the close row and `V+1` on the after row / marker while the INSERT row carried the standard version: intra-millisecond collisions, collision with the next event | one version per event, `historyVersion(record)`, in every row the event writes; underivable version refused (§3.5) | `Replication.History.update_rows_share_one_version`, `Replication.History.delete_rows_share_one_version` | `ReplicationHistoryHandlerTest.everyHistoryRowCarriesTheEventsHistoryVersion()` pins the change; the `V+1` collision was a test-free observation |
| S10 | `ReplicationHistoryHandler.historyVersion`, `PreparedStatementFieldMapper.handleVersionColumn`, `DebeziumChangeEventCapture.executeHistoryBulkCloses` | (first iteration of this change) the raw standard version as `V`: on a GTID-less source the 2.11.0 open rows and markers at `(k, S)` (`≈ 2.1·10^18`) outranked every sequence version (`≈ 1.8·10^18`) — every key a 2.11.0 connector had updated or deleted froze at the upgrade, and a re-insert after a 2.11.0 delete stayed hidden | every row of an SCD2 table carries the snowflake encoding of the event's ordering key (§3.5.1); upgrade and downgrade on the same tables without migration | `Replication.History.snowflake_encode_strict_mono`, `Replication.History.legacy_open_row_superseded_by_later_event`, `Replication.History.fixed_open_row_superseded_by_later_legacy_event` | `Replication.History.old_raw_sequence_version_loses_to_legacy_open_row`; `ReplicationHistoryVersionDomainTest.legacyOpenRowIsSupersededOnUpgrade()` (the raw sequence loses); the upgrade / downgrade end-to-end suite fails at the first hop without GTIDs |
| G-12.03-5 | `PreparedStatementFieldMapper` unknown-column branch | every unknown column bound to NULL in history mode | only `_valid_from` / `_valid_to` / `_operation` deferred; every other unknown column is the loud `StaleSchemaCacheException` of 08.03 (S9) | — (statement-level rule) | — |
| G-12.03-6 | `PreparedStatementExecutor` TRUNCATE segment; `MySqlDDLParserListenerImpl` TRUNCATE / DROP TABLE | the SCD2 table was truncated / dropped, closed versions included | bulk close on both paths (§3.4): every open row closed, one marker per row, nothing destroyed | `Replication.History.bulk_close_hides_every_open_row`, `Replication.History.bulk_close_preserves_history` | — (the old behaviour was the plain `TRUNCATE TABLE` of 04.05) |

---

## 7. Failure Modes & Recovery
The standard protocol recovers by replay: at-least-once delivery plus `ReplacingMergeTree` versions converge the replica. The SCD2 protocol **does not**. Each UPDATE, DELETE and bulk close reads the current open row back out of the target and derives its rows from it, so replaying an event against a later state writes different rows than the first delivery did. The current-state view (open rows) still converges after a replay. The closed versions do not: a replay can overwrite them, and **MySQL holds no history, so they cannot be re-synchronised from the source**. The only other record of past versions is the audit table of 12.04, which keeps `replication.history.ttl` days. Every failure that ends in a replay therefore has a permanent history cost, and the repair is the bounded, key-scoped procedure of FM-12.03-8. Throughput is the second weak point: one round trip per UPDATE or DELETE.

- **FM-12.03-1 In-run batch retry re-applies UPDATEs, and the pre-update version is lost**
  - **Trigger**: a batch fails after some of its SCD2 statements have run. A later statement hits a retriable ClickHouse error (`TOO_MANY_PARTS` 252, `MEMORY_LIMIT_EXCEEDED` 241, a timeout or reset), a `StaleSchemaCacheException`, or `processRecordsByTopic` returns false. `ClickHouseBatchRunnable` keeps `currentBatch` and re-runs `processBatch` on the same `ClickHouseStruct` objects.
  - **Behaviour**: `PreparedStatementExecutor` executes each UPDATE and DELETE history statement immediately (`flushStagedRows`, then `executeHistoryUpdate`), so the retry executes again the statements that had succeeded. The version is the same, because `resolveVersion` keeps the record's version.
    - A re-applied UPDATE's close SELECT now reads the open row the first attempt wrote: the after image, with `_valid_from = ts`. It inserts that row closed at `(k, ts)` with version `V`.
    - That is the same sorting key and the same version as the first attempt's close row (the before image, `[t0, ts)`). `FINAL` keeps the later-inserted row, so the pre-update version is replaced by a zero-length copy of the after image.
    - §3.9 item 0 says the retry leaves "a zero-length version" and that "no version is lost". The first half is right, the second is wrong. The theorem `Replication.History.closed_row_visible_at_close_key` excludes exactly this case through its `hfresh` hypothesis.
    - A re-applied DELETE writes nothing, which is correct: its own marker hides the key.
  - **Detection**: none for the loss. The retry itself logs `Retriable ClickHouse error (Code: ...)` or `Batch not written to ClickHouse; retrying the same batch in ... ms`.
  - **Blast radius**: the current state converges when the retry succeeds. One closed version per re-applied UPDATE is lost for good.
  - **Recovery**: the current state heals by itself. The lost versions can be restored only from the audit table, within its TTL (FM-12.03-8, scoped to the keys the batch updated).
  - **RTO**: current state, the retry backoff of spec 10.02. History, manual and unmeasured.
  - **Test**: GAP: an integration test (ClickHouse container) that fails a batch after its first UPDATE statement, lets the retry succeed, and asserts that the pre-update version is still visible at `(k, ts)` under `FINAL`.
  - **DEFECT**: a retried batch silently destroys the closed versions of the UPDATEs it re-applies.

- **FM-12.03-2 Restart redelivery re-applies the unacknowledged window against a later state**
  - **Trigger**: `kill -9`, OOM, a host crash, or an engine recreation while written batches are not yet acknowledged. Debezium re-publishes them from the last committed offset.
  - **Behaviour**: redelivered events are versioned above everything the previous run wrote (spec 02.04 §3.2). A redelivered UPDATE of key `k` closes the key's current open row, which is possibly the after image of a later event of the same window that is already applied. It closes it at `(k, ts_event)` with the higher version, which supersedes the original close row at that sorting key. The result is a closed row whose `_valid_from` is later than its `_valid_to`, and the original closed version is gone. The redelivered after row then makes the older image current again until the later events of the window are replayed. Redelivered INSERTs likewise re-open a key until its later DELETE is replayed. Bulk closes re-close rows opened after them.
  - **Detection**: none. Damage query: `SELECT count() FROM <history db>.<t> WHERE _valid_from > _valid_to`. The redelivered coordinates also appear twice in the audit table (12.04 §7 FM-12.04-2).
  - **Blast radius**: the current state regresses transiently during the replay and converges when it ends. For every key updated in the window, the version before its first redelivered event is lost and inverted rows are left behind.
  - **Recovery**: the current state heals by the replay. History: FM-12.03-8, scoped to the window between the last committed offset (`show_replica_status`) and the crash.
  - **RTO**: current state, a restart (about 20 s) plus the replay of the unacknowledged batches. History, manual and unmeasured.
  - **Test**: GAP: a crash-restart integration test asserting that no closed version is lost and no `_valid_from > _valid_to` row appears (spec 02.04 §5 notes that no crash-restart test exists).
  - **DEFECT**: history mode is not idempotent under the at-least-once delivery the connector provides.

- **FM-12.03-3 One round trip and new parts per UPDATE or DELETE: huge transactions do not drain**
  - **Trigger**: a bulk `UPDATE` or `DELETE` of millions of rows (a multi-GB transaction), or a sustained high update rate.
  - **Behaviour**: for every UPDATE and DELETE record, `PreparedStatementExecutor` flushes the staged INSERTs and runs one `INSERT ... SELECT ... FROM t FINAL WHERE <key> ...` of its own. Each such statement is one round trip plus a `FINAL` read, and it writes new parts in partition `toDate(ts)` (close row) and `2100-01-01` (after row or marker). With `routing.by.primary.key` (spec 03.07) the rows spread over `thread.pool.size` workers. Without it they stay on one worker. Either way, a 180 M-row UPDATE is 180 M statements, at most `thread.pool.size` of them in flight at once. The part count grows faster than merges, until ClickHouse throttles and then refuses with `TOO_MANY_PARTS` (252, retriable). The retry then triggers FM-12.03-1.
  - **Detection**: `seconds_behind_source` grows (spec 10.03). The INFO `EXECUTED BATCH` rate drops, and `Retriable ClickHouse error (Code: 252 ...)` WARN lines appear.
  - **Blast radius**: every table on that worker lags for the duration. Each 252 retry damages history (FM-12.03-1).
  - **Recovery**: none inside the connector. Let it drain, and relieve ClickHouse: raise `parts_to_delay_insert` and `parts_to_throw_insert` for the history tables as an owner-approved server setting, and reduce concurrent load. Do **not** skip the transaction: Invariant I15 item 2 allows that only with a re-synchronisation, and an SCD2 table cannot be re-synchronised from MySQL.
  - **RTO**: rows × (one statement round trip plus part creation) ÷ the workers in flight. Unmeasured, because no history-mode throughput harness exists. Even at an optimistic 1 ms per statement and 10 workers, 180 M rows take about 5 h, far beyond the target.
  - **Test**: GAP: a history-mode throughput harness measuring statements per second and parts per UPDATE.
  - **DEFECT**: history-mode cost is linear in rows with a per-row round trip, so a large transaction cannot be applied within any bounded RTO.

- **FM-12.03-4 More than 4,194,303 records in one sequence window: every later record is refused, forever**
  - **Trigger**: on the sequence version path (every row of a GTID-less source, and the snapshot rows of any source; §3.5.1), more than `2^22 − 1` records share one counter window. A window resets only when the effective time advances by at least 2000 ms (spec 02.03 §3.2), and every row of one large statement carries the same event timestamp. One UPDATE of more than about 4.2 M rows on a GTID-less source is enough.
  - **Behaviour**: `ReplicationHistoryHandler.historyVersion` throws `IllegalStateException("History version for topic ... cannot be encoded: sequence ... yields counter ..., outside the 22-bit discriminator field (Spec 12.03 section 3.5.1)")` for the 4,194,304th record and every later one of the window. The batch fails and is classified `UNKNOWN` (FM-12.03-5), so it is retried forever. After a restart the same window is redelivered and the counter overflows again at the same row.
  - **Detection**: an ERROR `ClickHouseBatchRunnable exception` carrying that message, then a repeating WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN)`, from the first refused batch.
  - **Blast radius**: every table on that worker stops permanently. The offset holds, so nothing is lost, but nothing moves either.
  - **Recovery**: none declared on 2.11.0. Enabling GTIDs on the source moves new transactions to the GTID path, which has no counter. The stuck transaction can only be skipped (`sink-connector-client update_binlog` past it while stopped), and then every SCD2 table it touched must have its current state repaired (FM-12.03-8). Its versions are missing from history.
  - **RTO**: unbounded.
  - **Test**: `ReplicationHistoryVersionDomainTest.underivableOrUnencodableVersionsAreRefused()` pins the refusal. GAP: a test that encodes a 5 M-row single-window transaction.
  - **DEFECT**: a transaction size that is routine in production stops a mode-2 connector on a GTID-less source with no recovery path.

- **FM-12.03-5 A deterministic refusal is retried forever instead of stopping**
  - **Trigger**: an UPDATE or DELETE on a table without a primary key (`History mode cannot close the previous row for topic ...: the record carries no primary key`), a record with neither image, an underivable version, an event millisecond not after the snowflake epoch, or the overflow of FM-12.03-4.
  - **Behaviour**: `IllegalStateException` from `buildUpdateQueryParams` / `historyVersion`. `PreparedStatementExecutor` logs `******* ERROR inserting Batch Database(...), Table(...) *****************` and rethrows it wrapped. `ClickHouseBatchRunnable.run()` classifies it with `ClickHouseErrorClassifier`, finds no ClickHouse code and no terminal cause, and gets `UNKNOWN`. It retries the same batch with `RetryBackoff`, which has no attempt cap. The worker never stops, so the loud FATAL path of spec 03.01 is never taken.
  - **Detection**: the ERROR above within one batch, then a WARN per retry. No exit, and `/status` keeps reporting the connector running.
  - **Blast radius**: every table hashed to that worker stalls indefinitely. Other workers continue. Nothing is lost.
  - **Recovery**: remove the cause, then restart. For a keyless table, add a primary key on the source (spec 01.01, GIPK) or remove the table from the history connector's `table.include.list`. For the others, see FM-12.03-4.
  - **RTO**: unbounded until an operator acts, then a restart plus the retry of the batch.
  - **Test**: `ReplicationHistoryPoisonEventTest.unencodableHistoryVersionStopsTheWorker()` and `ReplicationHistoryPoisonEventTest.updateWithoutPrimaryKeyStopsTheWorker()` (disabled, DEFECT). The refusal itself is pinned by `ReplicationHistoryPoisonEventTest.unencodableHistoryVersionIsRefusedLoudly()` and `ReplicationHistoryHandlerTest.underivableVersionIsRefused()`.
  - **DEFECT**: refusals that can never succeed are classified retriable (spec 10.01 taxonomy), so the pipeline stalls silently instead of failing.

- **FM-12.03-6 No open row is visible: a DELETE writes nothing, an UPDATE writes no close row, silently**
  - **Trigger**: the key's open row is not visible to the open-row predicate. Causes include a zone mismatch (12.02 §7 FM-12.02-1), a key never loaded after a mode change (12.01 §7 FM-12.01-2), a partial bulk close (FM-12.03-7), and out-of-band edits.
  - **Behaviour**: the close and marker SELECTs return no rows. `executeHistoryUpdate` returns `batchResult.length > 0`, which is true for any one-element answer, and `PreparedStatementExecutor` ignores the value. The only trace is DEBUG `Replication history delete executed successfully`. An UPDATE's after row is still written.
  - **Detection**: none.
  - **Blast radius**: a deleted source row stays current in ClickHouse forever. An update leaves a gap in the history.
  - **Recovery**: find the keys with the open-row checksum (spec 11.02 §7 FM-11.02-9), then repair them (FM-12.03-8, current-state step).
  - **RTO**: unbounded until checked, then the key repair.
  - **Test**: GAP: a test in which the statement reports zero written rows for a DELETE and a loud failure is expected. This depends on the JDBC driver reporting written rows for `INSERT ... SELECT`, which is not verified here.
  - **DEFECT**: a lost DELETE is indistinguishable from a successful one.

- **FM-12.03-7 The bulk close of a large table fails half way**
  - **Trigger**: a source `TRUNCATE TABLE` / `DROP TABLE` of a table with many open rows (DDL path `executeHistoryBulkCloses`, or record path `op = t`). The statement meets a memory limit, a timeout or a connection loss.
  - **Behaviour**: one `INSERT ... SELECT * REPLACE ... FROM t FINAL ... UNION ALL ...` reads every open row twice and writes two rows per open row. Its run time is proportional to the open rows. ClickHouse does not make a multi-block `INSERT ... SELECT` atomic (server behaviour, not verified here), so a failure can leave some close rows and some markers written.
    - On the DDL path the failure propagates into the `ddl.retry` loop: `MAX_RETRIES` attempts 10 s apart, then a terminal `DDLReplicationException`.
    - On the record path it logs `Replication-history bulk close for a replicated truncation failed for <db>.<t>` and the batch retries without a cap.
    - A retry re-closes rows whose marker is missing, identically at the same `ts` and `V`, so they collapse. A key whose marker was written without its close row has lost its last closed version: the marker hides the open row from the retry.
  - **Detection**: DDL path, `Error executing DDL` and an error-table row per attempt, then the terminal exception. Record path, the ERROR above.
  - **Blast radius**: replication waits at the DDL barrier until the close succeeds. Some keys may lose their last closed version.
  - **Recovery**: relieve the cause (memory, load), then let the retry run or restart. Repair lost versions with FM-12.03-8, scoped to that table and the event's `ts`.
  - **RTO**: proportional to the open rows (unmeasured), plus up to the retry budget (10 × 10 s by default) per failure.
  - **Test**: `ReplicationHistoryHandlerTest.bulkCloseUsesEventTimeAndVersion()` pins the statement. GAP: a partial-failure test.
  - **DEFECT**: the run time is proportional to the table size, and a partial failure can silently drop closed versions.

- **FM-12.03-8 An SCD2 table has diverged: the bounded repair (MySQL holds no history)**
  - **Trigger**: any failure above, or 12.01 §7 / 12.02 §7, left open rows or closed versions wrong.
  - **Behaviour**: MySQL holds only current rows. `ch-mysql-resync` must not be used (spec 11.04 §6 FM-11.04-9), and a re-snapshot rewrites open rows while leaving damaged history in place. The past versions exist only in the SCD2 table itself, in the audit table (every DML event's `before`/`after` image with binlog coordinates, for `replication.history.ttl` days, 12.04), and in the source binlog within its retention.
  - **Detection**: current state, the open-row checksum (spec 11.02 §7 FM-11.02-9). History, `WHERE _valid_from > _valid_to` rows, and per-key version counts compared with the audit table's UPDATE/DELETE rows for the same window.
  - **Blast radius**: bounded by the affected keys and the time window. The rest of the table is untouched by the repair.
  - **Recovery** (operator-run SQL, reviewed by the table owner; no tool exists):
    1. **Scope.** Fix the window `[T1, T2]` from the incident (restart time and committed `ts_sec`, or the time of the zone or mode change) and the affected keys (the failing checksums, then the keys the audit table shows changing in the window). Stop the connector for the repair, so no event interleaves.
    2. **Current state.** For each key whose open row differs from MySQL, write what the connector's UPDATE would have written (§3.2): the wrong open row closed at `_valid_to = now()`, plus the MySQL row at `(k, 2100-01-01 00:00:00)` with `_operation = 'U'` and `_version = max(_version) + 1` of that key. For a key MySQL no longer has, write a delete marker instead (§3.3). The next real event's version, a snowflake of a later millisecond, still ranks above it.
    3. **History, only within the audit TTL.** Rebuild the closed versions of the affected keys from the audit rows of the window, taken in `(logfile, position, row)` order. Each UPDATE or DELETE row's `before` image is the version it closed, valid until its `_time` (in seconds). Insert each as a close row at `(k, _time)` with a version above the damaged row at that sorting key. Remove the inverted rows of the keys in scope with a scoped `ALTER TABLE ... DELETE WHERE _valid_from > _valid_to AND <key predicate>` (a destructive mutation, limited to the rows in scope). Versions older than the audit TTL cannot be recovered; record that in the incident.
  - **RTO**: unmeasured. It is proportional to the affected keys and the window, not to the table size. Manual work is required.
  - **Test**: GAP: a repair tool implementing steps 1–3, with offline tests of the statements it emits.
  - **DEFECT**: there is no tooling for the only repair of an SCD2 table, and damage older than the audit TTL is permanent.

Summary: 8 failure modes, 8 DEFECT, 7 GAP.
