# PostgreSQL e2e suite: what each test justifies

The suite runs the tools from the tree named by `PYTOOLS_E2E_TOOLS_ROOT`
(default: this repository's `sink-connector/python`).
`PYTOOLS_E2E_DUMP_TOOLS_ROOT` overrides the tree for `ch-pg-dump` only. Running
the same tests against the pre-fix tools and against the fixed tools shows
which fix each test justifies. A justifying test fails on the pre-fix tools,
usually through a false success (exit 0, a false PASS, or wrong data loaded),
and passes on the fixed tools.

## Runs

Each run used the same stack: PostgreSQL 15 (`wal_level=logical`) and
ClickHouse 24.8, both as defined by
`sink-connector-lightweight/docker/docker-compose-postgres.yml`, in
external-stack mode. All three commands run from `sink-connector/python`.
`<prefix>` is the pre-fix tree, i.e. the 2.11.0 tools before the fix PRs,
extracted with `git archive <pre-fix commit> sink-connector/python | tar -x -C <scratch>`.
`<branch>` is this branch's `sink-connector/python`.

| Run | Command | Result |
|---|---|---|
| BEFORE (all tools pre-fix) | `PYTOOLS_E2E_TOOLS_ROOT=<prefix>/sink-connector/python PYTOOLS_E2E_EXTERNAL_STACK=1 ... python -m pytest -p no:cacheprovider -rA tests_e2e/postgres` | 27 failed, 4 passed, 2 xfailed |
| VERIFY-BEFORE (pre-fix verification tools, data loaded by the fixed dumper) | `PYTOOLS_E2E_TOOLS_ROOT=<prefix>/sink-connector/python PYTOOLS_E2E_DUMP_TOOLS_ROOT=<branch> ... python -m pytest -p no:cacheprovider -rA tests_e2e/postgres/test_pg_verification.py tests_e2e/postgres/test_pg_verify_fixes.py tests_e2e/postgres/test_pg_auto_diff.py` | 8 failed, 6 passed, 2 xfailed |
| AFTER (this branch) | `PYTOOLS_E2E_EXTERNAL_STACK=1 ... python -m pytest -p no:cacheprovider -rA tests_e2e/postgres` | 31 passed, 2 xfailed |

The VERIFY-BEFORE run separates the two fix PRs. In the BEFORE run, several
verification tests also fail because the pre-fix dumper collapses the keyless
table. VERIFY-BEFORE runs the pre-fix verification tools on correctly loaded
data, so a failure there is caused by the verification fixes alone.

PR labels used below:
- **PG dump PR**: ch-pg-dump fixes, spec 13.05 D-13.05-1..12 and D-13.05-17.
- **PG verify PR**: ch-checksum / ch-pg-checksum / ch-pg-count fixes, spec 13.07
  D-13.07-1..5 and D-13.07-23.
- **e2e PR**: this suite and the D-13.07-31 fix it found.

## Matrix

`-` means the test is not part of that run.

| Test | BEFORE | VERIFY-BEFORE | AFTER | Justifies |
|---|---|---|---|---|
| `test_pg_snapshot_dump.py::test_dump_exits_zero_and_loads_every_table` | FAIL: no slot verification, the keyless table loses rows | - | PASS | PG dump PR, D-13.05-3, D-13.05-5 |
| `test_pg_snapshot_dump.py::test_keyless_table_is_sorted_by_every_column` | FAIL: `ORDER BY tuple()`, 25 rows collapse to 1 | - | PASS | PG dump PR, D-13.05-5 |
| `test_pg_snapshot_dump.py::test_values_round_trip_exactly` | PASS | - | PASS | regression guard (UTC server, ISO DateStyle) |
| `test_pg_snapshot_dump.py::test_special_dates_saturate_to_the_type_bounds` | FAIL: `infinity` loaded as NULL | - | PASS | PG dump PR, D-13.05-9 |
| `test_pg_snapshot_dump.py::test_numeric_scale_values_are_loaded_exactly` | PASS | - | PASS | regression guard |
| `test_pg_snapshot_dump.py::test_offset_row_records_the_snapshot_lsn` | PASS | - | PASS | regression guard |
| `test_pg_snapshot_dump.py::test_slot_is_left_in_place_for_the_connector` | PASS | - | PASS | regression guard |
| `test_pg_snapshot_dump.py::test_missing_slot_fails_before_touching_clickhouse` | FAIL: exit 0 without a slot; loads and writes the offset | - | PASS | PG dump PR, D-13.05-3 |
| `test_pg_dump_fixes.py::test_failed_clickhouse_insert_fails_the_run_and_writes_no_offset` | FAIL: exit 0 and an offset written over a rejected INSERT | - | PASS | PG dump PR, D-13.05-1, D-13.05-2 |
| `test_pg_dump_fixes.py::test_skip_existing_refuses_a_partly_loaded_table` | FAIL: exit 0, the 30-of-62-row table is skipped and an offset is written | - | PASS | PG dump PR, D-13.05-4 |
| `test_pg_dump_fixes.py::test_two_source_tables_never_share_a_clickhouse_table` | FAIL: exit 0, two schemas merged into one table | - | PASS | PG dump PR, D-13.05-7 |
| `test_pg_dump_fixes.py::test_schema_include_list_is_anchored` | FAIL: schema list `pye2e` also dumps `pye2e_b` | - | PASS | PG dump PR, D-13.05-6 |
| `test_pg_dump_fixes.py::test_table_include_list_keeps_its_schema_and_is_anchored` | FAIL: `pye2e.t_orders` also selects `x_t_orders_old` | - | PASS | PG dump PR, D-13.05-6 |
| `test_pg_dump_fixes.py::test_zone_less_timestamp_keeps_its_wall_clock_in_a_non_utc_zone` | FAIL: every value shifted by the zone offset (6 h) | - | PASS | PG dump PR, D-13.05-8 |
| `test_pg_dump_fixes.py::test_session_datestyle_cannot_change_loaded_values` | FAIL: dates silently loaded as NULL under `SQL, DMY` | - | PASS | PG dump PR, D-13.05-10 |
| `test_pg_dump_fixes.py::test_overridden_column_is_loaded_as_overridden` | FAIL: overridden `String` column gets `''` instead of `infinity` | - | PASS | PG dump PR, D-13.05-11 |
| `test_pg_dump_fixes.py::test_pgdump_strategy_is_refused_before_touching_anything` | FAIL: not refused, the strategy runs (exit 1) | - | PASS | PG dump PR, D-13.05-12 |
| `test_pg_verification.py::test_ch_checksum_passes_on_dumped_data[snapshot]` | FAIL: keyless table mis-loaded | PASS | PASS | regression guard for verification; BEFORE failure is D-13.05-5 |
| `test_pg_verification.py::test_ch_checksum_passes_on_dumped_data[per_table]` | FAIL: keyless table mis-loaded | PASS | PASS | as above |
| `test_pg_verification.py::test_pg_checksum_and_pg_count_agree` | FAIL: 25 source rows, 1 in ClickHouse | PASS | PASS | PG dump PR, D-13.05-5 (post-load count parity) |
| `test_pg_verification.py::test_standalone_tools_exit_1_when_a_table_fails[ch-pg-checksum]` | FAIL: exit 0, empty-table digest for a missing table | FAIL | PASS | e2e PR, D-13.07-31 |
| `test_pg_verification.py::test_standalone_tools_exit_1_when_a_table_fails[ch-pg-count]` | FAIL: exit 0 | FAIL | PASS | PG verify PR, D-13.07-23 |
| `test_pg_verification.py::test_planted_value_is_a_mismatch` | FAIL: keyless table mis-loaded | PASS | PASS | regression guard (the pre-fix checksum already caught a changed value) |
| `test_pg_verification.py::test_dropped_clickhouse_column_is_a_mismatch_naming_it` | FAIL: `MATCH PASS`, exit 0 | FAIL | PASS | PG verify PR, D-13.07-3 |
| `test_pg_verification.py::test_extra_clickhouse_table_is_reported` | FAIL: ClickHouse-only table not reported, exit 0 | FAIL | PASS | PG verify PR, D-13.07-4 |
| `test_pg_verification.py::test_saturated_infinity_values_verify` | XFAIL | XFAIL | XFAIL (strict) | open defect D-13.07-30 |
| `test_pg_verification.py::test_numeric_with_trailing_zero_verifies` | XFAIL | XFAIL | XFAIL (strict) | open defect D-13.07-8 |
| `test_pg_auto_diff.py::test_auto_diff_locates_planted_rows_by_primary_key` | FAIL: keyless table mis-loaded | PASS | PASS | regression guard |
| `test_pg_verify_fixes.py::test_uncomputable_clickhouse_checksum_is_error_not_pass` | FAIL: PASS, exit 0 with no ClickHouse checksum | FAIL | PASS | PG verify PR, D-13.07-1 |
| `test_pg_verify_fixes.py::test_count_delta_within_thresholds_is_not_reported_as_a_match` | FAIL: keyless table mis-loaded as well | FAIL: `RESULT: PASS — all 2 tables match`, exit 0, with a WARN row | PASS | PG verify PR, D-13.07-2 |
| `test_pg_verify_fixes.py::test_column_hidden_from_the_checksum_user_is_error` | FAIL: t_orders not ERROR | FAIL: `MATCH PASS`, exit 0, `note` never compared | PASS | PG verify PR, D-13.07-4 |
| `test_pg_verify_fixes.py::test_snapshot_mode_reads_postgres_in_one_repeatable_read_transaction` | FAIL: keyless table mis-loaded | PASS | PASS | regression guard only (D-13.07-5, see below) |
| `test_pg_verify_fixes.py::test_pg_checksum_exits_1_when_a_table_query_fails` | FAIL: exit 0 although the table's query raised | FAIL | PASS | PG verify PR, D-13.07-23 |

## Coverage of the fixes

Every fix below has at least one test that fails before and passes after, and
none of those tests is an xfail.

| Fix | Witness test(s) |
|---|---|
| D-13.05-1, D-13.05-2 load failures detected | `test_failed_clickhouse_insert_fails_the_run_and_writes_no_offset` |
| D-13.05-3 slot required before the snapshot | `test_missing_slot_fails_before_touching_clickhouse` |
| D-13.05-4 `--skip_existing` count proof | `test_skip_existing_refuses_a_partly_loaded_table` |
| D-13.05-5 keyless sorting key | `test_keyless_table_is_sorted_by_every_column` |
| D-13.05-6 connector lists anchored | `test_schema_include_list_is_anchored`, `test_table_include_list_keeps_its_schema_and_is_anchored` |
| D-13.05-7 target collisions refused | `test_two_source_tables_never_share_a_clickhouse_table` |
| D-13.05-8 zone-less timestamps in the column zone | `test_zone_less_timestamp_keeps_its_wall_clock_in_a_non_utc_zone` |
| D-13.05-9 special values saturate | `test_special_dates_saturate_to_the_type_bounds` |
| D-13.05-10 session settings pinned | `test_session_datestyle_cannot_change_loaded_values` |
| D-13.05-11 load honours overrides | `test_overridden_column_is_loaded_as_overridden` |
| D-13.05-12 pgdump refused | `test_pgdump_strategy_is_refused_before_touching_anything` |
| D-13.07-1 uncomputed checksum is ERROR | `test_uncomputable_clickhouse_checksum_is_error_not_pass` |
| D-13.07-2 WARN is not "all match" | `test_count_delta_within_thresholds_is_not_reported_as_a_match` |
| D-13.07-3 missing ClickHouse column is MISMATCH | `test_dropped_clickhouse_column_is_a_mismatch_naming_it` |
| D-13.07-4 coverage gaps reported | `test_extra_clickhouse_table_is_reported`, `test_column_hidden_from_the_checksum_user_is_error` |
| D-13.07-23 standalone exit codes | `test_standalone_tools_exit_1_when_a_table_fails[ch-pg-count]`, `test_pg_checksum_exits_1_when_a_table_query_fails` |
| D-13.07-31 no digest without a comparable column | `test_standalone_tools_exit_1_when_a_table_fails[ch-pg-checksum]` |

Not shown end to end:

- **D-13.07-5 (REPEATABLE READ snapshot).** The pre-fix tools already read in
  REPEATABLE READ on PostgreSQL 15. The probe (`probe/sitecustomize.py`) prints
  the isolation level of the open transaction before each checksum query, and
  it reads `repeatable read` in the VERIFY-BEFORE run too. The pre-fix sequence
  is psycopg2's implicit `BEGIN` followed by an explicit
  `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ`. PostgreSQL warns about
  the nested `BEGIN` but still applies its isolation option, because no
  snapshot has been taken yet. The fix (`set_session` plus a
  `SHOW transaction_isolation` check) is hardening: it makes the level explicit
  and verified rather than incidental. The test stays as a regression guard.
- **D-13.05-3, single exported snapshot.** Reading every table in one
  exported snapshot only differs from the old behaviour under concurrent writes
  during the load. This cannot be made deterministic, so it is covered by the
  offline tests. The slot half of the fix is shown above.
- **D-13.05-17 (`pipefail` in the psql-copy dump).** That strategy cannot be
  reached from `main` (D-13.05-15), so no end-to-end run can exercise it.
