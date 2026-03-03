# Run 19 Checksum Mismatch Investigation Report

## Executive Summary

Investigation of 3 tables with perfect row counts but checksum mismatches in Run 19 revealed **two distinct root causes**:

1. **Root Cause #1 — Empty String → NULL Conversion (Connector Bug)**: The Debezium CDC connector converts PostgreSQL empty strings (`''`) to `NULL` when writing to ClickHouse `Nullable(String)` columns. This is a **persistent data fidelity issue** affecting 508+ rows in `alerts_tagcache` alone.

2. **Root Cause #2 — CDC Drift Window (Checksum Architecture Gap)**: The CH checksum is computed **after** the PG checksum within Phase B, but the connector continuously applies CDC changes from the PRIMARY to ClickHouse even while the standby's WAL replay is paused. This creates a temporal gap where CH reflects a later state than PG's REPEATABLE READ snapshot.

---

## Tables Investigated

| Table | Row Count | Checksum Status | Root Cause |
|-------|-----------|----------------|------------|
| `alerts_tagcache` | 458,023 | MISMATCH | **#1** Empty→NULL + **#2** CDC drift |
| `alerts_alert` | 384,012 | MISMATCH | **#2** CDC drift (4 columns) + **#1** (1 row) |
| `alerts_alertevent` | 71,956,417 | MISMATCH | Likely **#2** CDC drift (not fully verified due to SSH timeout) |

---

## Detailed Findings

### Root Cause #1: Empty String → NULL Conversion

#### Discovery Method
Per-column checksum comparison on `alerts_tagcache` (simplest table, no jsonb columns) isolated the `value` column as the sole divergent column.

#### Per-Column Checksum Results — `alerts_tagcache`

| Column | PG Type | Nullable | PG bucket_a | CH bucket_a | Match? |
|--------|---------|----------|-------------|-------------|--------|
| `id` | integer | NO | 969,182,047,173,154 | 969,182,047,173,154 | ✅ |
| `created` | timestamptz | NO | 979,628,771,880,594 | 979,628,771,880,594 | ✅ |
| `modified` | timestamptz | NO | 970,610,966,266,667 | 970,610,966,266,667 | ✅ |
| `key` | varchar | NO | 994,844,036,130,226 | 994,844,036,130,226 | ✅ |
| **`value`** | **varchar** | **YES** | **972,080,698,668,798** | **972,142,359,028,156** | **❌** |
| `owning_group_id` | integer | YES | 1,144,616,119,971,967 | 1,144,616,119,971,967 | ✅ |
| `public` | boolean | NO | 1,572,915,159,262,492 | 1,572,915,159,262,492 | ✅ |

#### Null/Empty Breakdown — `alerts_tagcache.value`

| Category | PostgreSQL | ClickHouse | Delta |
|----------|-----------|------------|-------|
| NULL | 2 | 510 | **+508** |
| Empty string (`''`) | 527 | 20 | **-507** |
| Has actual value | 451,283 | 451,282 | -1 |
| **Total** | **451,812** | **451,812** | 0 |

**508 rows** that are empty string `''` in PostgreSQL are stored as `NULL` in ClickHouse. This is a known Debezium behavior where the connector (or the sink writer) treats empty strings as null values when writing to `Nullable(String)` columns.

#### Concrete Example

Sample IDs where PG has `value = ''` (empty string, length=0) but CH has `value = \N` (NULL):

```
ID=1371866  key=server_status      PG: value=''  CH: value=NULL
ID=1371899  key=jira_it_teams      PG: value=''  CH: value=NULL
ID=1372545  key=server_status      PG: value=''  CH: value=NULL
ID=1373184  key=server_current_state PG: value='' CH: value=NULL
ID=1535781  key=first_notice_date   PG: value=''  CH: value=NULL
ID=1890856  key=manufacturer        PG: value=''  CH: value=NULL
ID=2314925  key=management_address  PG: value=''  CH: value=NULL
ID=3140375  key=Rack               PG: value=''  CH: value=NULL
```

#### Same Pattern in `alerts_alert`

| Column | PG NULL | PG Empty | CH NULL | CH Empty | Issue |
|--------|---------|----------|---------|----------|-------|
| `alert_description` | 379,016 | 1 | 379,017 | 0 | **1 row: PG empty→CH NULL** |
| `latest_note` (NOT NULL) | 0 | 236,624 | 0 | 236,624 | ✅ (NOT NULL prevents issue) |
| `until_expiry_state` | 384,016 | 0 | 384,016 | 0 | ✅ (no empty strings in PG) |

---

### Root Cause #2: CDC Drift Window

#### Discovery Method
Per-column checksum comparison on `alerts_alert` found **4 divergent NOT NULL columns** (`modified`, `state`, `tags`, `ages`) — these cannot be affected by the empty→NULL bug since they are NOT NULL. The divergence must be from live data changes.

#### Per-Column Checksum Results — `alerts_alert`

| Column | PG Type | Nullable | PG bucket_a | CH bucket_a | Delta | Match? |
|--------|---------|----------|-------------|-------------|-------|--------|
| `id` | varchar | NO | 825,317,050,165,842 | 825,317,050,165,842 | 0 | ✅ |
| `created` | timestamptz | NO | 823,534,429,130,361 | 823,534,429,130,361 | 0 | ✅ |
| **`modified`** | **timestamptz** | **NO** | **824,889,075,308,628** | **824,885,891,165,753** | **-3.2B** | **❌** |
| `type` | varchar | NO | 944,521,907,580,634 | 944,521,907,580,634 | 0 | ✅ |
| **`state`** | **varchar** | **NO** | **488,856,013,610,236** | **488,854,699,061,584** | **-1.3B** | **❌** |
| `latest_note` | text | NO | 1,210,049,783,348,579 | 1,210,049,783,348,579 | 0 | ✅ |
| `until_expiry_state` | varchar | YES | 1,366,600,194,214,288 | 1,366,600,194,214,288 | 0 | ✅ |
| `alert_description` | text | YES | 1,359,532,373,410,114 | 1,359,532,373,410,114 | 0 | ✅ |
| **`tags`** | **jsonb** | **NO** | **826,444,517,780,156** | **826,445,726,444,197** | **+1.2B** | **❌** |
| `attachment_metadata` | jsonb | NO | 968,944,903,641,208 | 968,944,903,641,208 | 0 | ✅ |
| **`ages`** | **jsonb** | **NO** | **824,033,949,462,299** | **824,035,841,477,257** | **+1.9B** | **❌** |
| `id_fields` | jsonb | YES | 1,311,883,642,758,078 | 1,311,883,642,758,078 | 0 | ✅ |

The divergent columns (`modified`, `state`, `tags`, `ages`) are all NOT NULL, so the empty→NULL bug cannot be the cause. These are columns that change together during alert state transitions — consistent with live updates to alerts while the checksum is running.

#### Architecture Gap Explanation

The checksum architecture has a fundamental timing problem:

```
Timeline during Run 19:
─────────────────────────────────────────────────────────────────
11:35:38  PG standby WAL replay paused
11:35:38  PG REPEATABLE READ snapshot started (frozen view of standby)
11:35:38  CH caught up to PG standby LSN
11:35:38  Phase A: CH count + tier3 queries (1.1s, parallel) — checksum NOT precomputed
11:35:39  Phase B begins: PG queries run serially on REPEATABLE READ

11:35:39  alerts_alert PG checksum computed (snapshot from 11:35:38)
11:35:42  alerts_alert CH checksum computed ← CDC has applied ~3s of new changes!
                                             PRIMARY → connector → CH continues
                                             while standby is frozen

11:48:00  alerts_alertevent PG checksum (snapshot still from 11:35:38)
11:48:45  alerts_alertevent CH checksum ← CDC has applied ~13 MINUTES of changes!

11:50:37  alerts_tagcache PG checksum (snapshot from 11:35:38)
11:50:40  alerts_tagcache CH checksum ← CDC has applied ~15 MINUTES of changes!
─────────────────────────────────────────────────────────────────
```

**The connector reads from the PRIMARY PostgreSQL**, not the standby. Pausing WAL replay on the standby freezes the PG side (REPEATABLE READ reads from the standby), but CDC changes from the PRIMARY continue flowing into ClickHouse. By the time the CH checksum is computed for each table, CH has received minutes of additional changes that the PG snapshot doesn't include.

Key code evidence from [`_query_ch_for_table()`](sink-connector/python/db_compare/top_level_postgres_checksum.py:1211):
```python
# Line 1264-1270: Phase A explicitly sets ch_cksum = None
# "the checksum needs PG column metadata for type-aware expressions,
#  so we CANNOT precompute it without PG metadata."
ch_cksum = None
```

And from [`compare_table()`](sink-connector/python/db_compare/top_level_postgres_checksum.py:762):
```python
# Line 762-771: Since ch_precomputed['checksum'] is always None,
# CH checksum is ALWAYS computed on-the-fly AFTER the PG checksum
if ch_precomputed and ch_precomputed.get('checksum') is not None:
    ch_checksum = ch_precomputed['checksum']
else:
    ch_checksum = get_ch_checksum(...)  # ← This always executes
```

#### Write Rate Evidence

`alerts_alert` has ~50-100+ modifications per minute:
```
12:21:00  134 rows modified
12:25:00  129 rows modified  
12:29:00  174 rows modified
```

With ~3 seconds between PG and CH checksum queries for `alerts_alert`, and ~15 minutes for `alerts_tagcache`, even a moderate write rate produces checksum divergence.

---

## Impact Assessment

### Root Cause #1 (Empty String → NULL)
- **Severity**: Medium — actual data fidelity issue
- **Scope**: Affects any `Nullable(String)` column in CH where the PG source has empty strings
- **Data Impact**: 508 rows in `alerts_tagcache`, 1 row in `alerts_alert.alert_description`
- **Nature**: Persistent — these rows will always mismatch unless the connector is fixed or data is repaired

### Root Cause #2 (CDC Drift Window)
- **Severity**: High — makes checksum unreliable for actively-written tables
- **Scope**: Affects any table with active writes during the checksum window
- **Data Impact**: Not actual data corruption — the data IS correct, the checksum comparison is comparing different points in time
- **Nature**: Transient — running checksum during a write quiescence window would eliminate these false positives

---

## Recommendations

### Fix Root Cause #1: Empty String → NULL Conversion

1. **Connector-level fix**: Investigate the Debezium PostgreSQL connector and the ClickHouse sink writer to find where empty strings are converted to NULL. The fix should preserve empty strings as empty strings in `Nullable(String)` columns.

2. **Checksum workaround**: In the checksum code, treat NULL and empty string as equivalent for `Nullable(String)` columns by using `coalesce(col, '')` on both sides (which is already done), BUT also add `CASE WHEN col IS NULL THEN '' ELSE col END` normalization on the CH side before hashing. However, this would mask the actual data divergence.

3. **Data repair**: For existing data, run an UPDATE on CH to convert NULLs back to empty strings where PG has empty strings:
   ```sql
   -- Example for alerts_tagcache
   ALTER TABLE `awacs-qa`.alerts_tagcache 
   UPDATE "value" = '' 
   WHERE is_deleted = 0 AND "value" IS NULL 
   AND "id" IN (SELECT id FROM pg_source WHERE value = '');
   ```

### Fix Root Cause #2: CDC Drift Window

1. **Precompute CH checksums in Phase A** (recommended): Modify Phase A to include PG column metadata retrieval (from a separate non-snapshot PG connection), so CH checksums can be precomputed in parallel immediately after LSN catch-up. This narrows the drift window to <2 seconds for all tables.

2. **Pause the connector during checksum**: Stop the CDC connector before starting the checksum, ensuring CH doesn't receive new changes. Resume after completion.

3. **Add `_version` filter on CH side**: Instead of `FINAL WHERE is_deleted = 0`, filter on `_version <= snapshot_version` to only include rows up to the PG snapshot LSN. This requires mapping PG LSN to CH `_version`.

4. **Schedule checksums during low-activity windows**: Run checksums during maintenance windows when write rates are minimal. This doesn't fix the problem but reduces false positives.

---

## Appendix: Verification Commands Used

### Per-Column PG Checksum
```sql
SELECT count(*), 
  sum(('x' || substring(md5(col_expr), 1, 8))::bit(32)::int8) as bucket_a
FROM public.table_name;
```

### Per-Column CH Checksum
```sql
SELECT count(*),
  sum(reinterpretAsInt64(reverse(unhex(substring(hex(MD5(col_expr)), 1, 8))))) as bucket_a
FROM `awacs-qa`.table_name FINAL
WHERE is_deleted = 0
SETTINGS do_not_merge_across_partitions_select_final = 1;
```

### Empty/NULL Distribution Check
```sql
-- PostgreSQL
SELECT 'null', count(*) FROM table WHERE col IS NULL
UNION ALL SELECT 'empty', count(*) FROM table WHERE col = '';

-- ClickHouse  
SELECT countIf(col IS NULL) as null_cnt, countIf(col = '') as empty_cnt
FROM table FINAL WHERE is_deleted = 0;
```
