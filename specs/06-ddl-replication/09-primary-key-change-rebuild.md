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
  — `swap` (the metadata-only phase, §3.3.1; `rewriteCreateStatement`),
  invoked by `DebeziumChangeEventCapture.performDDLOperation` right after
  `executeDDL` returns for the statement and before the cache invalidation of
  Spec 06.08; `PrimaryKeyBackfill` (same package) — the online copy, §3.3.2
  (`run`, `loadSourceKeyMap`, `completenessCheck`, `resumePending`), executed
  on the connector's single `pk-rebuild-backfill` thread owned by
  `DebeziumChangeEventCapture` and resumed at engine start.
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

#### 3.1.1 Clauses that touch an old-key column are deferred to the rebuilt table
ClickHouse rejects every `DROP`, type change or rename of a sorting-key column
on the existing table (`Code: 524`/`Code: 47`), while MySQL applies them inside
the same clustered-index rebuild. The translator therefore never emits such a
clause against the current table when a rebuild is planned. Instead the plan
carries them as **deferred clauses** — ClickHouse `ALTER TABLE` clauses to
apply to the empty rebuilt table before the copy (§3.3 step 3b):

| Source clause on an old-key column | Plan | Deferred clause |
|---|---|---|
| `DROP COLUMN k` (e.g. the GIPK promotion `DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)`) | `k` removed from the copied column set (and from the key when it was a key column) | `DROP COLUMN IF EXISTS k` on the empty rebuilt table (§3.3 step 3b) |
| `MODIFY k <wider or non-comparable type>` (Spec 06.05 §3.4 rule 3) | identity unchanged (a rebuild under the same key, §3.1.2); `k` re-typed | folded into the `CREATE` of the rebuilt table (§3.3 step 3) — ClickHouse refuses `MODIFY` of a key column even on an empty table |
| `CHANGE k k2 <type>` / `RENAME COLUMN k TO k2` | new key names `k2` where the old named `k`; the copy reads `o.k AS k2` | folded into the `CREATE` of the rebuilt table (column declared as `k2`, `ORDER BY` names `k2`) |
| `MODIFY k ... FIRST` / `CHANGE k k2 ... AFTER c` — a position on a deferred clause | the plan records `k'` (the name on the rebuilt table) → `FIRST` / `AFTER c` (`positionedColumns`) | `MODIFY COLUMN k' <type as declared on the rebuilt table> FIRST` / `AFTER c` executed on the empty rebuilt table (§3.3 step 3b): a `MODIFY` restating the identical type with a position is accepted on a sorting-key column as a metadata-only reorder (measured on 24.8.14); the type is read from the rebuilt table, never taken from the plan, because any other type is `Code: 524`. A `MODIFY` of a key column to a same-or-narrower type with a position is not deferred at all: it is restated against the current table (Spec 06.05 §3.4 rule 2) |

Same-or-narrower re-declarations stay suppressed (Spec 06.05 §3.4 rule 2) and
never trigger a rebuild on their own. Clauses on non-key columns are emitted
against the current table as before (Spec 06.04).

#### 3.1.2 Key-column widening or rename is a rebuild under the same identity
A statement whose only key effect is a deferred `MODIFY`/`CHANGE`/`RENAME`
(Spec 06.05 §3.4 rule 3) plans a rebuild whose new key is the current key
(renamed where applicable) — MySQL rebuilds the clustered index for a type
change of a primary-key column exactly as for a key change. With
`ddl.primary.key.rebuild=false` the former loud refusal applies.

When the current key is the keyless all-columns identity (Spec 06.05 §3.6 —
every stored non-connector column is a key column, possibly `Nullable` under
`allow_nullable_key = 1`), the same-identity plan is flagged keyless
(`keylessFallback`): the rebuilt table keeps every key column's nullability,
exactly as the source keeps it — a same-identity rebuild changes no column
but the re-typed or renamed one. Planned as a declared key, the rebuild of
§3.3.1 step 3 stripped the `Nullable` from every key column the statement did
not touch: the second key-column `ALTER` on such a table re-declared the first
one's `Nullable(Int32)` as `Int32` (`AlterTableModifyColumnIT.testModifyColumn`,
`AlterTableChangeColumnIT.testChangeColumn`), and a copy of rows holding NULL
in those columns would have stored the type's default in their place. A
declared key is unaffected: its columns are non-Nullable already (Spec 06.05
§3.3), and a column newly promoted into a declared key is still re-declared
non-Nullable. Unknown columns (no target lookup) keep the declared-key plan.

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
   old one's path (a `{uuid}` path — the default ClickHouse writes into the
   metadata of a table created with empty engine arguments — does not collide,
   but ClickHouse accepts it only in an `ON CLUSTER` query, which is how the
   rebuild issues every statement in replicated mode, §3.3.1 step 3);
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

### 3.3 Protocol: swap first, backfill online
The rebuild is split into a **swap phase**, executed inside the DDL barrier
and made of metadata-only statements (milliseconds), and a **backfill phase**
that copies the rows afterwards, online, while the rebuilt table already
receives the events that follow the DDL. Replication of this table and of
every other table resumes as soon as the swap phase returns; the barrier is
never held for the duration of a copy. `T` is the target,
`S = T__pk_rebuild_<epochMs>` the rebuilt definition, `R` the retired
pre-rebuild table (after the swap it lives under the scratch name), `K` the
source key map.

Why this is correct under `ReplacingMergeTree`: every row the backfill
inserts carries the `_version` it had BEFORE the DDL, and every event that
reaches the rebuilt table after the DDL is versioned above the run's floor
(Spec 02.02). For a key the new table already holds — a newer live row, a
DELETE tombstone, the tombstone half of a relocation — the backfilled row
has the smaller version and loses in `FINAL`; for a key the new table does
not hold yet, the backfilled row is the current state. Inserting an
outdated row therefore never shadows a newer one, and inserting the same
row twice collapses to one (`PkRebuild.lean`: `backfill_never_shadows_newer`,
`backfill_idempotent`).

#### 3.3.1 Swap phase (inside the barrier)
1. **Clean previous attempt** — only scratch tables of a *swap* that never
   completed: a `T__pk_rebuild_%` table is dropped only when `T` is still
   keyed by the OLD identity (the swap did not happen); a retired table whose
   backfill is pending (§3.3.2) is never touched here.
2. **The statement's other clauses** (`ADD COLUMN IF NOT EXISTS ...`) have
   been applied to `T` by `executeDDL`.
3. **Create `S`** from `SHOW CREATE TABLE T` rewritten by
   `rewriteCreateStatement`: the new key in `ORDER BY`, key columns
   non-Nullable (the keyless all-columns fallback — a `DROP PRIMARY KEY`
   without replacement, or the same-identity rebuild of a keyless table,
   §3.1.2 — keeps `Nullable` +
   `allow_nullable_key`; so does a deferred re-typing whose translated type
   is `Nullable(T)` — `MODIFY x ... NULL` on a keyless table's key column,
   Spec 06.05 §3.4 rule 3: the source column now holds NULL, so the rebuilt
   column keeps `Nullable(T)` and `allow_nullable_key = 1` is merged into
   `SETTINGS`; stripping it would reject the NULLs the source sends), a
   deferred rename/type change of a key column
   folded into the column list (§3.1.1) and renamed wherever `PARTITION BY`,
   `SAMPLE BY`, `TTL` or the key clauses reference it as an identifier (never
   inside a string literal or a longer identifier), a `UUID '...'` token that
   `SHOW CREATE TABLE` may render after the table name on Atomic databases
   dropped (two tables cannot share a UUID), engine, `PARTITION BY`, `TTL` and
   settings otherwise verbatim; then each deferred `DROP COLUMN IF EXISTS k`
   as its own `ALTER TABLE S` (metadata-only on the empty table), then each
   deferred column position as `ALTER TABLE S MODIFY COLUMN k' <type of k' on
   S> FIRST` / `AFTER c` (§3.1.1; the type is read from `system.columns` of
   `S` so the statement is a pure restatement, which ClickHouse accepts on a
   key column). Pinned by
   `PrimaryKeyRebuildTest.rewriteStripsTableUuid()`,
   `PrimaryKeyRebuildTest.rewriteRenamesKeyColumnInPartitionAndTtl()` and
   `PrimaryKeyRebuildTest.rewriteKeepsNullableRetypeOfKeyColumn()`.
4. **Mark the retired table**, then **swap**. Before the swap `T` (which is
   about to become `R`) receives a metadata-only
   `ALTER TABLE T MODIFY COMMENT '<original comment>\ncsc-pk-rebuild:{json}'`
   carrying the plan (old key, new key, renames, source-valued columns, source
   database, key-map name, epoch): this marker is what makes the backfill
   resumable from the tables alone (§3.3.2 step 6) — it cannot be inferred
   from column sets, because the statement's `ADD COLUMN` was applied to the
   old table too (an added `AUTO_INCREMENT` column is present on `R` with its
   default, not with the source values). Then `EXCHANGE TABLES T AND S` when
   the database engine is `Atomic` or `Replicated`, otherwise `RENAME TABLE T
   TO T__pk_retired_<epochMs>, S TO T` in one statement. From this instant `T`
   is the empty table keyed by the new identity and `R` (= `S` after an
   `EXCHANGE`, `T__pk_retired_*` after a `RENAME`) holds every pre-DDL row and
   the marker; the Spec 06.08 cache invalidation follows, and the barrier is
   released.
5. **Schedule the backfill** of `R` into `T` on the connector's single
   `pk-rebuild-backfill` thread (never a writer thread; its own ClickHouse
   connection) and return.

**Replicated mode.** When the connector creates `ReplicatedReplacingMergeTree`
tables (`auto.create.tables.replicated=true` — the same switch on which the
translator appends `ON CLUSTER `{cluster}`` to its own `CREATE`/`ALTER`),
every DDL the swap and the backfill issue on `T`, `S` and `R` carries
` ON CLUSTER `{cluster}`` in the same literal form: the `DROP TABLE IF EXISTS`
of a leftover (step 1), the `CREATE TABLE S` (step 3, after the table name),
the `ALTER TABLE S` of a deferred `DROP COLUMN` or column position (step 3b),
the `ALTER TABLE T ... MODIFY COMMENT` marker and the `EXCHANGE TABLES` /
`RENAME TABLE` (step 4), and the `DROP TABLE IF EXISTS R` /
`ALTER TABLE R ... MODIFY COMMENT` of §3.3.2 steps 4 and 7. Two reasons, both
measured in the replicated CI environment (a four-host cluster,
ClickHouse 23.8):
1. `SHOW CREATE TABLE T` renders the engine as
   `ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', ...)`
   — ClickHouse writes the default path into the metadata of a table created
   with empty engine arguments — and re-issuing that `CREATE` as a plain query
   is rejected with `Code: 36 BAD_ARGUMENTS` "Macro 'uuid' and empty arguments
   of ReplicatedMergeTree are supported only for ON CLUSTER queries with Atomic
   database engine" (`registerStorageMergeTree.cpp`: the `{uuid}` macro is
   allowed only for an `ON CLUSTER` query, a `Replicated` database, or
   `ATTACH`). Pre-fix code issued the plain `CREATE`, so every keyless or
   deferred-clause key-column `ALTER` on a replicated target failed at step 3
   (a `DDLReplicationException` on the barrier) and replication stopped there.
   The engine arguments are kept verbatim; on cluster the initiator generates
   the table UUID once, so `{uuid}` resolves to the same path on every host.
2. `T` exists on every host of the cluster (it was created `ON CLUSTER`), so
   the rebuilt definition must replace it everywhere; a swap on one host alone
   would leave the other replicas keyed by the old identity with the pre-DDL
   rows. The copy (`INSERT ... SELECT`, §3.3.2 step 2) and the completeness
   check run on the copying host only and reach the other replicas through
   the engine; the key map `K` is a local helper of that host and never
   carries the clause.
On-cluster statements go through the distributed DDL queue
(`distributed_ddl_task_timeout`) instead of returning in milliseconds; the
swap is still metadata-only and holds the barrier for that queue round-trip.
Without the switch nothing changes. Pinned by
`PrimaryKeyRebuildTest.replicatedSwapRunsOnCluster()` and
`PrimaryKeyRebuildTest.replicatedBackfillDropsRetiredOnCluster()`.

#### 3.3.2 Backfill phase (online, retried, restart-safe)
1. **Source key map** (SOURCE_VALUED columns only, §3.4): create `K` and fill
   it from the source — read now, i.e. at a position at or after the DDL,
   which is what makes the JOIN below safe (§3.4). A `K` left by an
   interrupted attempt is dropped and re-read (a partially filled map would
   silently drop rows from the JOIN); within one process a loaded `K` is
   reused across retries.
2. **Copy**: `INSERT INTO T (<cols>) SELECT <cols> FROM R FINAL WHERE <live>`
   (with the `K` JOIN and `o.<old> AS <new>` for renamed columns as in the
   previous protocol), one statement per partition of `R` in `system.parts`
   order (a single statement when `R` is unpartitioned), each logged with its
   row count. `<live>` is `is_deleted = 0` when the engine carries the delete
   flag. Tombstones are not copied: a row deleted before the DDL stays deleted
   because it is simply absent.
3. **Completeness check**: every live key of `R` must be present in `T`
   (live, tombstoned or relocated — presence by key, not liveness):
   `SELECT count() FROM (SELECT <new key> FROM R FINAL [JOIN K] WHERE <r live>)
   AS r LEFT JOIN (SELECT DISTINCT <new key> FROM T) AS t ON <new key
   equality> WHERE t.<key> IS NULL SETTINGS join_use_nulls = 1` must return
   `0`. The `FINAL` MUST be confined to the retired table's subquery: written
   as `FROM R FINAL LEFT JOIN T`, ClickHouse 24.8 reads the right-hand
   `ReplacingMergeTree` table with FINAL semantics too, so a key whose only
   rows in `T` are tombstones (deleted or relocated after the swap) does not
   match and the check reports it missing forever (measured: 3 phantom
   misses for 2 deleted + 1 relocated row; 0 with the subquery form). The probed column is the first new-key column, or
   `t._version` when that column is `Nullable` on `T` (the keyless fallback),
   and `Nullable` key columns are joined with `isNotDistinctFrom` (plain `=`
   would report every NULL-keyed row as missing forever). A new-key column
   whose type on `R` differs from its type on `T` (a re-typed key column,
   §3.1.1) is read from `R` through the conversion the copy applied —
   `CAST(r.<old>, '<type on T>')`, or `ifNull(CAST(r.<old>, 'Nullable(<type on
   T>)'), defaultValueOfTypeName('<type on T>'))` when `R` has it `Nullable`
   and `T` does not (the value `insert_null_as_default` stored) — because
   ClickHouse rejects the join of a String key against a numeric one with
   `Code: 386 NO_COMMON_TYPE` (measured on 24.8.14 after `MODIFY class_name
   INT` on a VARCHAR key column: "Left key class_name type Nullable(String).
   Right key class_name type Int32") and the backfill would retry forever
   with the retired table never released. Rows deleted or
   re-keyed on the source between the DDL and the key-map read are absent
   from `K` and excluded from the check, as in §3.4.
4. **Retire**: `DROP TABLE R` (`ON CLUSTER` in replicated mode, §3.3.1) and
   `K` (always local) — unless `disable.drop.truncate=true`, in which case
   both are kept and named at WARN, and the marker is stripped from the kept
   copy (the same `ON CLUSTER` rule) so it is not copied again at the next
   start.
5. **Failure and retry**: any failure in steps 1–3 is logged at ERROR with the
   step and statement, recorded in the error table when `error.logging.enable`
   is set, and the backfill is re-scheduled with exponential backoff
   (10 s doubling to a 5 min cap), indefinitely — re-running the copy is
   idempotent (§3.3 rationale). Replication is never stopped by a backfill
   failure: the data already on the source is safe in `R` and the new events
   are flowing into `T`; the visible symptom is the loud log line and the
   retired table still present. `R` is never dropped before step 3 passes.
6. **Restart**: at engine start, before the first batch, `PrimaryKeyBackfill.resumePending`
   scans the destination database(s) for `T__pk_rebuild_%` / `T__pk_retired_%`
   tables that carry the `csc-pk-rebuild:` marker (§3.3.1 step 4) and whose
   companion `T` exists and is keyed by the marker's new key (i.e. the swap
   happened), rebuilds the Task from the marker and schedules it; an
   in-flight copy that was interrupted simply re-runs (idempotent). A
   marker-less scratch table is either an incomplete swap's `S` (removed by
   the next attempt's step 1 while `T` is still keyed by the old identity) or
   a completed copy kept under `disable.drop.truncate` — neither is resumed
   nor dropped here.

7. **Superseding DDL while a backfill is pending.** A replicated `TRUNCATE
   TABLE T` or `DROP TABLE T` that arrives after the swap makes every
   pre-DDL row of `T` obsolete: on the source the table is empty (or gone)
   at that binlog position, so the retired rows must never reach `T` again.
   When such a statement executes for `T` (Spec 04.05 / 06.08; also when
   `disable.drop.truncate` suppresses it, since the operator keeps the rows
   already in `T`, not the retired ones), the connector **cancels** the
   pending or running backfill of `T` (a running copy is interrupted at the
   next statement boundary and its task is not re-scheduled) and drops the
   retired copy (`ON CLUSTER` in replicated mode, §3.3.1) and the key map
   (local) before the statement's own effect is applied — a TRUNCATE that ran first and a backfill statement that
   followed would otherwise resurrect truncated rows. A backfill whose
   retired table has vanished under it (cancelled) logs that and stops. A
   copy statement already executing on ClickHouse cannot be interrupted,
   and ClickHouse does NOT order the superseding statement behind it: for
   a MergeTree table `TRUNCATE TABLE` takes no exclusive table lock
   (`InterpreterDropQuery`, Truncate branch, 24.8: "We don't need any lock
   for ReplicatedMergeTree and for simple MergeTree"), so a TRUNCATE issued
   while the copy's `INSERT ... SELECT` is executing returns at once and
   the copied rows land after it (measured in CI: the truncate returned in
   2 ms while the copy of 300000 rows was in flight, and all 300000 rows
   survived it). The cancel therefore does the ordering itself: after
   marking the task cancelled it **waits** for the running attempt to stop
   at its next statement boundary — i.e. for the in-flight statement to
   return — before it drops `R`/`K` and returns to the DDL path, so the
   superseding statement always executes after the last copy statement
   and the truncated (or dropped) state is the final one. The wait is not
   capped (the copy statement is bounded by ClickHouse's own limits, and a
   capped wait would let the truncate run ahead of the copy); it is logged
   at WARN every 30 s, and an interrupt while waiting fails the DDL loudly
   rather than proceeding. A cancel issued from the attempt's own thread
   cannot wait for itself and does not. The cancel is performed on the DDL
   thread's connection (`PrimaryKeyBackfill.cancelFor(connection, database,
   table)`; `cancelAllFor` for `DROP DATABASE`), and the backfill of one
   table never overtakes an earlier-submitted backfill of the same table.
   Formally: the TRUNCATE is a segment boundary in binlog order (Spec 04.05);
   rows written before it, whether by the writer or by the backfill, belong
   to the segment it clears.
8. **Concurrent key changes on the same table.** A second identity change
   on `T` while the first backfill is pending swaps again (step 1 keeps the
   pending retired table; the new `S` is created from the CURRENT `T`),
   and the backfill of the first retired table then runs against a `T` that
   is keyed differently: the copy statement is built at run time from the
   current columns of `T` and the completeness check uses the first plan's
   new key, which still exists as ordinary columns. Backfills of one table
   run in submission order on the single backfill thread, so the older
   retired rows land before the newer ones.
9. **Schema change on `T` during a backfill.** A column added to `T` after
   the swap is absent from the retired table; the copy omits it (ClickHouse
   fills the column default, as MySQL back-fills an added column) and logs
   the omission at INFO. A column dropped from `T` after the swap is simply
   not in the copied set. A widening on `T` converts on insert.

#### 3.3.3 Observability
The swap and each backfill step log at INFO with the exact statement; the
backfill logs the per-partition and total row counts, the completeness-check
result and the drop of `R`. Until the completeness check passes the rebuilt
table is missing the pre-DDL rows that have not been copied yet — a
value-level comparison (`db_compare`) of that table during the backfill is
expected to differ and must be repeated once the log reports the backfill
complete.

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
A failure before the swap (§3.3.1 step 4) leaves `T` keyed by the old
identity, so the retried statement rebuilds again after step 1 removed the
scratch table of the failed attempt. After the swap the rebuilt `T` and the
retired `R` both exist and the DDL is a restatement; the backfill of `R` is
what still has to happen, and it is resumed from the tables themselves
(§3.3.2 step 6), never from the DDL.

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
- **I5 (DDL Barrier Quiescence)**: the swap runs strictly inside the
  existing barrier, like MySQL's serialization point, and holds it only for
  metadata statements; the copy runs outside it, so no other table's
  replication waits on a rebuild (§3.3).
- **I2 (Version Monotonicity)**: backfilled rows keep their pre-DDL
  `_version`, below every post-DDL event's version, so an outdated row can
  never shadow a newer one and the copy is idempotent
  (`backfill_never_shadows_newer`, `backfill_idempotent`).
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
- `PrimaryKeyRebuildTest.swapPhaseIsMetadataOnly()` — recording connection: the swap phase issues only the leftover scan, `SHOW CREATE TABLE`, `CREATE TABLE S`, deferred `ALTER TABLE S DROP COLUMN`, the `ALTER TABLE T MODIFY COMMENT` marker and `EXCHANGE TABLES`; no `INSERT`, no source connection, and it returns the backfill task instead of running it.
- `PrimaryKeyRebuildTest.localCopyStatementSequence()` — the backfill on the retired table: `INSERT INTO T ... SELECT ... FROM R FINAL WHERE is_deleted = 0` (one per partition), the completeness check (`LEFT JOIN ... IS NULL` count = 0), `DROP TABLE R`; no source connection opened.
- `PrimaryKeyRebuildTest.sourceKeyMapJoinSequence()` — a `SOURCE_VALUED` column: the key-map table, the source `SELECT`, the JOIN copy, the source-valued column taken from the map, `K` dropped after the check.
- `PrimaryKeyRebuildTest.completenessCheckGuardsDrop()` — a non-zero completeness count: no DROP of `R`/`K`, the failure is reported and the task is re-scheduled.
- `PrimaryKeyRebuildTest.completenessCheckCastsRetypedKeyColumns()` — §3.3.2 step 3: a new-key column re-typed by the rebuild is read from `R` as `CAST(r.<old>, '<type on T>')` (`ifNull(CAST(..., 'Nullable(<type on T>)'), defaultValueOfTypeName(...))` when only `R` is Nullable); same-typed columns are read as before. Pre-fix code compared the raw columns, so every String<->numeric re-type of a key column failed the check with `Code: 386` and the backfill retried forever.
- `PrimaryKeyRebuildTest.backfillRetriesWithBackoff()` — a failing `INSERT` re-schedules the backfill (10 s, 20 s, ... capped at 5 min) and the next attempt re-issues the same statements; nothing is dropped meanwhile.
- `PrimaryKeyRebuildTest.restartResumesPendingBackfill()` — `resumePending` finds a marker-bearing `T__pk_rebuild_%` table whose companion `T` is keyed by the marker's new key and schedules its backfill; a marker-less scratch table, or one whose companion is still keyed by the old identity, is not resumed.
- `PrimaryKeyRebuildTest.retiredCopyKeptWhenDropTruncateDisabled()` — `disable.drop.truncate=true`: the backfill completes, nothing is dropped, both names logged.
- `PrimaryKeyRebuildTest.truncateDuringBackfillCancelsAndDropsRetired()` — §3.3.2 step 7: a `TRUNCATE TABLE T` arriving while `T`'s backfill is queued cancels the task (no copy statement is ever issued) and drops the retired table and the key map before the truncate runs; `dropTableDuringBackfillCancels()` — the same for `DROP TABLE T`; `cancelledBackfillStopsWithoutRescheduling()` — a running copy whose retired table is gone logs and does not re-schedule; `cancelWaitsForInFlightCopyStatement()` — a cancel issued from the DDL thread while the copy statement is executing does not return until that statement has (the DDL thread is still blocked 1.5 s later, nothing is dropped meanwhile), then `R` is dropped and the truncate runs, in that order, and the attempt stops at the boundary with no row count, check or retry; a queued task is cancelled without any wait. Pre-fix code returned at once and the truncate ran while the copy was in flight, so on MergeTree (no exclusive lock for TRUNCATE) every copied row landed after the truncate.
- `PrimaryKeyRebuildTest.secondSwapKeepsPendingRetiredTable()` — §3.3.2 step 8: the second swap's step 1 leaves a marker-bearing pending retired table in place and the two tasks run in submission order.
- `PrimaryKeyRebuildTest.columnAddedDuringBackfillIsOmittedFromCopy()` — §3.3.2 step 9: a column present on `T` but absent from the retired table is left out of the copy column list.
- Integration (`PrimaryKeyChangeIT`, backfill session slowed with `clickhouse.jdbc.settings` `max_execution_speed`/`timeout_before_checking_execution_speed=0` so the online window is seconds long): `dmlDuringBackfillIsShadowedByNewerVersions()` (UPDATE / DELETE / INSERT / relocation on rows the backfill has not copied yet, issued while the retired table still exists; final value-level equality), `truncateDuringBackfillLeavesTableEmpty()` (TRUNCATE while the backfill is pending: the table ends empty, the retired copy is gone, no resurrected rows), `secondKeyChangeWhileBackfillPending()` (two key changes back to back; final equality and the second key), `restartDuringBackfillResumesFromMarker()` (engine stopped while the retired table still exists, restarted: the backfill completes and the retired table is dropped), `columnAddedDuringBackfill()` (ADD COLUMN on the source while the backfill runs; final equality including the new column).
- `PrimaryKeyRebuildTest.replicatedLiteralPathIsLoud()`, `PrimaryKeyRebuildTest.nullableOldKeyWithSourceMapIsLoud()` — §3.2 preconditions.
- `PrimaryKeyRebuildTest.replicatedSwapRunsOnCluster()` — §3.3.1 replicated mode: with `auto.create.tables.replicated=true` the leftover `DROP`, the `CREATE TABLE S` (the rendered `'/clickhouse/tables/{uuid}/{shard}'` path kept verbatim), the deferred `ALTER TABLE S DROP COLUMN`, the `MODIFY COMMENT` marker and the `EXCHANGE TABLES` (or the `RENAME TABLE` on a non-Atomic database) all carry ` ON CLUSTER `{cluster}``; without the switch none does. Pre-fix code issued the plain `CREATE`, which ClickHouse rejects for the `{uuid}` path outside `ON CLUSTER`, so every key-column ALTER on a replicated target failed at step 3.
- `PrimaryKeyRebuildTest.replicatedBackfillDropsRetiredOnCluster()` — §3.3.2 steps 4 and 7 in replicated mode: the copy and the check are plain statements on the copying host, `R` is dropped (or its kept copy re-commented) `ON CLUSTER`, the key map `K` without it — on completion and on a superseding `TRUNCATE`.
- `MySqlDDLParserListenerImplTest.testModifyKeyColumnWiderPlansRebuild()`, `testChangeKeyColumnRenamePlansRebuild()`, `testKeyColumnChangeIsLoudWhenRebuildDisabled()` — §3.1.1/§3.1.2: a widening `MODIFY` and a `CHANGE`/`RENAME` of a key column plan a same-identity rebuild with the deferred clause and (for a rename) the renamed key; nothing for the key column is emitted against the current table; `ddl.primary.key.rebuild=false` is loud.
- `MySqlDDLParserListenerImplTest.testDroppedKeyColumnIsDeferredToRebuiltTable()` — the GIPK promotion `DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)`: plan with new key `id`, deferred `DROP COLUMN IF EXISTS my_row_id`, and no `DROP COLUMN` in the emitted statement.
- `MySqlDDLParserListenerImplTest.testRedeliveredPrimaryKeyChangeIsRestatement()` — §3.5: once the target's sorting key equals the declared key, the same statement plans nothing and emits only its idempotent clauses.
- `PrimaryKeyRebuildTest.deferredClausesApplyToRebuiltTableBeforeCopy()` — step 3b ordering: CREATE `S`, then `ALTER TABLE S DROP COLUMN IF EXISTS ...`, then the copy whose column list excludes the dropped column.
- `PrimaryKeyRebuildTest.rewriteKeepsNullableRetypeOfKeyColumn()` — §3.3.1 step 3: a re-typed key column whose translated type is `Nullable(String)` is declared so on `S` (also under its new name when renamed) with `allow_nullable_key = 1`; a non-Nullable re-type adds nothing. Pre-fix code stripped the `Nullable` whenever the plan was not the keyless fallback, so `MODIFY x VARCHAR(100) NULL` on a keyless table produced a non-Nullable `x String`.
- `MySqlDDLParserListenerImplTest.testDeferredKeyColumnClauseCarriesPosition()` — §3.1.1: the `FIRST`/`AFTER` of a deferred `CHANGE`/`MODIFY` of a key column is recorded in `positionedColumns()` under the rebuilt table's name; absent without a position.
- `MySqlDDLParserListenerImplTest.testSameIdentityRebuildOfKeylessTableIsFlaggedKeyless()` — §3.1.2: a deferred `MODIFY`/`CHANGE` of a key column on a table keyed on every stored column plans a rebuild with `keylessFallback()` true (the re-typed column `Nullable(Int32)`, the renamed one under its new name); the same shape on a declared key plans a declared-key rebuild. `PrimaryKeyRebuildTest.keylessSameIdentityRebuildKeepsOtherKeyColumnsNullable()` — §3.3.1 step 3 under that plan: the untouched key columns stay `Nullable`, the existing `allow_nullable_key = 1` is kept once; the declared-key plan of the same statement strips them (the pre-fix result).
- `PrimaryKeyRebuildTest.renamedKeyColumnIsCopiedUnderNewName()` — the `CREATE` of `S` declares the renamed column under its new name and keys by it (no `RENAME COLUMN` ALTER is issued), and the copy reads `o.<old> AS <new>`.
- Integration (`PrimaryKeyChangeIT`, MySQL 8.0 → embedded connector → ClickHouse, the `AbstractCDCBaseIT` harness): `compositeKeyToAutoIncrementId()` (the production migration `DROP PRIMARY KEY, ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (id)`, source values joined in), `rekeyOntoExistingColumn()`, `supersetKey()` (`(a)` → `(a, b)`), `addPrimaryKeyOnNullableColumn()` (MySQL makes the column `NOT NULL`; the replica key column is non-Nullable), `dropPrimaryKeyBecomesKeyless()` (`sql_generate_invisible_primary_key=OFF`; all-columns identity), `gipkTablePromotedToExplicitKey()` (`DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)`), `keyColumnWidened()` (`MODIFY id BIGINT`), `keyColumnRenamed()` (`CHANGE id ref_id INT`), each preceded by DML under the old key and followed by INSERT / UPDATE / DELETE and a relocation (`UPDATE ... SET <new key> = ...`) under the new key; every case asserts value-level equality with MySQL (`FINAL`, live rows) once the backfill has completed (the retired table is gone), the replica sorting key, no leftover `__pk_rebuild_` / `__pk_retired_` tables, and an untouched control table. `replicationContinuesWhileBackfillRuns()` — a key change on a table with enough rows for the backfill to take seconds; an INSERT into another table issued right after the ALTER is visible in ClickHouse before the backfill of the first table has finished (the retired table still exists at that moment), and both tables compare equal at the end.
- End-to-end on a built jar (`csc_e2e_pk.sh`, podman: MySQL 8.0 → connector → ClickHouse 24.8): the same matrix at the value level; `t_pk` → `ORDER BY pk_id`, `t_pk2` → `ORDER BY b`, `t_pk3` → `ORDER BY (id, v)`.
- Formal: `primary_key_change_rebuilds`, `wider_key_change_rebuilds`, `rebuild_never_bare`, `rebuild_only_when_not_loud` in `DdlTranslation.lean`; `rebuild_preserves_live_rows`, `rebuild_view_eq`, `backfill_never_shadows_newer` (appending the re-keyed live rows of the old table, all versioned below every post-DDL record of the same key, leaves the FINAL view of those keys unchanged and supplies the view of the keys the new table lacked), `backfill_idempotent` (appending the same backfill twice yields the same FINAL view) in `PkRebuild.lean`.
