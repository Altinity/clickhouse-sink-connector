# Why each fix is needed: the MySQL end-to-end suite against the pre-fix and the fixed tools

The suite in this directory runs the MySQL tools of `sink-connector/python` the way production uses them
(the scheduled checksum job, the owning DBA's manual recipes, the snapshot path, ch-mysql-resync) against a
real MySQL, ClickHouse and lightweight connector. `PYTOOLS_E2E_TOOLS_ROOT` selects the tool tree under test,
so the same tests run against:

- **pre-fix**: commit `d42a8740` (2.11.0 plus the domain-13 specs, no fix), extracted outside the repository;
- **fixed**: this branch (2.11.0 plus PRs 1538 loader, 1539 checksum (`e1724038`, `73e92977`), 1542 resync,
  1543 dumper, plus the fixes this suite itself forced, listed at the end).

CI runs the fixed tree only (`.github/workflows/python-toolset-e2e-mysql.yml`, job `python-toolset-e2e-mysql`).
The pre-fix run is the local procedure below; its observed results are in the next section.

## Observed runs (read this first)

All three runs used one local stack (MySQL 8.0, ClickHouse 24.8, the lightweight connector, external-stack mode,
`pytest tests_e2e/mysql/` run in the stack's network namespace), recreated before each run:

| Run | Tools | SQLAlchemy | Result |
|---|---|---|---|
| fixed | this branch | 2.0 (what `source ./install.sh` installs from PyPI) | 40 passed, 1 xfailed |
| fixed, production interpreter | this branch | 1.4 (the scheduled jobs install 1.4.43 from their package mirror) | 40 passed, 1 xfailed |
| pre-fix | 2.11.0 tools (`d42a8740`, specs only on top of 2.11.0) | 1.4 | 37 failed, 2 passed, 1 xfailed, 1 error |

The pre-fix tools run on SQLAlchemy 1.4 because they cannot run on 2.x at all (D-13.06-9, D-13.03-3); 1.4 is also
what production uses, so the pre-fix column shows each defect rather than that one crash.

Observed pre-fix outcome per test, and the fix that turns it green:

| Test | Pre-fix (observed) | Fixed | Justifies |
|---|---|---|---|
| `test_job_sources_install_sh_under_set_euo_pipefail` | FAIL: `install.sh: line 4: PYTHONPATH: unbound variable` | PASS | this PR, D-13.06-39 |
| `test_clean_data_passes_the_job` | FAIL: `Checksum difference` for `pyops.fills` on clean data (BIT(16)), the job verdict fails | PASS | this PR, D-13.06-38 |
| `test_bitemporal_where_selects_the_trading_day_window`, `test_debug_run_passes_the_job`, `test_single_database_run_passes_the_job`, `test_table_include_list_restricts_the_job`, `test_planted_difference_fails_the_job`, `test_planted_difference_in_the_renamed_database_fails_the_job`, `test_difference_in_an_ignored_column_passes_the_job`, `test_side_notes_reach_the_job_log_below_warning` | FAIL: the same clean-data `Checksum difference` on the BIT(16) table fails every job run, so these cannot pass whatever else they check | PASS | this PR, D-13.06-38 (each test's own assertion is a regression guard) |
| `test_empty_partition_does_not_fail_the_job` | PASS (2.11.0 logs an empty table as `No difference` at INFO) | PASS | regression guard for PR 1539 `73e92977` (no WARNING for EMPTY) |
| `test_empty_partition_is_reported_empty_not_matched` | FALSE MATCH: `No difference for pyref.daily_marks` with 0 rows on both sides, exit 0 | PASS | PR 1539 D-13.06-5 |
| `test_clickhouse_side_failure_fails_the_job` | FAIL: the driver dies with `TypeError: 'NoneType' object is not subscriptable`, no table gets a verdict | PASS | PR 1539 D-13.06-1 |
| `test_manual_checksum_recipe_is_equal_on_clean_data`, `test_manual_checksum_recipe_with_the_connector_binary_encoding`, `test_manual_checksum_recipe_names_the_diverged_row` | FAIL: `--no_wc` crashes the MySQL side (`'list' object has no attribute 'mappings'`) | PASS | PR 1539 `73e92977` D-13.06-26 (then -17, -27) |
| `test_mysql_count_agrees_with_real_counts` | FAIL: `--no_wc` crashes the count (`'list' object has no attribute 'fetchall'`) | PASS | PR 1539 `73e92977` D-13.06-26 |
| `test_clickhouse_count_agrees_with_real_counts` | FAIL: every table counted 0 without `--include_partitions_regex` | PASS | PR 1539 `73e92977` D-13.06-18 |
| `test_dumper_hands_over_a_verified_snapshot_position` | FAIL: exit 0 but no `snapshot_position.json`, the binlog position is not handed over | PASS | PR 1543 D-13.03-1 |
| `test_loader_fills_a_fresh_database` | FAIL: the load aborts on the table whose source has a `_sign` column (the loader drops that column, the TSV still carries it) | PASS | PR 1538 D-13.04-6 |
| `test_production_job_matches_the_loaded_snapshot`, `test_keyless_table_keeps_every_row_after_optimize_final`, `test_loaded_values_equal_the_streamed_values[fills\|instruments\|keyless_events]`, `test_clickhouse_count_agrees_between_the_live_and_the_restored_copy` | FAIL: blocked by the same aborted load | PASS | PR 1538 (the individual defects D-13.04-1, -3 and -10 are shown by their offline tests and Spec 13.04) |
| `test_loader_fails_loudly_on_a_corrupt_dump_chunk` | PASS: the legacy loader asserts on the failed pipeline, so D-13.04-4 is not reproduced through this entry point | PASS | regression guard |
| `test_dumper_fails_loudly_when_mysql_shell_fails` | FAIL: `FileNotFoundError` inside the test before the exit status is checked; not attributable to one defect | PASS | none claimed |
| `test_unlogged_change_is_reported_then_repaired_by_resync` | FAIL: the resync load fails (pre-fix loader), exit 1 | PASS | PR 1538 via the loader resync runs |
| `test_patch_refuses_to_replace_while_the_connector_is_behind_the_dump` | FAIL: the REPLACE runs and overwrites the planted ClickHouse row, exit 0 | PASS | PR 1542 D-13.08-5 |
| `test_patch_skip_load_refuses_a_scratch_table_it_did_not_load` | FAIL: `--skip-load` installs the stale scratch rows (`stale`), exit 0 | PASS | PR 1542 D-13.08-8 |
| `test_rewind_sql_requires_the_connector_stopped_attestation` | FAIL: the rewind INSERT is printed, exit 0 | PASS | PR 1542 D-13.08-9 |
| `test_rewind_sql_refuses_a_forward_rewind` | FAIL: the forward rewind INSERT is printed, exit 0 | PASS | PR 1542 D-13.08-6 |
| `test_rewind_sql_accepts_the_dumper_snapshot_position` | FAIL: no `snapshot_position.json` to read | PASS | PR 1543 D-13.03-1 |
| `test_z_patch_refuses_an_empty_restore_suffix` | ERROR at setup: the stale rows the pre-fix `--skip-load` installed are still there; not attributable | PASS | none claimed (D-13.08-11 is shown by its offline test) |
| `test_checksum_in_column_names_does_not_confuse_the_verdict` | FALSE MATCH: `No difference` for a table whose outputs could not be parsed, exit 0 | PASS | PR 1539 D-13.06-2 |
| `test_function_partition_expression_reaches_the_sides` | FAIL: the driver dies with `TypeError: 'NoneType' object is not subscriptable` | PASS | PR 1539 D-13.06-10 |
| `test_dollar_table_is_compared_under_its_own_name` | FAIL: the driver dies with the same `TypeError` | PASS | PR 1539 D-13.06-3 |
| `test_equal_mysql_and_clickhouse_host_strings_still_give_verdicts` | FAIL: exit 0 with no verdict line | PASS | PR 1539 D-13.06-4 |
| `test_packaged_driver_run_from_another_directory_reports_a_difference` | FALSE MATCH: both sides fail to start, `(None, None)` on both, `No difference for accounts` for the planted difference | PASS | PR 1539 D-13.06-1, D-13.01-2 |
| `test_loader_loads_a_table_with_a_dollar_in_its_name` | XFAIL | XFAIL (strict) | open defect D-13.04-32 |

The expected-outcome matrix below was written before these runs; where it differs from the table above, the
table above is what was observed.

## Local procedure (pre-fix against fixed)

```bash
# from the repository root; SCRATCH is any directory outside the repository
git archive d42a8740 sink-connector/python | tar -x -C "$SCRATCH"
# The pre-fix tools cannot run on SQLAlchemy 2.x (D-13.06-9, D-13.03-3): give them a 1.4 venv,
# otherwise every driver and dumper test fails on that one defect and hides the others.
python3 -m venv "$SCRATCH/venv-sa14"
"$SCRATCH/venv-sa14/bin/pip" install -r sink-connector/python/requirements.txt "sqlalchemy>=1.4,<2"
pip install -r sink-connector/python/tests_e2e/mysql/requirements.txt

# fixed tree, as CI runs it (install.sh in the job workspace, SQLAlchemy 2.x)
pytest -v sink-connector/python/tests_e2e/mysql
# pre-fix tree; each pytest session starts and removes its own compose stack, so the
# pre-fix run (which damages ClickHouse state on purpose) cannot leak into the next one
PYTOOLS_E2E_TOOLS_ROOT="$SCRATCH/sink-connector/python" PYTOOLS_E2E_PYTHON="$SCRATCH/venv-sa14/bin/python" \
    pytest -v sink-connector/python/tests_e2e/mysql
# optional: the fixed tree under SQLAlchemy 1.4, and the tree before 73e92977 (git archive 67a322f1)
```

With an external stack (`PYTOOLS_E2E_EXTERNAL_STACK=1`, see `mysql_e2e_support.py`) recreate the stack between
the two runs.

## Expected matrix (written before the observed runs)

Pre-fix outcome legend: FAIL = an assertion fails (wrong verdict, wrong exit code, wrong data); FALSE MATCH =
the tool reports equality or success for data that differs; PASS = passes on both trees.

### Production scheduled job (`test_mysql_01_production_job.py`)

| Test | Pre-fix (expected) | Fixed | Justifies |
|---|---|---|---|
| `test_job_sources_install_sh_under_set_euo_pipefail` | FAIL: `install.sh: line 4: PYTHONPATH: unbound variable` (observed) | PASS | this suite's fix, D-13.06-39 |
| `test_clean_data_passes_the_job` | FAIL: on SQLAlchemy 2.x TypeError at the first table, exit 1 (observed, D-13.06-9); on 1.4, `Checksum difference` for `pyops.fills` and `pyops.instruments` (BIT(16) rendered base64, observed, D-13.06-38), job verdict fails | PASS | registered defects D-13.06-9, D-13.06-38 (this suite's fixes); clean-data job on the mandatory config |
| `test_bitemporal_where_selects_the_trading_day_window` | FAIL: the job verdict fails on `pyops.fills` (D-13.06-38, and D-13.06-9 on SQLAlchemy 2.x). The bitemporal counts themselves (3 rows on each side) are expected to be right on 2.11.0: the `where` survives its `sh -c` quoting | PASS | D-13.06-38; production `where` contract |
| `test_debug_run_passes_the_job` | FAIL: as the clean run (D-13.06-38) | PASS | D-13.06-38 |
| `test_single_database_run_passes_the_job` | FAIL: as the clean run (D-13.06-38) | PASS | D-13.06-38 |
| `test_table_include_list_restricts_the_job` | FAIL: `pyops.fills`, `pyops.instruments` DIFFERENT (D-13.06-38) | PASS | D-13.06-38 |
| `test_empty_partition_does_not_fail_the_job` | PASS on 2.11.0 (EMPTY was "No difference" at INFO); FAIL on the tree before `73e92977` (EMPTY logged at WARNING, spec 13.06 §3.17) | PASS | PR 1539 `73e92977` (WARNING contract), against `e1724038` |
| `test_empty_partition_is_reported_empty_not_matched` | FALSE MATCH: `No difference for pyops.fills` at count 0 (spec repro R03) | PASS | PR 1539 `e1724038`, D-13.06-5 |
| `test_side_notes_reach_the_job_log_below_warning` | FAIL: no side note, the grep pipeline drops the side WARNING (spec repro R11); on the tree before `73e92977` the note is relayed at WARNING and the job fails | PASS | PR 1539 `e1724038` D-13.06-8, `73e92977` (INFO) |
| `test_planted_difference_fails_the_job` | PASS for `pyops.fills` (a real difference was always reported), FAIL on the non-partitioned assertions (D-13.06-38 noise) | PASS | D-13.06-38 |
| `test_planted_difference_in_the_renamed_database_fails_the_job` | FAIL on the other tables (D-13.06-38 noise); the override itself works on 2.11.0 | PASS | D-13.06-38 |
| `test_difference_in_an_ignored_column_passes_the_job` | PASS (ignored columns work on 2.11.0) | PASS | production `ignored_columns` contract |
| `test_clickhouse_side_failure_fails_the_job` | FAIL: the failed ClickHouse side parses to `(None, None)` and the table is reported `Checksum difference`, driver exit 0 (spec 13.06 §3.8 outcome table): the job still fails, but through its WARNING scan, as if the data differed, not as a failed run | PASS | PR 1539 `e1724038`, D-13.06-1/-2 (ERROR verdict, exit 1) |

### Manual recipes and counts (`test_mysql_02_manual_recipes.py`, one test of `test_mysql_03_snapshot.py`)

| Test | Pre-fix (expected) | Fixed | Justifies |
|---|---|---|---|
| `test_manual_checksum_recipe_is_equal_on_clean_data` | FAIL: `--no_wc` crashes the MySQL side (`'list' object has no attribute ...`, spec repro R04); `--exclude_columns _version is_deleted ...` excludes nothing on the ClickHouse side (R05), `--debug_output` prints no checksum line | PASS | PR 1539 `73e92977`: D-13.06-26, -17, -27 |
| `test_manual_checksum_recipe_with_the_connector_binary_encoding` | FAIL: as above, plus BIT(16) base64 (D-13.06-38) | PASS | `73e92977` D-13.06-26/-17/-27; D-13.06-38 |
| `test_manual_checksum_recipe_names_the_diverged_row` | FAIL: no per-row files / no checksum line (D-13.06-26, -27) | PASS | `73e92977` |
| `test_mysql_count_agrees_with_real_counts` | FAIL: TypeError on SQLAlchemy 2.x (D-13.06-9); on 1.4 the `--no_wc` step crashes (D-13.06-26) | PASS | D-13.06-9 (this suite), `73e92977` D-13.06-26 |
| `test_clickhouse_count_agrees_with_real_counts` | FAIL: every table counted 0 without `--include_partitions_regex` (`match(partition,'None')`, spec repro R05) | PASS | `73e92977` D-13.06-18, D-13.06-26 |
| `test_clickhouse_count_agrees_between_the_live_and_the_restored_copy` | FAIL: zero counts (D-13.06-18) | PASS | `73e92977` D-13.06-18 |

### Snapshot (`test_mysql_03_snapshot.py`)

| Test | Pre-fix (expected) | Fixed | Justifies |
|---|---|---|---|
| `test_dumper_hands_over_a_verified_snapshot_position` | FAIL: TypeError on SQLAlchemy 2.x (D-13.03-3); on 1.4 no `snapshot_position.json` is written | PASS | PR 1543 D-13.03-1; D-13.03-3 (this suite) |
| `test_loader_fills_a_fresh_database` | PASS expected (the load itself succeeds on 2.11.0) | PASS | - |
| `test_production_job_matches_the_loaded_snapshot` | FAIL: binary values loaded as MySQL Shell's base64 text with line breaks (D-13.04-3), BIT(1) as `String` `01` (D-13.04-10, observed before this suite's fix), `ledger._sign` not loaded (D-13.04-6), plus D-13.06-38 noise | PASS | PR 1538 D-13.04-3, -6; D-13.04-10 BIT(1) part (this suite) |
| `test_keyless_table_keeps_every_row_after_optimize_final` | FAIL: `ORDER BY tuple()`, one row left after `OPTIMIZE ... FINAL` (spec 08.05 measurement) | PASS | PR 1538 D-13.04-1 |
| `test_loaded_values_equal_the_streamed_values[fills]` | FAIL: BIT(16) and VARBINARY in base64 text, BIT(1) `01` (D-13.04-3, -10) | PASS | PR 1538 D-13.04-3; D-13.04-10 (this suite) |
| `test_loaded_values_equal_the_streamed_values[instruments]` | FAIL: the 210-byte BLOB carries MySQL's base64 line breaks (D-13.04-3) | PASS | PR 1538 D-13.04-3 |
| `test_loaded_values_equal_the_streamed_values[keyless_events]` | FAIL: VARBINARY base64 (D-13.04-3); collapsed rows (D-13.04-1) | PASS | PR 1538 D-13.04-1, -3 |
| `test_loader_fails_loudly_on_a_corrupt_dump_chunk` | FALSE SUCCESS: exit 0, the chunk's rows missing (no pipefail, spec repro r7/r8) | PASS | PR 1538 D-13.04-4 |
| `test_dumper_fails_loudly_when_mysql_shell_fails` | FAIL or flaky: the exit status is read with `poll()` (spec repro r3), and the dump cannot start on SQLAlchemy 2.x | PASS | PR 1543 D-13.03-12 |

### Ad hoc patching (`test_mysql_04_resync.py`)

Pre-fix flags that do not exist are left out by the tests (`Workspace.supports`), so the pre-fix tool's own
behaviour is measured, not an argparse error.

| Test | Pre-fix (expected) | Fixed | Justifies |
|---|---|---|---|
| `test_unlogged_change_is_reported_then_repaired_by_resync` | FAIL: the packaged loader has no `--binary_handling_mode` and loads base64 text verbatim, BIT(1) into `Bool` fails or differs; after the REPLACE the job still reports `Checksum difference` | PASS | PR 1538 D-13.04-3 (through `--loader-cmd`), PR 1542 (the gated REPLACE), D-13.04-10 BIT(1) |
| `test_patch_refuses_to_replace_while_the_connector_is_behind_the_dump` | FAIL: no position gate, the REPLACE runs and overwrites the planted ClickHouse row | PASS | PR 1542 D-13.08-5 |
| `test_patch_skip_load_refuses_a_scratch_table_it_did_not_load` | FAIL: `--skip-load` replaces the live table from the unmarked stale scratch copy | PASS | PR 1542 D-13.08-8 |
| `test_rewind_sql_requires_the_connector_stopped_attestation` | FAIL: the INSERT is printed, exit 0 | PASS | PR 1542 D-13.08-9 |
| `test_rewind_sql_refuses_a_forward_rewind` | FAIL: the forward INSERT is printed, exit 0 (spec repro 5 of 13.08) | PASS | PR 1542 D-13.08-6 |
| `test_rewind_sql_accepts_the_dumper_snapshot_position` | FAIL: the dumper writes no `snapshot_position.json` | PASS | PR 1543 D-13.03-1 (handoff to PR 1542 rewind-sql) |
| `test_z_patch_refuses_an_empty_restore_suffix` | FAIL: `DROP TABLE IF EXISTS pyops.temp_resync_guard` drops the live table (spec repro 6 of 13.08) | PASS | PR 1542 D-13.08-11 |

### Dedicated shapes (`test_mysql_05_justification.py`)

| Test | Pre-fix (expected) | Fixed | Justifies |
|---|---|---|---|
| `test_checksum_in_column_names_does_not_confuse_the_verdict` | FALSE MATCH / FAIL: both outputs unparseable, `No difference` without per-side results (spec repro R07) | PASS | PR 1539 `e1724038` D-13.06-2 |
| `test_function_partition_expression_reaches_the_sides` | FAIL: `syntax error near unexpected token '('` from `--partition_key to_days(ev_date)` in `sh -c` (spec repro R03) | PASS | PR 1539 `e1724038` D-13.06-10 |
| `test_dollar_table_is_compared_under_its_own_name` | FALSE MATCH / FAIL: `$rates` expands to nothing, `^temp_fx$` matches no table on either side (spec repro R03) | PASS (DIFFERENT, as the data differ: see below) | PR 1539 `e1724038` D-13.06-3 |
| `test_equal_mysql_and_clickhouse_host_strings_still_give_verdicts` | FAIL: no verdict line, exit 0 (spec repro R03) | PASS | PR 1539 `e1724038` D-13.06-4 |
| `test_packaged_driver_run_from_another_directory_reports_a_difference` | FALSE MATCH: both sides fail to start outside the tool tree, `(None, None) == (None, None)`, `No difference` for the planted difference (spec repro R02) | PASS | PR 1539 `e1724038` D-13.06-1, -14, -25 |
| `test_loader_loads_a_table_with_a_dollar_in_its_name` | XFAIL | XFAIL (strict) | open defect D-13.04-32 (registered, not fixed) |

## Fixes this suite forced (mandatory use cases blocked end to end)

| Defect | What the suite saw | Fix | Offline test |
|---|---|---|---|
| D-13.06-9, D-13.03-3 | `source ./install.sh` installs SQLAlchemy 2.x; the job exited 1 at the first table (`tuple indices must be integers or slices, not str`), and so did the dumper | rows read through `mappings()` in both drivers, both MySQL count runners, the packaged MySQL side and `get_table_partition_key`, both dumpers | `db_compare/tests/test_sqlalchemy_rows.py` |
| D-13.06-38 | with `binary.handling.mode: base64` the connector stores BIT(16) as hex (`abcd`); the MySQL side rendered `q80=`; every table with a BIT(n>1) column was DIFFERENT on clean data | BIT(n>1) rendered as lower-case hex under every `--binary_encoding` | `db_compare/tests/test_scheduled_job_findings.py::TestBitColumnRendering` |
| D-13.06-39 | the job's `set -euo pipefail` script died in `source ./install.sh` (`PYTHONPATH: unbound variable`) | `${PYTHONPATH:-}` | `db_compare/tests/test_scheduled_job_findings.py::TestInstallShUnderStrictMode` |
| D-13.04-10 (BIT(1) part) | the loaded snapshot had BIT(1) as `String` `01`/`00`, the connector `Bool`; the production job reported the snapshot DIFFERENT | ANTLR translator declares `Bool`, the MySQL Shell path loads true/false (both copies) | `db_load/tests/test_loader_bit1_bool.py` |

Findings recorded but not fixed: D-13.04-32 (the loader cannot load a table whose name MySQL Shell
percent-encodes, such as `temp_fx$rates`; strict xfail); the connector itself creates `temp_fx$rates` in
ClickHouse but writes its rows to `temp_fx_rates`, which is why the checksum of that table is DIFFERENT (a
connector finding, outside the Python tools); a table named with `temp` anywhere (for example
`positions_bitemporal`) is silently excluded by the jobs' `--exclude_tables_regex "(temp|...)"`.
