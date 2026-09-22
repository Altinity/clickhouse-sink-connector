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
   (`getColumnNameToCHDataTypeMapping`, rules in §3.1.1); an optional field
   becomes `Nullable(T)` unless `T` cannot be wrapped
   (`ClickHouseDataTypeMapper.canBeNullable`: `Array`/`Map`/`Tuple`/`Nested`
   and the geo types) or the column is an engine column.
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

#### 3.1.1 Column types: the record-schema path must agree with the DDL path
Two code paths declare ClickHouse column types for the same MySQL column: this
one, from the Kafka Connect record schema (Kafka mode auto-create, and
`schema.evolution` `ADD COLUMN` in both modes), and the DDL translator, from
the parsed MySQL DDL (`DataTypeConverter.convertToString`, Spec 06.04/06.05).
A table created by one path and evolved by the other must not end up with
two different types for the same source type. The record-schema rules, using
the source metadata Debezium propagates when `column.propagate.source.type` is
set (`__debezium.source.column.type` / `.length` / `.scale`; always set by
the lightweight runtime):

| Source column | Record schema | Declared type |
|---|---|---|
| `TINYINT` (signed) | `INT16` + type `TINYINT` | `Int8` (was `Int16`; the DDL path says `Int8`) — without the source type, `Int16` |
| `TINYINT`/`SMALLINT`/`MEDIUMINT`/`INT`/`BIGINT UNSIGNED` | signed Kafka type + type `... UNSIGNED` | `UInt8`/`UInt16`/`UInt32`/`UInt32`/`UInt64`, **`Nullable(...)` when optional** (the unsigned branch previously skipped the Nullable wrap, so `ADD COLUMN` declared a non-Nullable unsigned column for a nullable source column and the first NULL failed the batch) |
| `DECIMAL(p, s)` | `BYTES` `Decimal`, params `scale` / `connect.decimal.precision` | `Decimal(p, s)`; a missing precision is `max(10, s)`, a missing scale is `0` — so a dimensionless `DECIMAL` is `Decimal(10,0)`, as MySQL defines it and as the DDL path declares (was `Decimal(10,2)`) |
| `DATETIME(p)`, `p` 0..3 | `INT64` `io.debezium.time.Timestamp` | `DateTime64(p, 'UTC')`, `p` from `__debezium.source.column.length` (absent with a propagated `DATETIME` type means `p = 0`); without a propagated type, `DateTime64(3, 'UTC')` (the widest the logical type carries) |
| `DATETIME(p)`, `p` 4..6 | `INT64` `io.debezium.time.MicroTimestamp` | `DateTime64(p, 'UTC')` as above; without a propagated type `DateTime64(6, 'UTC')` |
| `TIMESTAMP(p)` | `STRING` `io.debezium.time.ZonedTimestamp` | `DateTime64(p, 'UTC')` as above (absent length with a propagated `TIMESTAMP` type means `p = 0`); without a propagated type `DateTime64(6, 'UTC')` |
| spatial | see Spec 07.06 §3.1 | |

Why `p` matters even though both `DateTime64(0)` and `DateTime64(3)` hold a
`DATETIME` value: two paths declaring different precisions for one source
column make `ADD COLUMN` after a hand-off between them, and every value-level
comparison that renders the column, disagree on the same instant.

Engine-column collision: a source table that itself has a column named like
the ReplacingMergeTree delete column (`is_deleted` by default) keeps that
column as an ordinary source column (`Nullable` when optional), and the
engine column is renamed `_is_deleted` (exactly what the DDL translator does)
instead of emitting the name twice, which ClickHouse rejects — the previous
outcome, after which every batch for the table failed with
"TABLE METADATA not retrieved".

Engine selection (`DbWriter.isNewReplacingMergeTreeEngine`): a failure to read
`SELECT VERSION()` **propagates** and no table is created in that
`DbWriter` initialisation (the batch is retried against a writer that is
rebuilt). It previously returned `false`, so a transient metadata failure
created the table with the **legacy** `ReplacingMergeTree(_version)` +
`_sign` layout permanently.

Known residual differences, owned by the DDL translator (not changed here):
`BOOL`/`BOOLEAN` keywords are declared `Bool` by the DDL path while the
record path (Debezium delivers `INT16`, type `TINYINT`) declares `Int8`; the
DDL path declares DATETIME columns in the configured `clickhouse.datetime.timezone`
(and a bare `DateTime64`, i.e. precision 3, for `DATETIME` when no zone is
configured) whereas this path always declares `'UTC'` (Spec 07.03 §3.1.3).
`DateTime64(p, 0)` as emitted by the DDL path without a zone is accepted by
ClickHouse as `DateTime64(p)` (`clickhouse local` 24.8.14: the non-string
optional timezone argument is ignored), so it is compared as such.

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
- `ClickHouseTableOperationsBaseTest.getColumnNameToCHDataTypeMappingTest()` —
  §3.1.1: a dimensionless `DECIMAL` is `Decimal(10,0)` (this assertion
  previously pinned `Decimal(10,2)`); temporal fields without a propagated
  type keep `DateTime64(3, 'UTC')` / `DateTime64(6, 'UTC')`.
- `ClickHouseTableOperationsBaseTest.getColumnNameToCHDataTypeMappingSourceTypeParityTest()`
  — §3.1.1: `TINYINT` → `Int8`, optional `INT UNSIGNED` → `Nullable(UInt32)`,
  `DATETIME` / `DATETIME(2)` / `DATETIME(6)` / `TIMESTAMP` / `TIMESTAMP(6)`
  with propagated type and length → `DateTime64(0|2|6|0|6, 'UTC')` (pre-fix
  code: `Int16`, `UInt32`, `DateTime64(3|3|6|6|6, 'UTC')`).
- `ClickHouseAutoCreateTableTest.testSourceIsDeletedColumnRenamesEngineColumn()`
  — §3.1.1 engine-column collision: the DDL declares `` `is_deleted` Nullable(Int16) ``
  as a source column and `` `_is_deleted` UInt8 `` with
  `Engine=ReplacingMergeTree(_version,_is_deleted)` (pre-fix code emits
  `is_deleted` twice).
- `DbWriterEngineSelectionTest.versionQueryFailurePropagates()` — §3.1.1
  engine selection: a `SQLException` from `SELECT VERSION()` propagates
  (pre-fix code returns `false`, the legacy engine); `22.8` → `false`,
  `24.8` → `true`.
- `RecordSchemaVsDdlTypeAgreementTest.bothPathsDeclareTheSameType()`
  (lightweight module) — table-driven: for each MySQL type in the matrix the
  record-schema path (with the parameters Debezium propagates) and
  `DataTypeConverter.convertToString` declare the same ClickHouse type
  (whitespace-normalised, `DateTime64(p, 0)` read as `DateTime64(p)`).
