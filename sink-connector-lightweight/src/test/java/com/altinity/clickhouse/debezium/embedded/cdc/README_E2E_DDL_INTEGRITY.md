# End-to-End DDL Data-Integrity Integration Test

`EndToEndDDLDataIntegrityIT` exercises the **full data flow** MySQL → Debezium
embedded connector → ClickHouse against **real** MySQL and ClickHouse containers
(Testcontainers, no stubs). It exists so the true end-to-end behavior of the
entire pipeline — and the full impact of any change — is observable and
regression-guarded, especially for DDL column changes.

## What it covers

| Test | Scenario | Key assertion |
|---|---|---|
| `testNormalCrud` | snapshot + INSERT / UPDATE / DELETE | values in ClickHouse match the source exactly under `FINAL` |
| `testAddColumnUnderConcurrentInsertsNoDataLoss` | `ADD COLUMN` while rows stream in (4 concurrent inserters) | **no post-DDL row** has a dropped/zeroed new column — the `price_usd` silent-data-loss regression |
| `testRenameModifyDropColumn` | `MODIFY` / `ADD`+`RENAME` / `DROP COLUMN` | renamed column exists on destination; values survive each change |
| `testRapidInterleavedDdlDmlRace` | 3 rapid `ADD COLUMN` rounds each followed by inserts | rows inserted after each ALTER keep their column value; all added columns exist |

The data-loss assertions are **value-level**: every post-`ADD COLUMN` row sets the
new column to a strictly-positive source value, so any `0`/`NULL` in ClickHouse
proves the column was silently dropped (the exact failure mode behind the
`partner_liquidation_trade.price_usd` incident). These tests pass because of the
per-table replication freeze + source-vs-destination integrity work in this
branch (PR #30); without it, `testAddColumnUnderConcurrentInsertsNoDataLoss`
fails.

## Running

Requires a working Docker daemon (CI default).

```bash
# JDK 17 is required by the build.
export JAVA_HOME=/path/to/jdk-17
mvn -pl sink-connector-lightweight -am install -DskipTests        # build core dep
mvn -pl sink-connector-lightweight test \
    -Dtest='EndToEndDDLDataIntegrityIT' -DfailIfNoTests=false
```

Images used: `docker.io/mysql:8.0.36`, `clickhouse/clickhouse-server:24.8`
(same as the other ITs, see `ITCommon`).

### Note on rootless podman

Under **rootless podman** (some sandboxes), Testcontainers' docker-java client
throws `NullPointerException: ... PullResponseItem.getStatus()` while parsing
podman's image-pull progress stream. This affects **all** ITs in this module
equally (e.g. `DebeziumChangeEventCaptureIT`), is unrelated to test logic, and
does not occur on a real Docker daemon. Pre-pulling the images (so Testcontainers
skips the pull) avoids it.
