"""Scenario 2: verification of the dumped data.

Positive: ``ch-checksum`` (top_level_postgres_checksum with a YAML config)
reports PASS and exits 0, in snapshot mode and in per-table mode;
``ch-pg-checksum`` and ``ch-pg-count`` agree with each other and with both
databases.
Negatives (each planted in its own clone of the dumped database): a differing
value is MISMATCH, a dropped ClickHouse column is MISMATCH naming the column, a
ClickHouse-only table matching the include regex is EXTRA; every one exits 1.
The standalone tools exit 1 when a table fails.
"""

import re

import pytest

from conftest import (
    CH_DATABASE,
    INCLUDE_REGEX,
    PG_DATABASE,
    PG_SCHEMA,
    VERIFIED_TABLES,
    ch_query,
    clone_ch_database,
    parse_checksum_summary,
    pg_query,
    pg_tool_args,
    run_ch_checksum,
    run_tool,
    write_checksum_config,
)


class ToolDidNotReport(RuntimeError):
    """The tool did not produce the summary a verdict assertion needs."""


def _summary(output):
    rows, result_line, exit_line = parse_checksum_summary(output)
    if not rows or result_line is None or exit_line is None:
        raise ToolDidNotReport(f"no ch-checksum summary in output:\n{output[-3000:]}")
    return rows, result_line, exit_line


def _value_verdict(rows, table):
    """The checksum verdict of a table whose counts agree, else ToolDidNotReport.

    Used by the strict-xfail tests so that only the defect they name (a
    value-level MISMATCH) can satisfy them; any other failure surfaces.
    """
    row = rows.get(table)
    if row is None or row["pg"] != row["ch"] or row["checksum"] not in ("MATCH", "MISMATCH"):
        raise ToolDidNotReport(f"no value-level verdict for {table}: {row}")
    return row["checksum"], row["status"]


def _assert_pass(rows, table):
    row = rows[table]
    assert (row["checksum"], row["status"]) == ("MATCH", "PASS"), row
    assert row["pg"] == row["ch"], row


# ---------------------------------------------------------------------------
# Positive
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("snapshot_mode", [True, False], ids=["snapshot", "per_table"])
def test_ch_checksum_passes_on_dumped_data(dumped, tmp_path, snapshot_mode):
    diff_dir = tmp_path / "diff"
    config = write_checksum_config(tmp_path / "checksum.yml", CH_DATABASE,
                                   snapshot_mode=snapshot_mode,
                                   auto_diff_dir=diff_dir if snapshot_mode else None)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = _summary(output)
    assert rc == 0
    assert sorted(rows) == VERIFIED_TABLES
    for table in VERIFIED_TABLES:
        _assert_pass(rows, table)
    assert result_line == f"RESULT: PASS — all {len(VERIFIED_TABLES)} tables match"
    assert exit_line == "Exit code: 0"
    if snapshot_mode:
        # auto_diff is enabled but has nothing to locate on matching data.
        assert "AUTO_DIFF: enabled but no tables failed checksum" in output
        assert not diff_dir.exists() or not any(diff_dir.iterdir())


def _parse_lines(output, pattern):
    return {m.group("table"): m.groupdict() for m in re.finditer(pattern, output)}


def test_pg_checksum_and_pg_count_agree(dumped):
    rc_sum, out_sum = run_tool("ch_sink_tools.db_compare.postgres_table_checksum",
                               pg_tool_args() + ["--tables_regex", INCLUDE_REGEX])
    rc_cnt, out_cnt = run_tool("ch_sink_tools.db_compare.postgres_table_count",
                               pg_tool_args() + ["--include_tables_regex", INCLUDE_REGEX])
    assert rc_sum == 0
    assert rc_cnt == 0
    prefix = re.escape(f"{PG_DATABASE}.{PG_SCHEMA}.")
    checksums = _parse_lines(
        out_sum, rf"Checksum for table {prefix}(?P<table>\w+) = (?P<md5>[0-9a-f]+) "
                 rf"count (?P<count>\d+)")
    counts = _parse_lines(out_cnt, rf"Count for table {prefix}(?P<table>\w+) = (?P<count>-?\d+)")
    assert sorted(checksums) == sorted(counts) == VERIFIED_TABLES
    for table in VERIFIED_TABLES:
        pg_count = pg_query(f"SELECT count(*) AS n FROM {PG_SCHEMA}.{table}")[0]["n"]
        ch_count = ch_query(
            f"SELECT count() FROM `{CH_DATABASE}`.`{table}` FINAL WHERE is_deleted = 0")[0][0]
        assert int(checksums[table]["count"]) == int(counts[table]["count"]) == pg_count == ch_count
        assert len(checksums[table]["md5"]) == 32
    # Distinct tables give distinct digests (the digest is not a constant).
    assert len({checksums[t]["md5"] for t in VERIFIED_TABLES}) == len(VERIFIED_TABLES)


@pytest.mark.parametrize("module", [
    "ch_sink_tools.db_compare.postgres_table_checksum",
    "ch_sink_tools.db_compare.postgres_table_count",
], ids=["ch-pg-checksum", "ch-pg-count"])
def test_standalone_tools_exit_1_when_a_table_fails(seeded, module):
    regex_flag = ("--tables_regex" if module.endswith("checksum") else "--include_tables_regex")
    rc, output = run_tool(module, pg_tool_args() + [regex_flag, "t_does_not_exist", "--no_wc"])
    assert rc == 1
    assert "1 table(s) failed: ['t_does_not_exist']" in output


# ---------------------------------------------------------------------------
# Negatives
# ---------------------------------------------------------------------------

def test_planted_value_is_a_mismatch(dumped, tmp_path):
    db = "pye2e_pg_planted"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    # A newer row version, as CDC would write it, with one differing value.
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * REPLACE (amount + 1 AS amount, "
             f"1 AS _version) FROM `{db}`.t_orders FINAL WHERE id = 17")
    config = write_checksum_config(tmp_path / "checksum.yml", db)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = _summary(output)
    assert rc == 1
    assert (rows["t_orders"]["checksum"], rows["t_orders"]["status"]) == ("MISMATCH", "FAIL")
    assert rows["t_orders"]["pg"] == rows["t_orders"]["ch"]   # counts still equal
    _assert_pass(rows, "t_events_nopk")
    assert result_line.startswith("RESULT: FAIL")
    assert exit_line == "Exit code: 1"


def test_dropped_clickhouse_column_is_a_mismatch_naming_it(dumped, tmp_path):
    db = "pye2e_pg_dropcol"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    # DESTRUCTIVE: drops one column of the test's own clone (pye2e_pg_dropcol),
    # never of the dumped database; this is the planted divergence.
    ch_query(f"ALTER TABLE `{db}`.t_orders DROP COLUMN note")
    config = write_checksum_config(tmp_path / "checksum.yml", db)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = _summary(output)
    assert rc == 1
    row = rows["t_orders"]
    assert (row["checksum"], row["status"]) == ("MISMATCH", "FAIL")
    assert "columns missing in ClickHouse: ['note']" in row["detail"]
    _assert_pass(rows, "t_events_nopk")
    assert exit_line == "Exit code: 1"


def test_extra_clickhouse_table_is_reported(dumped, tmp_path):
    db = "pye2e_pg_extra"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    for name in ("t_ch_only", "zz_not_included"):
        ch_query(f"CREATE TABLE `{db}`.`{name}` (id Int32, _version UInt64 DEFAULT 0, "
                 f"is_deleted UInt8 DEFAULT 0) ENGINE = ReplacingMergeTree(_version, is_deleted) "
                 f"ORDER BY id")
        ch_query(f"INSERT INTO `{db}`.`{name}` (id) VALUES (1)")
    config = write_checksum_config(tmp_path / "checksum.yml", db)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = _summary(output)
    assert rc == 1
    assert (rows["t_ch_only"]["checksum"], rows["t_ch_only"]["status"]) == ("EXTRA", "EXTRA")
    assert "exists in ClickHouse but has no visible PostgreSQL table" in rows["t_ch_only"]["detail"]
    # Only tables matching the include regex are examined.
    assert "zz_not_included" not in rows
    for table in VERIFIED_TABLES:
        _assert_pass(rows, table)
    assert exit_line == "Exit code: 1"


# ---------------------------------------------------------------------------
# Shapes the dumper loads correctly that ch-checksum cannot verify yet
# ---------------------------------------------------------------------------

@pytest.mark.xfail(strict=True, raises=AssertionError, reason=(
    "D-13.07-30: ch-checksum renders PostgreSQL 'infinity'/'-infinity' date and "
    "timestamp values through to_char() (NULL, then '') while ch-pg-dump and the "
    "connector store the saturated type bounds, so the table is a false MISMATCH"))
def test_saturated_infinity_values_verify(dumped, tmp_path):
    config = write_checksum_config(tmp_path / "checksum.yml", CH_DATABASE)
    rc, output = run_ch_checksum(config, "--table", "x_special_dates")
    rows, _, _ = _summary(output)
    assert (_value_verdict(rows, "x_special_dates"), rc) == (("MATCH", "PASS"), 0)


@pytest.mark.xfail(strict=True, raises=AssertionError, reason=(
    "D-13.07-8: numeric(10,2) 1.50 is '1.50' in PostgreSQL text and '1.5' in "
    "ClickHouse toString(Decimal), so the table is a false MISMATCH"))
def test_numeric_with_trailing_zero_verifies(dumped, tmp_path):
    config = write_checksum_config(tmp_path / "checksum.yml", CH_DATABASE)
    rc, output = run_ch_checksum(config, "--table", "x_numeric_scale")
    rows, _, _ = _summary(output)
    assert (_value_verdict(rows, "x_numeric_scale"), rc) == (("MATCH", "PASS"), 0)
