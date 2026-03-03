# Binary Search: Divergent Rows in `alerts_alertevent`

## Summary

**Table:** `alerts_alertevent`  
**Total rows:** 72,382,183  
**ID range:** [309,239,357 – 426,968,068]  
**Row counts match:** ✓ (both PG and CH have identical counts)  
**Divergent rows found:** **6** (out of 72.4M = 0.0000083%)  
**Columns excluded:** `alert_description` (known empty→NULL bug, skipped by design)

## Root Causes

All 6 divergent rows share **exactly the same two root causes**:

### 1. JSONB Single-Quote Escaping (`tags` column)

| Database | Value |
|----------|-------|
| **PostgreSQL** | `"alert_info": "info '1'"` |
| **ClickHouse** | `"alert_info": "info \'1\'"` |

The sink connector backslash-escapes single quotes inside JSONB string values when writing to ClickHouse. PostgreSQL stores `'` as a literal single quote, but ClickHouse receives `\'`. This affects any JSONB field containing single quotes.

### 2. DateTime64 Overflow / Far-Future Timestamp Clamping (`until` column)

| Database | Value |
|----------|-------|
| **PostgreSQL** | `3999-12-31 18:00:00-06` → UTC: `4000-01-01 00:00:00.000000` |
| **ClickHouse** | `2299-12-31 23:00:00.000000` |

PostgreSQL `timestamptz` supports dates far beyond year 4000. ClickHouse `DateTime64(6)` has a maximum representable date of **2299-12-31 23:59:59.999999**. The sink connector (or ClickHouse itself) clamps the value to the maximum, resulting in `2299-12-31 23:00:00.000000`.

## Binary Search Drill-Down

```
Level 1 (10 chunks over full ID range):
  → 1/10 mismatched: Chunk 5 [356,330,845 – 368,103,717)  count=7,998,187

Level 2 (10 sub-chunks of Chunk 5):
  → 1/10 mismatched: [365,749,149 – 366,926,437)  count=1,177,095

Level 3 (10 sub-sub-chunks):
  → 4/10 mismatched:
    [365,749,149 – 365,866,878)  count=117,729
    [365,984,607 – 366,102,336)  count=117,729
    [366,102,336 – 366,220,065)  count=117,631
    [366,220,065 – 366,337,794)  count=117,729

Level 4 (10 sub-chunks each of the 4 mismatched L3 chunks):
  → Narrowed to 5 final sub-chunks, then per-row MD5 comparison

Per-row scan:
  → 6 individual rows with hash mismatches
```

## Divergent Row Details

### Row 1: ID `365768857`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `id` | 365768857 | 365768857 | ✓ |
| `tags` | `...info '1'...` | `...info \'1\'...` | ✗ |
| `alert_seen` | 2025-08-01 10:33:54 | 2025-08-01 10:33:54 | ✓ |
| `datetime` | 2025-08-01 10:45:40.273824 | 2025-08-01 10:45:40.273824 | ✓ |
| `type` | lorem-alert | lorem-alert | ✓ |
| `state` | RSV | RSV | ✓ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| `note` | (empty) | (empty) | ✓ |
| `alert_id` | aabbas-mock-1 | aabbas-mock-1 | ✓ |
| `owner_id` | 877 | 877 | ✓ |
| `owning_group_id` | 84 | 84 | ✓ |
| `user_id` | 877 | 877 | ✓ |
| `received_out_of_order` | false/0 | 0 | ✓ |
| `attachment_metadata` | {} | {} | ✓ |
| `count` | 1 | 1 | ✓ |
| `incident_id` | 7519008 | 7519008 | ✓ |
| `until_expiry_state` | RSV | RSV | ✓ |

### Row 2: ID `365995738`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `tags` | `...info '1'...` (jira_id=FULCRUM-646) | `...info \'1\'...` | ✗ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| All other columns | — | — | ✓ |

### Row 3: ID `366168733`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `tags` | `...info '1'...` (jira_id=FULCRUM-671) | `...info \'1\'...` | ✗ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| All other columns | — | — | ✓ |

### Row 4: ID `366197677`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `tags` | `...info '1'...` (jira_id=FULCRUM-671) | `...info \'1\'...` | ✗ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| All other columns | — | — | ✓ |

### Row 5: ID `366227330`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `tags` | `...info '1'...` (jira_id=FULCRUM-671) | `...info \'1\'...` | ✗ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| All other columns | — | — | ✓ |

### Row 6: ID `366227332`

| Column | PG | CH | Match? |
|--------|----|----|--------|
| `tags` | `...info '1'...` (jira_id=FULCRUM-674) | `...info \'1\'...` | ✗ |
| `until` | **4000-01-01 00:00:00** | **2299-12-31 23:00:00** | ✗ |
| All other columns | — | — | ✓ |

## All Divergent IDs

```
365768857, 365995738, 366168733, 366197677, 366227330, 366227332
```

## Common Pattern

All 6 rows belong to the same mock user (`aabbas-mock-1`, `owner_id=877`, `owning_group_id=84`) and share:
- JSONB `tags` containing `"alert_info": "info '1'"` with single quotes
- `until` timestamp set to `3999-12-31 18:00:00-06` (a "never expires" sentinel value)
- All are `lorem-alert` type alerts

## Methodology

- **Script:** [`binary_search_divergent_rows.py`](../../sink-connector/python/db_compare/binary_search_divergent_rows.py) deployed to `/tmp/binary_search_divergent_rows.py` on `fpif-dbachl4`
- **Aggregation:** XOR-based checksums (`bit_xor` in PG, `groupBitXor` in CH) to avoid integer overflow
- **Hash function:** MD5 of concatenated normalized columns → first 8 bytes → 64-bit integer for XOR aggregation
- **Normalization:** Timestamps converted to UTC text, booleans to `0`/`1`, NULLs to empty strings, JSONB to `::text`
- **Signed→unsigned conversion:** PG `bit_xor` returns signed bigint; normalized to unsigned via `val + 2^64` if negative
- **Binary search depth:** 4 levels of 10-way subdivision (10 → 100 → 1,000 → 10,000 ranges), then per-row MD5 scan on final chunks

## Recommendations

1. **JSONB escaping fix:** The sink connector should not backslash-escape single quotes in JSONB text. This is a serialization bug in the connector's JSONB→String conversion.

2. **Far-future timestamp handling:** Either:
   - Clamp far-future timestamps in PG before they reach the connector (e.g., cap at `2299-12-31`)
   - Add explicit handling in the connector to detect DateTime64 overflow and clamp consistently
   - Document the ClickHouse DateTime64 limitation (max year 2299) and adjust application sentinel values accordingly

3. **Checksum tool enhancement:** The checksum comparison tool should have an option to ignore these known patterns (JSONB quote escaping, timestamp clamping) to avoid false positives on future runs.
