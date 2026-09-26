# Spec 12.03: SCD2 Write Protocol — INSERT, UPDATE (3-SELECT) and DELETE (2-SELECT)

## 1. Executive Summary & Purpose
Specifies, statement by statement, how a mode-2 connector
(`replication.history.enable=true`, spec 12.01) turns each source DML event
into rows of the SCD2 table of spec 12.02. The standard protocol (bind one
row per event, spec 04.x) is replaced for UPDATE and DELETE by an
`INSERT ... SELECT ... UNION ALL ...` statement that **reads the current open
row back out of the target** and re-inserts it closed, then appends the new
version. The protocol has three properties the rest of the system relies on
— continuity of validity ranges, exactly one open row per key under `FINAL`,
and the current-state view being a correct replica — and four defects that
this spec records with machine-checked counterexamples so that they can be
reasoned about before the code is changed.

---

## 2. Codebase Mapping on 2.11.0
- **Dispatch**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` — `executePreparedStatement`: constructs a `ReplicationHistoryHandler` per batch when `replication.history.enable=true`; routes DELETE (after flushing the staged statement) and UPDATE (inline, no flush) to `executeHistoryUpdate`; suppresses the relocation tombstone in history mode.
- **Statement builder**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/ReplicationHistoryHandler.java` — `buildUpdateQueryParams` (sentinel, event time, version, whole primary key from the after image), `generateUpdateQuery`, `generateDeleteQuery`, `executeHistoryUpdate`.
- **SQL text**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java` — `getInsertQueryForUpdate` (three SELECTs), `getInsertQueryForDelete` (two SELECTs), `formatPrimaryKeyPredicate`, `isTemporalTrackingColumn`, `isConnectorManagedColumn`.
- **Column binding**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java` — `handleReplicationHistoryColumns` (INSERT path values of `_valid_from`, `_valid_to`, `_operation`), the history-mode exemptions in `requireEngineColumnPlaceholder`, `requireDeleteColumn` and the unknown-column branch.
- **Grouping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java` — one grouped entry per UPDATE (04.01 §3.2, 04.04 §3.1).
- **Version source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/SnowFlakeId.java` — `generate(ts_ms, gtid, false)`; `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java` — `getTsSec` (source offset `ts_sec`), `getTs_ms`, `getGtid`, `getPrimaryKey`, `calculateVersion` (the standard-path version bound on INSERT).
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `insertRow`, `closeRow`, `afterRow`, `beforeRow`, `deleteMarker`, `updateRows`, `deleteRows`, `applyInsert`, `applyUpdate`, `applyDelete`, `applyUpdateStaged`.

---

## 3. Operational Specification

Notation: `k` = the source primary key (all its columns), `ts` = the event's
`ts_sec` rendered as `DateTime` "without timezone adjustment" from the source
timezone to the server timezone, `S` = the sentinel `2100-01-01 00:00:00`
(12.02 §3.1), `V = SnowFlakeId.generate(ts_ms, gtid, false)` (§3.5),
`tz` = the resolved server timezone id. All FINAL reads below are
`SELECT ... FROM <table> FINAL WHERE <k predicate> AND `_valid_to` =
toDateTime('S', 'tz') [AND `is_deleted` = 0]` — the **open-row predicate**;
the `is_deleted` conjunct is present when the table has that column.

### 3.1 INSERT / snapshot READ (`op = c` / `op = r`)
Standard path (`CDC_RECORD_STATE_AFTER`): the after image is bound into the
per-table INSERT template. `handleReplicationHistoryColumns` binds:
- `_valid_from` = `ts`;
- `_valid_to` = `S`;
- `_operation` = the single-letter code `'C'` (or `'r'` for a snapshot row);
- `is_deleted` = 0 and `_version` = the record's **standard** version
  (`ClickHouseStruct.calculateVersion`, spec 02.01 — not `V`).
Exactly one row: `(k, image, ts, S, is_deleted=0, version_std, 'C')`.
Formal: `Replication.History.insertRow`.

### 3.2 UPDATE (`op = u`) — one statement, three SELECTs
Dispatch (`CDC_RECORD_STATE_BOTH`):
1. On a `CollapsingMergeTree` target the before image is staged as a `-1`
   cancel row first (05.04) — engine-generic, not history-specific.
2. The sorting-key relocation tombstone of 05.02 is **skipped**
   (`replicationHistoryHandler == null && ...`): history mode retires the
   previous version itself.
3. `executeHistoryUpdate(isDelete=false)` builds and executes, on its own
   `PreparedStatement`, **immediately and inline** (no `executeBatch()` of the
   shared statement first):

```
INSERT INTO `t`(<all columns>)
SELECT <close>   FROM `t` FINAL WHERE <k> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0   -- (1)
UNION ALL
SELECT <after>                                                                                            -- (2) no FROM: parameters
UNION ALL
SELECT <before>  FROM `t` FINAL WHERE <k> AND `_valid_to` = toDateTime('S','tz') AND `is_deleted` = 0   -- (3)
```

| SELECT | data columns | `_valid_from` | `_valid_to` | `is_deleted` | `_version` | `_operation` |
|---|---|---|---|---|---|---|
| (1) close | copied from the open row | copied | `ts` | `0` | `V` | copied |
| (2) after | **bound parameters** from the after image | `ts` (bound) | `S` (bound) | `0` (literal) | `V+1` (literal) | `'U'` (literal) |
| (3) before | copied from the open row | copied | `ts` | `1` | `V` | `'U'` |

Rows produced when the open row is visible: (1) the previous version, now
closed at `ts`; (2) the new open version at `(k, S)` with `V+1`, which
supersedes the previous open row under `ReplacingMergeTree` (`FINAL` keeps the
higher `_version`); (3) a deleted copy of the previous version at `(k, ts)`.
Rows (1) and (3) share the sorting key `(k, ts)` **and** the version `V` and
differ only in `is_deleted` — see Gap G-12.03-2.
Formal: `Replication.History.closeRow`, `afterRow`, `beforeRow`,
`updateRows`, `applyUpdate`; properties
`Replication.History.update_supersedes_open_row`,
`Replication.History.closed_row_continuity`.

**Key predicate.** `buildUpdateQueryParams` takes the primary-key values from
the **after** image (`record.getAfterStruct()`, falling back to the before
image only when the after image is null). A record without a primary key is
refused with `IllegalStateException` naming the topic (I9; 02.01 §3.5 a).
Every key column is used (`formatPrimaryKeyPredicate`, composite keys,
02.01 §3.5 a).

**Binding.** Only SELECT (2) has parameters: the after image's data columns
and `_valid_from` / `_valid_to` (`PreparedStatementFieldMapperUnboundColumnTest.testTemporalColumnsAreBoundParameters()`);
`_version`, `is_deleted`, `_operation` are SQL literals and deliberately have
no placeholder, which is why `requireEngineColumnPlaceholder` is exempt in
history mode (`PreparedStatementFieldMapperEngineColumnTest.testHistoryModeStatementWithLiteralEngineColumnsIsAccepted()`).
A ClickHouse column absent from the record's schema is bound to NULL in
history mode instead of raising `StaleSchemaCacheException`
(`PreparedStatementFieldMapper`, unknown-column branch) — **Gap G-12.03-5**:
the loud stale-cache protection of 08.03 does not apply to SCD2 tables.

### 3.3 DELETE (`op = d`) — flush, then one statement, two SELECTs
Dispatch (`CDC_RECORD_STATE_BEFORE` with `DELETE`):
1. `ps.executeBatch()` — **the staged INSERTs of the batch are flushed
   first**, so that a row created and deleted in the same batch is visible to
   the SELECT below (the code comment names the silent-drop defect this
   prevents).
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
| (2) marker | copied from the open row (**not** from the event's before image) | `ts` | `S` | `1` | `V+1` | `'D'` |

No parameters are bound. The marker at `(k, S)` with `V+1` supersedes the
open row and, being deleted, hides the key under `FINAL`; the closed row
keeps the last version's validity range `[_valid_from, ts)`. If no open row
is visible, **both SELECTs return nothing and the DELETE writes zero rows
with no error** (the flush of step 1 exists to make this impossible within a
batch; across batches the open row is always flushed).
Formal: `Replication.History.deleteMarker`, `deleteRows`, `applyDelete`;
properties `Replication.History.delete_hides_open_row`,
`Replication.History.no_open_row_after_delete_marker`.

### 3.4 TRUNCATE (`op = t`)
Not history-aware: the replicated `TRUNCATE TABLE` of 04.05 is executed
against the SCD2 table and removes **every version**, closed ones included.
**Gap G-12.03-6** — a source truncate erases the history window.

### 3.5 Versions
- INSERT rows carry the standard version of 02.01 (`calculateVersion`).
- Close / after / before / marker rows carry
  `V = SnowFlakeId.generate(ts_ms, gtid, false)` and `V+1`: 41 timestamp
  bits (milliseconds since the Snowflake epoch) above 22 GTID transaction
  bits — a different formula from the lightweight sequence path of 02.01
  §3.2 (`effectiveTs * 10^6 + seq`), and one that ignores the commit-order
  floor of 02.02.
- The two domains meet at the sorting key `(k, S)`: the after row (`V+1`)
  must exceed the INSERT row's standard version for the UPDATE to take
  effect. When the standard path itself is the Snowflake path (a GTID is
  present and `snowflake.id=true`, 02.01 §3.1 path 1) both values are
  Snowflake ids of successive `ts_ms` and the order is the event order; with
  `gtid_mode=OFF` the GTID field is `-1` for every history row, so two
  history rows of one key within one millisecond collide (02.01 §3.5 b,
  **Gap G-12.03-4**, unchanged here). The mixed-domain comparison is not
  covered by any test or theorem (**coverage gap**).

### 3.6 Timezones
`ts` and `S` are converted with `convertWithoutTimeZoneAdjustment` from the
source timezone (`source.datetime.timezone`, default UTC) to the server
timezone; the statement literals wrap them in `toDateTime(..., 'tz')`. See
12.02 §3.1 / Gap G-12.02-1 for the equality requirement between the
statement timezone and the column timezone.

### 3.7 Operation codes
SCD2 tables store the **single-letter** codes from
`CDC_OPERATION.getOperation()`: `'C'`, `'r'`, `'U'`, `'D'` — the field mapper
and both statements agree. The audit table of 12.04 stores the enum **name**
(`CREATE`, `UPDATE`, `DELETE`, `READ`); a consumer joining the two tables
must translate (02.01 §3.5 c).

### 3.8 What a reader must do
- **Current state**: `SELECT ... FROM t FINAL WHERE _valid_to =
  toDateTime('S') AND is_deleted = 0` — equals the source table (I3 on open
  rows, 12.02 §4).
- **As-of `T`**: `WHERE _valid_from <= T AND T < _valid_to AND is_deleted = 0`
  (with `FINAL`), subject to Gap G-12.03-2 for versions closed by an UPDATE.
- Row counts of a mode-2 table are meaningless for verification (11.02 Rule
  0): they count versions.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)** on the open rows:
  `Replication.History.update_supersedes_open_row` (after an UPDATE the live
  row at `(k, S)` is the new image) and
  `Replication.History.delete_hides_open_row` (after a DELETE there is none).
  Hypothesis of both: every existing row at `(k, S)` has version `≤ V` —
  the version-domain condition of §3.5.
- **Invariant I4 (Sorting Key Mutation Integrity)**: not applicable as
  stated — the relocation tombstone is suppressed; the SCD2 close row is the
  retirement mechanism. It does not retire the old key on a key change
  (Gap G-12.03-3).
- **Invariant I9 (Loud Failure)**: the missing-primary-key refusal is loud;
  the zero-row DELETE (§3.3), the NULL-bound unknown column (Gap G-12.03-5)
  and the unflushed UPDATE (Gap G-12.03-1) are silent — recorded.
- **Continuity**: the closed version's `_valid_to` equals the successor's
  `_valid_from` (`Replication.History.closed_row_continuity`); ranges never
  overlap and never leave a gap.
- **Uniqueness**: `Replication.History.at_most_one_live_row_per_sort_key`.

---

## 5. Verification Criteria
- `QueryFormatterTest.testGetInsertQueryForUpdate()`, `QueryFormatterTest.testGetInsertQueryForUpdateWithStringPrimaryKey()` — §3.2 statement text: two `UNION ALL`, open-row predicate with the sentinel and `is_deleted = 0`, close row at the binlog timestamp, `_valid_from` / `_valid_to` in the parameter map.
- `QueryFormatterTest.testGetInsertQueryForDelete()`, `QueryFormatterTest.testGetInsertQueryForDeleteWithStringPrimaryKey()` — §3.3: one `UNION ALL`, `'D'`, empty parameter map.
- `QueryFormatterTest.updateAndDeleteQueriesUseEveryPrimaryKeyColumn()`, `ReplicationHistoryHandlerTest.compositePrimaryKeyClosesOnlyTheMatchingRow()` — composite-key predicate in both table-reading SELECTs.
- `ReplicationHistoryHandlerTest.testBuildUpdateQueryParams()`, `ReplicationHistoryHandlerTest.testGenerateUpdateQuery()`, `ReplicationHistoryHandlerTest.testGenerateDeleteQuery()` — §3.2/§3.3 parameter derivation and SELECT counts.
- `PreparedStatementFieldMapperUnboundColumnTest.testTemporalColumnsAreBoundParameters()` — §3.2 binding of `_valid_from` / `_valid_to`.
- `PreparedStatementFieldMapperEngineColumnTest.testHistoryModeStatementWithLiteralEngineColumnsIsAccepted()` — §3.2 literal engine columns.
- `QueryFormatterOperationColumnTest.testOperationColumnSurvives()` — `_operation` stays in the INSERT column list (a prior omission left it empty, so DELETEs were indistinguishable from inserts).
- `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()`, `GroupInsertQueryHistoryMultiRowTest.recordsAfterTheFirstUpdateSurviveInHistoryMode()`, `GroupInsertQueryHistoryMultiRowTest.allRowsOfAMultiRowUpdateAreGroupedInHistoryMode()` — one grouped entry per UPDATE, all rows of a multi-row statement.
- `VersionHistoryIT.testValidToValidFromColumnsOnUpdateDelete()` — §3.1–§3.3 end to end without `FINAL`: after INSERT one open row; after UPDATE the close row (`is_deleted=0`), the new open row, and the deleted before copy (`is_deleted=1`, still showing the old salary); after DELETE a closed row and a `'D'` marker; after `OPTIMIZE ... FINAL` no active row.
- `VersionHistoryInitialIT.testValidToValidFromColumnsOnUpdateDeleteWithInitialSnapshot()` — the same protocol read **with** `FINAL`: exactly two rows after the UPDATE (closed original + open new) — i.e. FINAL at `(k, ts)` kept the close row over the before copy in that run (Gap G-12.03-2 is order-dependent, not deterministic) — and the delete marker hidden after the DELETE.
- `VersionHistoryIT.testDecimalPrecisionOnUpdate()` — bound decimal columns in SELECT (2) keep their precision (`CAST(?, 'Decimal(...)')`, 07.02).
- Lean (`formal_specs/lean/Replication/History.lean`): `Replication.History.update_supersedes_open_row`, `Replication.History.delete_hides_open_row`, `Replication.History.closed_row_continuity`, `Replication.History.no_open_row_after_delete_marker`, `Replication.History.at_most_one_live_row_per_sort_key`; gap witnesses `Replication.History.close_and_before_share_sort_key_and_version`, `Replication.History.closed_history_row_depends_on_insert_order`, `Replication.History.staged_insert_writes_no_closed_row`, `Replication.History.flushed_insert_writes_closed_row`, `Replication.History.staged_update_current_view_still_correct`, `Replication.History.update_only_closes_after_image_key`.
- **Coverage gaps**: no test creates and updates a row inside one batch (G-12.03-1), changes a primary-key column in history mode (G-12.03-3), truncates (G-12.03-6), or mixes the two version domains of §3.5.

---

## 6. Gaps recorded (not fixed here)
| Id | Where | Behaviour | Consequence | Witness |
|---|---|---|---|---|
| G-12.03-1 | `PreparedStatementExecutor` UPDATE branch | `executeHistoryUpdate` runs inline **without** the `executeBatch()` flush that the DELETE branch performs | when a row's INSERT and its UPDATE land in one batch, SELECTs (1) and (3) see no open row: the pre-update version is **never written to the history**; the current-state view is still correct, so counts and current-value checks cannot detect it | `Replication.History.staged_insert_writes_no_closed_row`, `Replication.History.staged_update_current_view_still_correct` |
| G-12.03-2 | `QueryFormatter.getInsertQueryForUpdate` | close row (1) and before copy (3) have the same sorting key `(k, ts)` and the same `_version V`, opposite `is_deleted` | which of the two `FINAL` keeps is decided by physical insertion order inside one `INSERT ... SELECT ... UNION ALL`, which ClickHouse does not guarantee; the closed history version may be invisible under `FINAL` | `Replication.History.close_and_before_share_sort_key_and_version`, `Replication.History.closed_history_row_depends_on_insert_order` |
| G-12.03-3 | `ReplicationHistoryHandler.buildUpdateQueryParams` | the close predicate uses the **after** image's key | an UPDATE that changes a primary-key column closes nothing at the old key: the old key keeps an open row forever and the new key gets an open row — two current rows for one source row | `Replication.History.update_only_closes_after_image_key` |
| G-12.03-4 | `SnowFlakeId.generate(ts_ms, gtid, false)` | history versions ignore the sequence number and the commit floor; GTID field is `-1` without GTIDs | intra-millisecond collisions; mixed version domains at `(k, S)` (§3.5) | 02.01 §3.5 b |
| G-12.03-5 | `PreparedStatementFieldMapper` unknown-column branch | a table column absent from the record is bound to NULL in history mode | the stale-schema-cache defence of 08.03 is disabled for SCD2 tables | — |
| G-12.03-6 | `PreparedStatementExecutor` TRUNCATE segment | truncation is applied to the SCD2 table | all closed versions are lost | — |
