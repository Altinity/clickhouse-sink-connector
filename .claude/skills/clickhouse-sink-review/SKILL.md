---
name: clickhouse-sink-review
description: Review a pull request on the Altinity clickhouse-sink-connector (Debezium CDC to ClickHouse) for data-loss, ordering, offset, type-mapping and DDL bugs. Use when given a PR number or URL for this repo, or asked to review a PR.
argument-hint: <PR number or URL>
---

# ClickHouse Sink Connector PR review

You are reviewing a pull request to `Altinity/clickhouse-sink-connector`: a Java 17 / Maven
project that replicates MySQL, MariaDB, PostgreSQL and MongoDB changes into ClickHouse using
Debezium (3.1.x). A bug here rarely crashes anything. It silently loses, duplicates or reorders
rows in someone's analytics database. Review for that first; style comes last.

The argument is a PR number (`1437`) or URL (`https://github.com/Altinity/clickhouse-sink-connector/pull/1437`).
If none was given, ask for one.

## 1. Fetch the PR

Run from the repo checkout. `N` is the PR number.

1. Metadata and description — try `gh pr view N --json title,body,baseRefName,headRefOid,commits,files`.
   If `gh` is unavailable or blocked, use the REST API (`gh api repos/Altinity/clickhouse-sink-connector/pulls/N`),
   and failing that fetch the PR page. The description and earlier review comments tell you what the
   author intended and which concerns were already raised; don't re-report resolved ones.
2. Code — `git fetch origin pull/N/head:pr-N <base>` (base is usually `develop`), then
   `MB=$(git merge-base origin/<base> pr-N)` and diff `$MB..pr-N`. Never diff against the tip of
   the base branch: that mixes in unrelated commits merged since the PR branched. If the history is
   shallow and `merge-base` fails, deepen with `git fetch --deepen=500 origin pull/N/head <base>`.
3. Check out `pr-N` (or use `git show pr-N:path`) so you read whole files at the PR's version.

## 2. Triage

1. Split changed files: production Java (`*/src/main/java/**`), tests (`*/src/test/**`, `tests/`),
   build/config (`pom.xml`, `deploy/`, workflows, YAML), and everything else (docs, `specs/`,
   `formal_specs/`, Python tools). Get line counts with `git diff --numstat $MB pr-N`.
2. Classify production files by area using the map below. Areas touching invariants 1–4 or 6
   (offsets, threading, versions, deletes, DDL) are **tier 1**; conversion, type mapping and
   writing are **tier 2**; config, validators, REST API, logging are **tier 3**.
3. **Large PRs.** If production Java changes exceed ~1,500 lines, don't skim everything evenly.
   Review tier 1 files in full, tier 2 where the diff touches value or column handling, and tier 3
   only for the config checklist. Read tests only to judge coverage of what you reviewed. State
   exactly what you did and didn't review in the report, and suggest splitting the PR if
   unrelated concerns are bundled together.
4. For each file you review, read the **whole** changed method plus its callers and the code paths
   that consume what it produces, not just the hunk. Most real bugs here live in what the hunk
   *didn't* change.
5. Load `references/checklist.md` and walk only the sections for the areas touched.

### Module and area map

| Area | Where | What can go wrong |
|---|---|---|
| Embedded engine (lightweight) | `sink-connector-lightweight/.../embedded/cdc/` — `DebeziumChangeEventCapture`, `DebeziumOffsetStorage`, `DebeziumJdbcStorageOperations` | offsets committed before rows land; heartbeats advancing past unwritten data; engine restart loops |
| Kafka Connect sink | `sink-connector/.../ClickHouseSinkTask`, `ClickHouseSinkConnector`, `KafkaProvider` | `preCommit`/`flush` offsets ahead of ClickHouse; rebalance handling |
| Batching & threading | `executor/` — `ClickHouseBatchRunnable`, `ClickHouseBatchExecutor`, `ClickHouseBatchWriter`, `DebeziumOffsetManagement`; `model/RoutedBatch` | per-table/per-key ordering broken across threads; shared static state races; retries that drop or re-apply batches |
| Record conversion | `converters/` — `ClickHouseConverter`, `DebeziumConverter`, `ClickHouseDataTypeMapper`; `model/ClickHouseStruct` | wrong before/after section per op; timezone shifts; precision/overflow; nulls vs defaults |
| Writing | `db/DbWriter`, `db/batch/` — `PreparedStatementExecutor`, `PreparedStatementFieldMapper`, `GroupInsertQueryWithBatchRecords`, `CdcOperation` | version/sign/is_deleted wrong; columns bound to wrong index; metadata cache stale after DDL |
| Table/DDL | `db/operations/` (auto-create, alter), lightweight `ddl/parser/` (ANTLR `MySqlParser.g4`) | MySQL DDL mistranslated; DDL applied out of order with DML; replicated engines missing `ON CLUSTER`/ZK path |
| Config | `*Config`, `*ConfigVariables`, `config/` YAML, `EnvironmentVariables` | new key not wired through both modules; unsafe default; secrets logged |
| Replication history | `db/batch/ReplicationHistoryHandler`, `history/` | temporal rows (`_valid_from/_valid_to`) inconsistent with the main table |

## 2. Core invariants (check these on every review)

These are the rules the codebase is built around. A change that weakens one is at least **High**.

1. **Offsets trail data.** No source offset (binlog/GTID, LSN, Kafka offset) may be committed until
   every record at or before it is durably in ClickHouse. Watch `markProcessed`/`markBatchFinished`,
   heartbeat/control-record commits, and `DebeziumOffsetManagement.hasUnwrittenBatches()`. Any new
   path that hands a batch to a consumer must increment `batchHandedOff()` *before* it is visible,
   and call `batchHandoffFailed()` if it never arrives.
2. **Per-key order is preserved.** Changes for the same primary key must reach ClickHouse in source
   order, and the version column must increase with that order. Anything that splits a table's
   batch across threads, reorders a queue, or retries a later batch before an earlier one breaks it.
3. **Versions are monotonic and unique per row change.** `_version` (or the configured version
   column) for ReplacingMergeTree comes from source position (GTID/LSN/ts via `SnowFlakeId` or the
   record's sequence number). Two different changes to the same key must never get the same version;
   ties make ReplacingMergeTree keep an arbitrary row.
4. **Deletes are rows, not absences.** With `is_deleted` (new ReplacingMergeTree) a delete writes a
   tombstone from the `before` section; with CollapsingMergeTree it writes `sign = -1`. A delete that
   falls through to an insert with `is_deleted = 0`/`sign = 1` resurrects data.
5. **Snapshot (`op=r`) and streaming (`op=c/u/d`) are interchangeable for the target.** A snapshot
   row followed by a stream update of the same key must resolve to the update.
6. **DDL is a barrier.** DML after a DDL in the source must not be written with the pre-DDL column
   list, and DML before it must not be written with the post-DDL list. Metadata caches
   (`DBMetadata`, `CacheInvalidationManager`) must be invalidated in step.
7. **Failures don't silently skip.** A batch that fails must be retried, routed to the error table,
   or stop the task. `catch (Exception e) { log... }` followed by marking the batch done is data loss.
   Fatal ClickHouse errors (see `ClickHouseErrorClassifier`) should stop the task, not loop forever.

## 3. Report

Write the review to `review-pr-N.md` and show it to the user in this shape:

```
## PR #N: <title>
<2-3 sentences: what the PR does, overall risk, recommendation: approve / approve with fixes / request changes>

**Reviewed:** <files or areas read in full>  **Not reviewed:** <what was skipped and why>

## Findings
### [Critical|High|Medium|Low] <short title>
**Where:** path/File.java:123 (method)
**Invariant / area:** e.g. "Offsets trail data"
**Problem:** what breaks, under what sequence of events (be concrete: which thread, which op, which crash point)
**Fix:** the specific change, with a code snippet when it's short
**Test:** the unit test or *IT that would catch it
```

Severity guide:
- **Critical** — rows lost, duplicated as live, or resurrected after delete; offsets committed early.
- **High** — wrong values (timezone, precision, overflow, wrong column), ordering broken under
  concurrency, DDL mistranslated, task hangs or crash-loops.
- **Medium** — missing test for a risky path, config not validated, resource leak, unclear retry.
- **Low** — readability, logging, naming, Javadoc.

Rules for findings:
- Only report what you can tie to a line and a failure sequence. Mark anything you couldn't confirm
  as *Question* instead of a finding.
- Don't pad with style nits when there are correctness issues; group Low items in one list.
- If the change touches a risky area and adds no test, say which existing test class to extend
  (unit tests sit next to the code under `src/test`; integration tests are `*IT` using Testcontainers).
- Cite lines at the PR's head commit, so they match what reviewers see on GitHub, and name that
  commit in the report (long-lived PRs keep moving).
- Before reporting a gap, grep the tests for it: this repo has many narrowly named tests
  (`Offset*Test`, `Handoff*Test`, `*RaceTest`). Often the single-path case is covered and the bug
  is in the composite path (coalesced writes, multi-table batches, restarts).

**Posting.** Don't post anything to GitHub unless the user asks. When they do, post one review
with `gh pr review N --comment --body-file review-pr-N.md` (use `--request-changes` only if the
user says so). If `gh` can't write to the repo, say so and leave the file for them to paste.

## 4. Optional: verify

When a JDK and Maven are available, compile and run the unit tests for the touched module:

```
./mvnw -q -pl sink-connector -am test -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl sink-connector-lightweight -am test -Dtest='*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

The lightweight module's Surefire config includes `**/*IT.java`, so always pass the `-Dtest`
filter. Don't run `*IT` tests unless the user asks; they need Docker and take a long time.
