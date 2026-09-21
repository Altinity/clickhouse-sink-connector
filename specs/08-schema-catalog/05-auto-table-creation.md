# Spec 08.05: Automatic Target Table Creation & Engine Selection

## 1. Executive Summary & Purpose
Specifies the automated synthesis and execution of ClickHouse `CREATE TABLE` DDL when `auto.create.tables = true` and a target table does not exist.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java`
- **Methods**: `createNewTable()` (executes), `createTableSyntax()` (builds the DDL), `isPrimaryKeyColumnPresent()`
- **Caller**: `db/DbWriter.autoCreateTable()` — passes `record.getPrimaryKey()` (the Kafka record-key field names; `null` for a keyless source table) and the record's schema fields.
- **Banner**: `db/KeylessTableWarning.banner(database, table)` (shared with the lightweight DDL translator).
- **Constants**: `db/ClickHouseDbConstants` (`ORDER_BY`, `PRIMARY_KEY`, `ALLOW_NULLABLE_KEY`, engine column names). `ORDER_BY_TUPLE` is no longer used by this class.

---

## 3. Operational Specification

### 3.1 DDL Synthesis Protocol
When an incoming record targets a table that does not exist in ClickHouse:
1. Infers column names and types from the Kafka Connect `Schema`
   (`getColumnNameToCHDataTypeMapping`); an optional field becomes `Nullable(T)`
   unless `T` is `Array`/`Map`/`Tuple` or the column is an engine column.
2. Appends ALIAS columns from `column_type_override.alias.*`, then the
   replication-history columns when `replication.history.enable=true`
   (`_valid_from`, `_valid_to`, `_operation`, `is_deleted`).
3. Appends the engine columns, which depend on `isNewReplacingMergeTreeEngine`
   (ClickHouse ≥ 23.2 supports the `is_deleted` engine argument):
   - new engine: `_version UInt64`, `is_deleted UInt8` (or the configured
     `replacingmergetree.delete.column`; omitted here when history mode already added it)
   - legacy engine: `_sign Int8`, `_version UInt64`
4. Emits the engine clause **exactly** as the code does (the previous text of
   this spec described a form the code never emitted):
   - new engine, not replicated: `` Engine=ReplacingMergeTree(_version,is_deleted)``
   - new engine, replicated (`auto.create.tables.replicated=true`):
     `CREATE TABLE ... ON CLUSTER `{cluster}` ... Engine=ReplicatedReplacingMergeTree(_version, is_deleted)`
     — **no** ZooKeeper path / replica arguments are passed; the server's
     `default_replica_path` / `default_replica_name` macros are relied upon.
   - legacy engine: `ENGINE = ReplacingMergeTree(_version)` /
     `Engine=ReplicatedReplacingMergeTree(_version)`.
   `CollapsingMergeTree` is never emitted by auto-create.
5. `PARTITION BY` from the schema override (`partition_by`), or
   `toDate(_valid_to)` in history mode.
6. Sorting key per §3.2; history mode appends `_valid_to` to the `ORDER BY`.
7. `TTL _valid_to + toIntervalDay(30)` in history mode; `SETTINGS` last (§3.2.3).
8. `createNewTable()` executes the statement through `DBMetadata.executeSystemQuery`.

### 3.2 Sorting key selection
`ReplacingMergeTree` deduplicates on the sorting key. With `ORDER BY tuple()`
**every row compares equal**, so background merges and `FINAL` collapse the whole
table to one row — total, silent data loss for any keyless source table
(measured with `clickhouse local`: two distinct rows inserted, `count() FROM t
FINAL` = 1). `ORDER BY tuple()` is therefore **never emitted** by this class.

Precedence:
1. **Schema override** `primary_key` for the table, if configured. This is the
   operator's override for any table whose key the record does not carry.
2. **Record key fields** (`primaryKey`) when every named column exists in the
   column map: `PRIMARY KEY(k1,...) ORDER BY(k1,...)`.
3. **Keyless fallback** — `primaryKey` is `null`/empty, or names a column the
   map does not contain: `ORDER BY(c1,...,cn)` over **every source column** in
   record-schema order that exists in the column map, excluding the connector's
   own columns (`_version`, `_sign`, `is_deleted` / the configured delete
   column, `_valid_from`, `_valid_to`, `_operation`). No `PRIMARY KEY` clause is
   emitted (it defaults to the `ORDER BY`). `KeylessTableWarning.banner()` is
   logged at ERROR so the operator is told to give the table a real identity at
   the source (`ADD COLUMN my_row_id ... INVISIBLE PRIMARY KEY` /
   `sql_generate_invisible_primary_key=ON`).

#### 3.2.1 Nullable columns in the fallback key
The all-columns key necessarily includes every `Nullable` column of the source
table, and ClickHouse rejects a nullable sorting key (Code 44 `ILLEGAL_COLUMN`)
unless `allow_nullable_key=1`. When any column of the fallback key is
`Nullable(...)` (from the column map) or its schema field is optional, the
statement ends with `SETTINGS allow_nullable_key=1`, appended to (never
replacing) any user-supplied `settings`, and not duplicated if the user already
set it. The PK path never needs it (a MySQL PRIMARY KEY is `NOT NULL`) and must
not get it.

#### 3.2.2 Known limits of a value-derived key (why the banner is loud)
An all-columns key reproduces MySQL's own semantics for a table without a
declared identity — rows are distinguished by value — but it is not a row
identity:
- Two source rows identical in every column collapse into one (MySQL permits
  such duplicates; nothing in the binlog distinguishes them).
- A column added later (`schema.evolution=true` issues `ADD COLUMN`) is not in
  the key, so two rows differing only in that column collapse.
- ClickHouse forbids `MODIFY`/`RENAME`/`DROP` of a key column (Code 524 / 47).
  This path issues only `ADD COLUMN` itself, but any manual DDL on such a table
  is constrained.
The only correct fix is at the source, which is what the banner says; the
schema-override `primary_key` is the operator's escape hatch in the meantime.
`ORDER BY tuple()` is worse on every axis: it loses all but one row immediately.

#### 3.2.3 `SETTINGS` placement
`SETTINGS` is always the last clause: user `settings` from the schema override,
with `allow_nullable_key=1` appended when §3.2.1 requires it.

### 3.3 A column type override that contradicts the table halts the connector
`createNewTable()` reconciles `column_type_override.*` against an existing
table and raises `ColumnTypeOverrideMismatchException` (unchecked) when the
override contradicts the actual column type; the operator must resolve it
before rows may flow. The caller must let it propagate: `DbWriter`'s
constructor and `DbWriter#autoCreateTable` re-throw it ahead of their generic
`catch (Exception)` (which only logs) so the writer is never built against a
type the operator has declared wrong (spec 10.04 §3.8).

---

## 4. Invariants Preserved
- **Invariant I3 (Convergence)**: an auto-created `ReplacingMergeTree` table always has a sorting key under which distinct source rows stay distinct.
- **Invariant I9 (Loud Failure)**: a keyless source table is reported with the banner naming the fix; it is never silently collapsed.
- **Zero Configuration Setup**: Enables out-of-the-box replication without manual ClickHouse DDL pre-provisioning.

---

## 5. Verification Criteria
- `ClickHouseAutoCreateTableTest.testKeylessTableOrdersByAllColumns()` —
  `createTableSyntax(null, "audit", "db", fields a,b,...)` contains no
  `ORDER BY tuple()`, its `ORDER BY` names `a` and `b`, and no `PRIMARY KEY`
  clause is emitted (pre-fix code emits `ORDER BY tuple()`).
- `ClickHouseAutoCreateTableTest.testKeylessTableWithNullableColumnEnablesNullableKey()`
  — an optional field yields `SETTINGS allow_nullable_key=1`.
- `ClickHouseAutoCreateTableTest.testKeylessTableMergesUserSettings()` —
  user `settings` are kept and `allow_nullable_key=1` appended once.
- `ClickHouseAutoCreateTableTest.testCreateTableEmptyPrimaryKey()` /
  `testCreateTableMultiplePrimaryKeys()` — updated expectations (all-columns key).
- `ClickHouseAutoCreateTableTest.testCreateTableSyntax()` — the PK path is unchanged.
- Probe (recorded in the PR): the emitted DDL executed with `clickhouse local`
  keeps two distinct rows under `FINAL`; the `tuple()` form keeps one.
