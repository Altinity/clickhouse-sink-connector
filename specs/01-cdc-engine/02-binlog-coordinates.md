# Spec 01.02: Binary Log Coordinate Extraction & Ordering

## 1. Executive Summary & Purpose
Specifies the extraction, normalization, and comparison of binary log coordinates from MySQL change events. The coordinate establishes the total ordering of events in the source stream and is what separates a first delivery from a redelivery in the version sequence (spec 02.02).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/SourcePosition.java`
- **CDC Interceptor**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `SourcePosition.ofBinlog(String file, Long pos, Integer row)` — MySQL/MariaDB coordinate; a `null` row is row 0; a missing file or pos yields no position (`null`).
  - `SourcePosition.ofLsn(Long lsn)` — PostgreSQL WAL position.
  - `SourcePosition.binlogFileSequence(String file)` (package-private) — numeric suffix extraction.
  - `SourcePosition.compareTo(SourcePosition other)` / `equals` / `hashCode`.
  - `SourcePosition.sameLog(SourcePosition other)` — whether two positions belong to the same binary log (equal file prefix); false across a log basename change (§3.1.1).
  - `SourcePosition.sameLogPosition(SourcePosition other)` — whether two positions name the same binary log **event**: same log, same file, same byte position, any row index (§3.3). The version sequence uses it to recognise the rest of the event that set its high-water mark (spec 02.02 §3.1.2); under `binlog_transaction_compression` that event is a whole transaction (spec 01.08 §3.2).
  - `ClickHouseStruct.getSourcePositionFromChangeEvent(ChangeEvent<SourceRecord, SourceRecord>)` — reads `source.file` / `source.pos` / `source.row` (or `source.lsn`) from the Debezium envelope; `ClickHouseStruct.getSourcePosition()` does the same from an already-parsed struct.

---

## 3. Coordinate Tuple Specification

A MySQL binary log position is derived from the 4-tuple:
$$(F_{\text{prefix}}, F_{\text{seq}}, P_{\text{byte}}, R_{\text{idx}})$$
Where:
- $F_{\text{prefix}}$: The binlog file name prefix (e.g. `mysql-bin`).
- $F_{\text{seq}}$: The integer sequence extracted from the file name suffix (e.g. `000123` $\to 123$) by `binlogFileSequence`. File names without a numeric suffix fall back to lexical comparison of the whole file name; mixed suffixes keep a transitive order.
- $P_{\text{byte}}$: The 64-bit byte offset within the binlog file (`pos`).
- $R_{\text{idx}}$: The 0-indexed row position within a multi-row event (`row`).

### 3.1 Total Order Lexicographical Comparison
Given two coordinates $C_1 = (F_1, P_1, R_1)$ and $C_2 = (F_2, P_2, R_2)$:
$$C_1 < C_2 \iff (F_1 < F_2) \lor (F_1 = F_2 \land P_1 < P_2) \lor (F_1 = F_2 \land P_1 = P_2 \land R_1 < R_2)$$
PostgreSQL positions compare numerically on the LSN.

### 3.1.1 Log identity change (basename change, `RESET MASTER`)
The order compares $F_{\text{prefix}}$ first, as a string, so it is total across differently named logs — but that order is meaningless across a **log identity change**: when the binary log basename changes (`log_bin` reconfigured, a failover to a server with a different basename, or a `RESET MASTER` followed by the engine being re-created in the same JVM through its completion-callback retry) every position of the new log ranks entirely above or entirely below every position of the old one purely by the spelling of the prefix (`binlog` < `mysql-bin`). A consumer that used that comparison to tell a first delivery from a redelivery would classify the whole new log as a redelivery (and never clamp it, spec 02.02 §3.1) or as new. `sameLog` exposes the prefix equality so the version sequence resets its high-water mark instead of comparing across the change: a positioned record whose prefix differs from the mark's is a **first delivery** and becomes the new mark (spec 02.02 §3.1). PostgreSQL positions have an empty prefix and are always in the same log.

**Gap (tracked)**: a `RESET MASTER` that keeps the basename restarts the numbering at `000001`; within one JVM those positions rank below the mark and are treated as in-run redeliveries (not clamped) until the numbering passes the old mark. Positions alone cannot distinguish that from a legitimate in-run rewind; a process restart clears the mark and is the remedy.

### 3.3 The row index is not part of an event's identity
$R_{\text{idx}}$ counts rows *inside* one rows event and restarts at 0 in every rows event. Two positions with equal $(F, P)$ and different $R$ are the same event; `sameLogPosition` is that equality. It matters because $P$ is not always one statement: with `binlog_transaction_compression=ON` the whole transaction is one `Transaction_payload_event` and every row of every statement inside it is delivered at that event's $P$ (spec 01.08 §3.2), so a comparison that includes $R$ ranks the second statement's first row *below* the first statement's last row although it committed in the same instant and follows it in log order. `compareTo` keeps $R$ (it is a total order over rows, used to skip already-written rows on a restart); classification of first delivery vs redelivery must use `sameLogPosition` first (spec 02.02 §3.1.2). PostgreSQL positions have no row: `sameLogPosition` is LSN equality.

### 3.2 GTID Coordination
When Global Transaction Identifiers (GTID) are enabled:
- The connector reads the GTID from the Debezium `source` struct into `ClickHouseStruct.gtid`.
- The GTID is not part of `SourcePosition`; it is consumed by `ClickHouseStruct.calculateVersion` (spec 02.01, precedence rule), where it takes precedence over the sequence number.

---

## 4. Invariants Preserved
- **Invariant I1 (Log Sequence Monotonicity)**: Debezium delivers events in log order, and log order is commit order; the coordinate makes that order comparable in code.
- **Missing coordinates are not fatal**: an event without file/pos (or lsn) yields no `SourcePosition`; it is versioned as a positionless record (spec 02.04) rather than rejected.

---

## 5. Verification Criteria
- `SourcePositionTest`: ordering by pos then row, rotation ordering by file number, non-numeric file names, transitive mixed suffixes, missing coordinates yielding no position, LSN ordering, `equals`/`compareTo` agreement, and extraction from MySQL / PostgreSQL source structs.
- `SourcePositionTest.sameLogIsByFilePrefix()` — §3.1.1: same prefix across a rotation, different prefix across a basename change, LSNs always the same log.
- `CommitOrderVersionClampTest.binlogBasenameChangeResetsTheHighWaterMark()` — §3.1.1 through the version sequence: the first record of a differently named log is a first delivery, is clamped to the floor and becomes the new mark (pre-fix: treated as a redelivery, never clamped).
- Corresponds to `Replication.Binlog.BinlogPos` and `BinlogPos.lt` in `formal_specs/lean/`.

---

## 6. Failure Modes & Recovery
Coordinates are server-local: a `(file, pos)` pair means something only on the server that wrote it, and the in-memory high-water mark built from them (`DebeziumChangeEventCapture.sequenceHighWaterPosition`) lives for the whole JVM. The component recovers by itself from a log basename change; it has no defence against a source whose log identity changes without a basename change, and no way to tell a new server from the old one without GTID.

- **FM-01.02-1 Binary log basename change**
  - **Trigger**: `log_bin` reconfigured, or a failover to a server whose binlog basename differs.
  - **Behaviour**: `SourcePosition.sameLog` compares the prefix; `nextVersionAssignment` treats the first record of a differently named log as a first delivery, replaces the mark and keeps clamping to the floor (§3.1.1).
  - **Detection**: WARN `Binary log identity changed: high-water position <old> is replaced by <new> from a differently named log; its first record is versioned as a first delivery`, on the first record of the new log.
  - **Blast radius**: none; versions stay commit-ordered.
  - **Recovery**: none needed (positioning on the new server is FM-01.02-3).
  - **RTO**: 0 — no interruption.
  - **Test**: `CommitOrderVersionClampTest.binlogBasenameChangeResetsTheHighWaterMark()`, `SourcePositionTest.sameLogIsByFilePrefix()`.

- **FM-01.02-2 Same-named log restarting at a lower number inside one JVM**
  - **Trigger**: `RESET MASTER` / `RESET BINARY LOGS AND GTIDS` on the source, or a failover (GTID or not) to a server whose binlog numbering is below the old server's, picked up by the completion-callback restart (spec 01.01 FM-01.01-1) rather than a process restart.
  - **Behaviour**: `sequenceHighWaterPosition` is a process static that nothing resets on an engine restart (`setupDebeziumEventCapture` only raises the floor through `seedVersionFloorFromDurableMark`). Every position of the new log compares below the old mark, so `nextVersionAssignment` classifies every new record as a redelivery and does not clamp it: the late-commit floor (spec 01.04 §3.1) is off, and any clock skew between the old and new primary shows through, until the new numbering passes the old mark — possibly never in the life of the process.
  - **Detection**: none; nothing is logged (the identity WARN of FM-01.02-1 needs a prefix change).
  - **Blast radius**: silent value divergence on keys written by a late-committing transaction (the older write wins in ReplacingMergeTree); row counts intact.
  - **Recovery**: restart the connector PROCESS after every source failover or binlog reset: the mark starts empty and the floor is seeded from the durable mark (spec 02.02 §3.5), so every record is clamped above the previous run. Then check the tables written since the failover with the checksum job and repair diverged ones with `ch-mysql-resync` (spec 11.04).
  - **RTO**: process restart ≈ 30 s + start once someone knows to do it; unmeasured.
  - **Test**: GAP: an in-process engine restart followed by positions of a same-named, lower-numbered log, asserting that a late-committing transaction still outranks the earlier commit (needs a restart hook that resets the mark).
  - **DEFECT**: a binlog reset or a failover into a lower-numbered log disables the commit-order floor silently for the rest of the process.

- **FM-01.02-3 Source failover without GTID: the durable coordinates name the old server's log**
  - **Trigger**: the source VIP/DNS moves to a promoted replica while `gtid_mode=OFF` (or the offset carries no GTID set).
  - **Behaviour**: Debezium 3.1.3 positions the binlog client from the offset's `file`/`pos` unless GTID mode is on and the offset has a GTID set (`BinlogStreamingChangeEventSource`: `setBinlogFilename`/`setBinlogPosition` vs `setGtidSet`, read in the bytecode). On the new server the file is absent — Debezium WARNs `Last recorded offset is no longer available on the server.` and the dump fails with server error 1236 (spec 01.07 FM-01.07-5) — or it exists with unrelated content, and the dump starts at an arbitrary byte of another log: a server error, a deserialization failure, or, if the byte happens to be an event boundary, transactions skipped or replayed without an error (server-side behaviour, unverified). The offset records the old `server_id`; nothing compares it with the new server.
  - **Detection**: none guaranteed; when it fails loudly, the lines of FM-01.07-5.
  - **Blast radius**: all tables; possible silent loss or duplication of every transaction between the old position and the new server's matching point.
  - **Recovery**: stop the connector; find the promoted server's binlog coordinates at the failover point; `sink-connector-client change_replication_source --binlog_file <f> --binlog_position <p>` (`UPDATE_BINLOG_COMMAND` in `sink-connector-client/main.go`); start; then `ch-mysql-resync` (spec 11.04) every table written around the failover. Prevention: `gtid_mode=ON` — Debezium then logs `Registering binlog reader with GTID set: '<set>'` and resumes at a transaction boundary on any server (FM-01.07-6).
  - **RTO**: GTID: one engine restart (spec 01.07 FM-01.07-1); without GTID: manual, unbounded; unmeasured.
  - **Test**: GAP: a two-server failover harness without GTID.
  - **DEFECT**: without GTID a failover can mis-position the reader with no detection; the recorded `server_id` is never checked.

- **FM-01.02-4 GTID versioning with `snowflake.id=false` across a failover**
  - **Trigger**: raw GTID versioning (`snowflake.id=false`, spec 02.06 §3.2.1) and a failover to a server whose own GTID transaction numbers are lower than the old server's.
  - **Behaviour**: `ClickHouseStruct.calculateVersion` uses the raw transaction number of the GTID (§3.2); the new server's numbers restart from its own sequence, so every update after the failover can carry a lower `_version` than the row already in ClickHouse.
  - **Detection**: none at the failover (the start WARN of `warnIfRawGtidVersioningWithDataSnapshot` covers data snapshots only).
  - **Blast radius**: silent loss of every update and delete to pre-existing keys until the new numbers pass the old ones.
  - **Recovery**: keep `snowflake.id=true` (default; the snowflake's timestamp dominates the GTID); after such a failover `ch-mysql-resync` (spec 11.04) the written tables.
  - **RTO**: table resync time; unmeasured.
  - **Test**: GAP: a version comparison across two GTID source UUIDs with `snowflake.id=false`.
  - **DEFECT**: raw GTID versions are not ordered across servers and nothing warns at the failover.

Summary: 4 failure modes, 3 DEFECT, 3 GAP.
