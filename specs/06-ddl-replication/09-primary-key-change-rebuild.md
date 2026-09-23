# Spec 06.09: Primary Key Change — Replica Rebuild at the DDL Barrier

## 1. Executive Summary & Purpose
Specifies how a source `ALTER TABLE` that changes a replicated table's row
identity (`ADD PRIMARY KEY` of a different column set, `DROP PRIMARY KEY`, the
migration shape `DROP PRIMARY KEY, ADD COLUMN id ... AUTO_INCREMENT, ADD PRIMARY
KEY (id)`) is **followed** on ClickHouse instead of stopping the pipeline: the
connector rebuilds the target table under the new sorting key at the DDL
barrier, the same way MySQL itself executes the statement.

This is the MySQL-to-MySQL replication contract adapted to a ClickHouse replica:

1. **MySQL cannot change a clustered index in place either.** "Restructuring
   the clustered index always requires copying of table data. [...] MySQL creates
   a new clustered index by copying the existing data from the original table to
   a temporary table that has the desired index structure. Once the data is
   completely copied to the temporary table, the original table is renamed with
   a different temporary table name. The temporary table comprising the new
   clustered index is renamed with the name of the original table, and the
   original table is dropped from the database." (MySQL 8.0 Reference Manual
   §17.12.1 *Online DDL Operations*, table 17.17: adding a primary key —
   *Rebuilds Table: Yes*; dropping a primary key — *Rebuilds Table: Yes*;
   dropping and adding another — *Rebuilds Table: Yes*.)
2. **The DDL is a barrier on the replica.** The statement is written to the
   binary log as a statement; the write-set dependency tracker records a
   serialization point for every DDL, so a multithreaded applier finishes every
   earlier transaction before it and starts none after it until it completes
   (`binlog_transaction_dependency_tracking`, *Binary Logging Options and
   Variables*). The replica then re-executes the same rebuild. The connector's
   existing DDL barrier (Spec 06.01 / 06.02, Invariant I5) is that serialization
   point.
3. **Values that only the source knows are read from the source.** MySQL
   documents that a replica re-numbering an added `AUTO_INCREMENT` column "might
   not produce the same ordering of the rows on the replica and the source"
   (§15.1.9.3 *ALTER TABLE Examples*): the MySQL-to-MySQL contract is weaker
   than the Prime Directive. The replica must hold the source's values, so the
   connector reads the new key values from MySQL, keyed by the old identity,
   through a read-only `SELECT` — never by re-deriving them.

Before this spec, Spec 06.07 §3.1 rule 3 refused the statement loudly and named
a manual rebuild; that manual procedure (create the table with the new
`ORDER BY`, load the rows, count-reconcile, swap, keep the old table until
verified) is what this spec automates.

---

## 2. Codebase Mapping on 2.11.0
- **Plan (translator side)**:
  `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/PrimaryKeyRebuildPlan.java`
  — the value object `enforcePrimaryKeyPolicy` produces instead of throwing
  (`MySqlDDLParserListenerImpl.enforcePrimaryKeyPolicy`,
  `MySqlDDLParserListenerImpl.primaryKeyRebuildPlan()`).
- **Handoff**: `DDLParserService.primaryKeyRebuildPlan()` (default `null`;
  `MySQLDDLParserService` returns the plan of the last `parseSql`,
  `PostgreSQLDDLParserService` never produces one).
- **Executor**:
  `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/PrimaryKeyRebuild.java`
  (`execute`, `rewriteCreateStatement`, `loadSourceKeyMap`), invoked by
  `DebeziumChangeEventCapture.performDDLOperation` right after `executeDDL`
  returns for the statement and before the cache invalidation of Spec 06.08.
- **Source access**: `KeylessTablePreflight.jdbcUrl` and
  `KeylessTablePreflight.assertReadOnlySql` (same package) — the connector stays
  read-only against MySQL.
- **Configuration**: `ddl.primary.key.rebuild`
  (`SinkConnectorLightWeightConfig.DDL_PRIMARY_KEY_REBUILD`, default `true`).
  `false` restores Spec 06.07 §3.1 rule 3 (loud refusal, nothing emitted).
  `disable.drop.truncate` (Spec 06.08 §3.3) decides whether the retired copy is
  dropped after the swap.
- **Formal model**: `formal_specs/lean/Replication/PkRebuild.lean`;
  `Clause.primaryKeyChange` in `formal_specs/lean/Replication/DdlTranslation.lean`
  now translates to `Emission.rebuild`.

---

## 3. Operational Specification

### 3.1 When a rebuild is planned
`enforcePrimaryKeyPolicy` (Spec 06.07 §3.1) keeps rules 1 and 2 unchanged:
an unknown sorting key skips the key clauses, a restatement is a no-op. Rule 3
(net identity differs from the replica's sorting key) now:

- with `ddl.primary.key.rebuild=true` (default): records a
  `PrimaryKeyRebuildPlan` and lets the statement's other clauses be emitted as
  usual (the `ADD COLUMN IF NOT EXISTS new_id ...` of the migration shape is
  applied to the current table first; Spec 06.04 §3.1);
- with `ddl.primary.key.rebuild=false`: raises `DDLReplicationException` exactly
  as before (Invariant I9).

The plan carries: destination database and clean table name; the **old key**
(the current sorting key, connector columns removed, Spec 06.07 §3.1); the **new
key** — the declared column list, or, for `DROP PRIMARY KEY` without a
replacement, the identity a keyless table has on this replica: every stored
column of the table after the statement in position order (Spec 06.05 §3.6 /
Spec 08.05 §3.2, the same fallback the CREATE path uses — the translator cannot
see the source's `UNIQUE` keys from ClickHouse); and, for each new-key column,
its **provenance**:

| Provenance | Condition | Rebuild reads it from |
|---|---|---|
| `EXISTING` | the column exists in the ClickHouse table before this statement | the old table |
| `ADDED_DEFAULTED` | added by this statement without `AUTO_INCREMENT` | the old table (ClickHouse back-fills the `ADD COLUMN` default for old rows, as MySQL back-fills the column `DEFAULT`) |
| `SOURCE_VALUED` | added by this statement with `AUTO_INCREMENT` | the source, keyed by the old identity (§3.4) |

A new-key column that is none of these (a column the statement neither adds nor
the replica has) is loud: the replica cannot represent the identity
(`DDLReplicationException`, nothing emitted).

### 3.2 Preconditions (loud when not met)
The rebuild is only defined for the `ReplacingMergeTree(_version[, is_deleted])`
and `ReplicatedReplacingMergeTree(...)` targets the connector creates
(`isNewReplacingMergeTreeEngine`). It refuses, with the Spec 06.07 rule 3
message, when:
1. `replication.history.enable=true` (SCD2 tables key on `_deleted_time`);
2. the target is not a `ReplacingMergeTree` family engine, or is the legacy
   sign-based engine;
3. the target is `Replicated*` with a literal ZooKeeper path that does not use
   the `{table}` or `{uuid}` macro — the rebuilt table would collide with the
   old one's path;
4. a `SOURCE_VALUED` column or an old-key column is neither an integer nor a
   string type on both engines — the only types the key map binds (§3.4) without
   a second value-conversion path;
5. any old-key column is `Nullable` when a source key map is required — NULL
   never equals NULL in a JOIN, so such rows could not be matched.

Items 1–3 and the ClickHouse side of 4–5 are checked from `system.tables` /
`system.columns` BEFORE any rebuild statement is executed, so the old table's
rows and key are untouched by a refusal; the statement's own representable
clauses (`ADD COLUMN IF NOT EXISTS ...`, idempotent) have already been applied
by `executeDDL` at that point, exactly as they were under the former rule 3
when the ALTER was not refused. The MySQL side of item 4 can only be judged
from the source `ResultSetMetaData`, i.e. at step 4 of §3.3, after the scratch
tables `S` and `K` exist; a refusal there leaves them for step 1 of the next
attempt to remove.

### 3.3 Protocol
All statements run on the writer's connection, inside the DDL barrier
(`drainBeforeDDL` has already proven quiescence; the pool stays paused until the
`finally` in `processEveryChangeRecord`). `T` is the target, `S = T__pk_rebuild_<epochMs>`,
`K = T__pk_rebuild_keys_<epochMs>`.

1. **Clean previous attempt.** Tables named `T__pk_rebuild_%` /
   `T__pk_rebuild_keys_%` left by a failed earlier attempt for the same table
   are dropped (connector-owned scratch, never source data; each drop is logged
   with the table name).
2. **Apply the statement's other clauses** (`executeDDL` of the translated
   `ALTER`, unchanged): `T` now has every column of the post-statement source
   table.
3. **Derive the new definition.** `SHOW CREATE TABLE T` is rewritten by
   `PrimaryKeyRebuild.rewriteCreateStatement`: the table name becomes `S`; the
   `ORDER BY` clause becomes the new key; an existing `PRIMARY KEY` clause is
   replaced by the new key; every new-key column declared `Nullable(X)` is
   re-declared `X` (MySQL makes primary-key columns `NOT NULL`; a NULL the
   replica still holds becomes the type default, as `ALGORITHM=COPY` does) —
   except when the new key is the keyless all-columns fallback, where nullable
   columns stay `Nullable` and `allow_nullable_key=1` is added to `SETTINGS`
   (Spec 06.05 §3.6); engine, `PARTITION BY`, `SAMPLE BY`, `TTL` and every other
   setting are kept verbatim. `S` is created with the result.
4. **Source key map (SOURCE_VALUED columns only, §3.4).** `K` is created as
   `MergeTree ORDER BY (<old key>)` with the old-key columns typed as in `T` and
   the source-valued columns typed as in `S`, and filled from the source.
5. **Copy the live rows, versions preserved.**
   - without a key map:
     `INSERT INTO S (<cols>) SELECT <cols> FROM T FINAL WHERE <live>`;
   - with a key map:
     `INSERT INTO S (<cols>) SELECT <T cols except source-valued>, <K source-valued> FROM T AS o FINAL INNER JOIN K AS k ON <old key equality> WHERE <live>`
     (ClickHouse requires the alias before `FINAL`); the number of live rows
     the INNER JOIN drops (`count()` of the live rows of `T` minus the copied
     count) is logged;

   where `<cols>` is every ordinary column of `S` (`ALIAS`/`MATERIALIZED`
   excluded) including `_version` and the delete flag, and `<live>` is
   `is_deleted = 0` (the delete-flag column named by the engine; no predicate
   when the engine has only `_version`). Tombstones are not copied: under the
   new identity they retire nothing, and the MySQL rebuild copies live rows
   only.
6. **Count-reconcile.** `count()` of `S` must equal the count of the exact
   `SELECT` that fed it (run separately, both after the copy). A mismatch is
   `DDLReplicationException`; `S` and `K` are left in place for inspection and
   `T` is untouched.
7. **Swap.** `EXCHANGE TABLES T AND S` when the database engine is `Atomic` or
   `Replicated` (`system.databases.engine`); otherwise
   `RENAME TABLE T TO T__pk_retired_<epochMs>, S TO T` (one statement).
8. **Retire.** The retired copy (`S` after an `EXCHANGE`, `T__pk_retired_*`
   after a `RENAME`) and `K` are dropped, as MySQL drops the original table —
   unless `disable.drop.truncate=true`, in which case both are kept and their
   names logged (the operator has asked that the connector never drop data).
9. The Spec 06.08 cache invalidation for `T` runs as for any DDL, so the next
   batch re-reads the table's columns, engine and sorting key.

Every step is logged at INFO with the statement it ran and the row counts of
steps 5–6; a failure at any step is `DDLReplicationException` (terminal, Spec
06.08 §3.1) so no offset is committed past the DDL.

### 3.4 Source key map
For the `SOURCE_VALUED` columns the connector runs, on a fresh JDBC connection
built by `KeylessTablePreflight.jdbcUrl` from `database.hostname` /
`database.port` / `database.user` / `database.password`, the single statement

```
SELECT <old key columns>, <source-valued columns> FROM `<source db>`.`<table>`
```

(`assertReadOnlySql` is applied; streaming fetch; the connection is read-only).
Each row is bound into `K` with `setObject` — integers and strings only (§3.2
item 4). The map is a function from old identity to new values because the old
key was the source's primary key (or the replica's all-columns identity) when
those rows were written.

The source is read at the time of the rebuild, i.e. at a binlog position at or
after the DDL. Rows changed on the source between the DDL and the read are
reconciled by their own events, which follow the DDL in the binlog and carry the
new key in their row image (`binlog_row_image=FULL`, Spec 01.06): a row whose
old identity is no longer on the source is dropped by the INNER JOIN and is
re-inserted under the new key by its later UPDATE, or receives its later DELETE
tombstone; a row inserted after the DDL is absent from `T` and arrives by its
INSERT. Rows the JOIN drops are counted and logged.

### 3.5 Redelivery and restart
The DDL event is at-least-once (Spec 01.06). A redelivered statement finds the
`ADD COLUMN IF NOT EXISTS` idempotent and the sorting key already equal to the
declared key, i.e. a restatement (Spec 06.07 §3.1 rule 2): no second rebuild.
A failure before step 7 leaves `T` keyed by the old identity, so the retried
statement rebuilds again after step 1 removed the scratch tables. A failure
between step 7 and step 8 leaves the rebuilt `T` plus a retired copy that the
next attempt's step 1 does not touch (it is not a `__pk_rebuild_` name after a
`RENAME`, and after an `EXCHANGE` it is — and is dropped as scratch, its data
being the pre-rebuild state already superseded).

### 3.6 Late-committing transactions
The rebuild copies `_version` unchanged and the DDL is a barrier: every row
event before the DDL has been written and every event after it is versioned
above the run's floor (Spec 02.02), so no re-versioning happens and Invariant I2
is unaffected.

---

## 4. Invariants Preserved
- **I3 (Eventual Convergence)**: the copy keeps the FINAL live row set with its
  versions and re-keys it by a function of the old identity (source values),
  so `FINAL` under the new key holds exactly the rows MySQL holds after the
  statement; later events converge as before (`PkRebuild.lean`).
- **I4 (Sorting Key Mutation Integrity)**: a change of identity is applied as
  an identity change, not as a value change under the old key.
- **I5 (DDL Barrier Quiescence)**: the rebuild runs strictly inside the
  existing barrier, like MySQL's serialization point.
- **I9 (Loud Failure)**: every precondition failure, count mismatch or failed
  statement is `DDLReplicationException`; nothing is skipped and no offset
  passes an unrebuilt table.
- **I6 (Column Authority)**: source-generated key values are read from the
  source, never re-derived on the replica.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testAddPrimaryKeyThatChangesIdentityPlansRebuild()` — the shapes of Spec 06.07's former loud set now emit their representable clauses and produce a plan naming old key, new key and per-column provenance (`AUTO_INCREMENT` added column is `SOURCE_VALUED`); the restatement still plans nothing.
- `MySqlDDLParserListenerImplTest.testDropPrimaryKeyPlansRebuild()` — a lone `DROP PRIMARY KEY` plans the all-columns fallback key; `DROP ..., ADD PRIMARY KEY (same)` plans nothing; unknown key: skipped as before.
- `MySqlDDLParserListenerImplTest.testPrimaryKeyChangeIsLoudWhenRebuildDisabled()` — `ddl.primary.key.rebuild=false` restores the loud refusal, nothing emitted.
- `PrimaryKeyRebuildTest.rewritesOrderByAndTableName()`, `PrimaryKeyRebuildTest.rewriteKeepsPartitionTtlAndSettings()`, `PrimaryKeyRebuildTest.rewriteMakesKeyColumnsNonNullable()`, `PrimaryKeyRebuildTest.keylessFallbackAddsAllowNullableKey()` — `rewriteCreateStatement` on rendered `SHOW CREATE TABLE` shapes.
- `PrimaryKeyRebuildTest.localCopyStatementSequence()` — recording connection: CREATE, INSERT…SELECT FINAL WHERE is_deleted = 0, both counts, EXCHANGE, DROP, in that order; no source connection opened.
- `PrimaryKeyRebuildTest.sourceKeyMapJoinSequence()` — a `SOURCE_VALUED` column: the key-map table, the source `SELECT`, the JOIN copy, and the source-valued column taken from the map.
- `PrimaryKeyRebuildTest.countMismatchAbortsBeforeSwap()` — a count mismatch throws `DDLReplicationException` and no EXCHANGE/RENAME/DROP is issued.
- `PrimaryKeyRebuildTest.retiredCopyKeptWhenDropTruncateDisabled()` — `disable.drop.truncate=true`: swap happens, nothing is dropped.
- `PrimaryKeyRebuildTest.replicatedLiteralPathIsLoud()`, `PrimaryKeyRebuildTest.nullableOldKeyWithSourceMapIsLoud()` — §3.2 preconditions.
- End-to-end (MySQL 8.0 → connector → ClickHouse 24.8, `csc_e2e_suite.sh` case `t_pk`): the production migration `DROP PRIMARY KEY, ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (id)` followed by INSERT/UPDATE/DELETE; value-level equality and `ORDER BY id` on the replica. Case `t_pk2`: `DROP PRIMARY KEY, ADD PRIMARY KEY (existing column)` without a source read.
- Formal: `primary_key_change_rebuilds`, `rebuild_never_bare` in `DdlTranslation.lean`; `rebuild_preserves_live_rows`, `rebuild_view_eq` in `PkRebuild.lean`.
