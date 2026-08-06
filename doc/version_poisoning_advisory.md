# Version Formula Fix - Poisoning Risk Advisory

## Summary

The version formula for `_version` column generation in ReplacingMergeTree tables was corrected
in this release. A previously-reverted PR (#1353, reverted by #1352) introduced a formula
`(sourceSec << 32) | pos` that produced versions approximately **4.3x larger** than the
established `ts_ms * 1_000_000 + sequenceNumber` formula used since 2.8.0.

## Who Is Affected

Any deployment that ran a build containing the `(sourceSec << 32) | pos` formula (PR #1353
prior to its revert) may have tables with poisoned `_version` values. These rows carry
versions in the ~7.67e18 range, while the corrected formula produces versions in the
~1.79e18 range.

**If you never ran the build from PR #1353, you are not affected.**

## Impact of Poisoned Rows

Rows written with the inflated version (~7.67e18) can never be superseded by the corrected
formula (~1.79e18) through normal CDC operations. UPDATEs and DELETEs targeting those rows
will be silently discarded by ReplacingMergeTree because the new version is numerically lower.

## Detecting Poisoned Tables

Run on each affected ClickHouse table:

```sql
SELECT
    database,
    table,
    max(_version) as max_ver,
    if(max_ver > 5000000000000000000, 'POISONED', 'OK') as status
FROM system.parts
WHERE active
GROUP BY database, table
HAVING max_ver > 5000000000000000000;
```

Or directly on the table:

```sql
SELECT count(*) as poisoned_rows
FROM your_database.your_table
WHERE _version > 5000000000000000000;
```

## Remediation Options

### Option A: Re-align versions (minimal downtime)

Scale down the inflated versions to the correct magnitude:

```sql
ALTER TABLE your_database.your_table
UPDATE _version = intDiv(_version, 4)
WHERE _version > 5000000000000000000;

-- Wait for mutation to complete
SELECT * FROM system.mutations
WHERE database = 'your_database' AND table = 'your_table' AND is_done = 0;
```

Note: `intDiv(_version, 4)` brings ~7.67e18 down to ~1.92e18, which is within the
acceptable magnitude family. Subsequent CDC events will produce versions > 1.92e18
(since current timestamps yield ~1.79e18 + growth), so ordering is preserved.

### Option B: Truncate and re-snapshot (clean slate)

```sql
TRUNCATE TABLE your_database.your_table;
```

Then restart the connector with `snapshot.mode=initial` to repopulate from the source.

### Option C: Manual mutation of specific rows

If only a few rows are affected:

```sql
ALTER TABLE your_database.your_table
UPDATE _version = 1785678221550000000
WHERE _version > 5000000000000000000 AND primaryKey = 'specific_value';
```

## Prevention

The corrected formula:
- Stays in the `ts_ms * 1_000_000` magnitude family (~1.79e18 at 2026 timestamps)
- Handles binlog rotation correctly (does not rely solely on `pos` for ordering)
- Asserts if `pos > 2^32` rather than silently truncating
- Warns if `ts_ms` approaches the 2109 ceiling (~2^42 ms)

## Version Priority in calculateVersion()

1. **GTID** (via SnowFlakeId) - if available
2. **sequenceNumber** (set by lightweight batch loop: `ts_ms * 1e6 + counter`)
3. **pos-based fallback** (Kafka Connect only: `ts_ms * 1e6 + deriveSubMs(file, pos)`)
4. **LSN** (PostgreSQL)
