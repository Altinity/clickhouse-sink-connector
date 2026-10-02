"""ch-checksum / ch-pg-checksum fixes, each shown end to end (see JUSTIFICATION.md).

Every test asserts the fixed verdict of one verification defect (spec 13.07
D-13.07-n). Against the pre-fix tools each one fails, typically because the
old tool reports PASS / exit 0 where the data is not verified equal.
"""

import re

from conftest import (
    CH_DATABASE,
    READER_PASSWORD,
    READER_USER,
    VERIFIED_TABLES,
    ch_query,
    clone_ch_database,
    parse_checksum_summary,
    pg_tool_args,
    run_ch_checksum,
    run_tool,
    write_checksum_config,
)


def test_uncomputable_clickhouse_checksum_is_error_not_pass(dumped, tmp_path):
    """D-13.07-1: a ClickHouse checksum query that fails must not yield PASS."""
    db = "pye2e_pg_nochk"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    # The CH expression for a PG timestamptz is toTimeZone(c, 'UTC'), which
    # ClickHouse rejects on a String column: the CH checksum cannot be computed.
    ch_query(f"ALTER TABLE `{db}`.t_orders MODIFY COLUMN updated_at Nullable(String) "
             f"SETTINGS mutations_sync = 2")
    config = write_checksum_config(tmp_path / "checksum.yml", db, snapshot_mode=False)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = parse_checksum_summary(output)
    assert rc == 1
    assert rows["t_orders"]["status"] == "ERROR", rows["t_orders"]
    assert "ClickHouse checksum not computed" in rows["t_orders"]["detail"]
    assert result_line.startswith("RESULT: FAIL")
    assert exit_line == "Exit code: 1"


def test_count_delta_within_thresholds_is_not_reported_as_a_match(dumped, tmp_path):
    """D-13.07-2: a one-row difference (WARN) must not read as 'all tables match'."""
    db = "pye2e_pg_warn"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * REPLACE (1 AS _version, 1 AS is_deleted) "
             f"FROM `{db}`.t_orders FINAL WHERE id = 5")
    # 1 of 62 rows is 1.6 %: inside the alert thresholds once the relative one
    # is 5 % (the 0.01 % default would make it FAIL outright).
    config = write_checksum_config(tmp_path / "checksum.yml", db, snapshot_mode=False,
                                   checksum_extra={"alert_count_delta_pct": 0.05})
    rc, output = run_ch_checksum(config, "--no-checksum")
    rows, result_line, exit_line = parse_checksum_summary(output)
    assert rows["t_orders"]["status"] == "WARN", rows["t_orders"]
    assert (rows["t_orders"]["pg"], rows["t_orders"]["ch"]) == ("62", "61")
    assert rc == 1, f"WARN without allow_count_delta_warn must fail the run: {result_line}"
    assert result_line.startswith("RESULT: FAIL")
    assert "match" not in result_line
    assert "WARN=1" in output
    assert exit_line == "Exit code: 1"


def test_column_hidden_from_the_checksum_user_is_error(dumped, tmp_path):
    """D-13.07-4: a PG column the checksum user cannot read must not vanish silently."""
    # Per-table mode: both digests are built from the PG column list, so a
    # silently hidden column would cancel out and give a false PASS.
    config = write_checksum_config(tmp_path / "checksum.yml", CH_DATABASE, snapshot_mode=False,
                                   pg_user=READER_USER, pg_password=READER_PASSWORD)
    rc, output = run_ch_checksum(config)
    rows, result_line, exit_line = parse_checksum_summary(output)
    assert rc == 1
    assert rows["t_orders"]["status"] == "ERROR", rows["t_orders"]
    assert ("checksum user lacks SELECT privilege on PostgreSQL columns ['note']"
            in rows["t_orders"]["detail"])
    assert (rows["t_events_nopk"]["checksum"], rows["t_events_nopk"]["status"]) == (
        "MATCH", "PASS")
    assert exit_line == "Exit code: 1"


def test_snapshot_mode_reads_postgres_in_one_repeatable_read_transaction(dumped, tmp_path):
    """D-13.07-5: every PG checksum query of a snapshot-mode run is REPEATABLE READ.

    A regression guard, not a before/after witness: on PostgreSQL 15 the
    pre-fix sequence (psycopg2's implicit BEGIN, then an explicit
    BEGIN ... REPEATABLE READ) also ends up REPEATABLE READ, because the
    isolation option is applied while no snapshot exists (see JUSTIFICATION.md).
    """
    config = write_checksum_config(tmp_path / "checksum.yml", CH_DATABASE, snapshot_mode=True)
    rc, output = run_ch_checksum(config, probe=True)
    levels = re.findall(r"PYTOOLS_E2E_PROBE isolation=(.+)", output)
    rows, result_line, _ = parse_checksum_summary(output)
    assert rc == 0, result_line
    assert levels, "the probe saw no checksum query inside a transaction"
    assert set(levels) == {"repeatable read"}, levels


def test_pg_checksum_exits_1_when_a_table_query_fails(seeded):
    """D-13.07-23: ch-pg-checksum must not exit 0 when a table's query fails."""
    rc, output = run_tool("ch_sink_tools.db_compare.postgres_table_checksum",
                          pg_tool_args() + ["--tables_regex", "x_timetz", "--no_wc"])
    assert "Error checksumming" in output
    assert rc == 1
    assert "1 table(s) failed: ['x_timetz']" in output
