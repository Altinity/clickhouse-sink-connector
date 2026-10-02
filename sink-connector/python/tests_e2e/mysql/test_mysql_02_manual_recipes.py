"""The owning DBA's manual recipes (spec 13.06 sections 3.9, 3.10, 3.14).

A table is checksummed side by side with the two side scripts, ``--no_wc`` (the
table is named, not matched), the partition as ``--where`` and ``--debug_output``,
which writes every row's canonical string to ``out.<table>.<side>.txt``; the two
files are sorted and diffed. The count runners are run with and without
``--include_partitions_regex``.
"""
import pytest

from mysql_e2e_support import (CH_HOST, DB, MYSQL_HOST, YESTERDAY, ch_query, mysql_query, parse_checksum_lines,
                               parse_counts, planted)

PARTITION_FILTER = f"trade_date = '{YESTERDAY}'"
CH_EXCLUDE = ["_version", "is_deleted", "_is_deleted", "__is_deleted"]
MYSQL_EXCLUDE = ["_sign", "_version", "is_deleted", "_is_deleted", "__is_deleted"]


def clickhouse_recipe(ws, table, extra=()):
    return ws.py("db_compare/clickhouse_table_checksum.py", "--clickhouse_host", CH_HOST, "--clickhouse_database", DB,
                 "--tables_regex", table, "--no_wc", "--clickhouse_config_file", "clickhouse-client.xml",
                 "--where", PARTITION_FILTER, "--sign_column", "", "--exclude_columns", *CH_EXCLUDE, "--debug_output",
                 *extra)


def mysql_recipe(ws, table, extra=()):
    return ws.py("db_compare/mysql_table_checksum.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                 "--tables_regex", table, "--no_wc", "--defaults_file", ".my.cnf", "--where", PARTITION_FILTER,
                 "--exclude_columns", *MYSQL_EXCLUDE, "--debug_output", *extra)


def sorted_diff(ws, table):
    """`sort out.<t>.mysql.txt`, `sort out.<t>.ch.txt` and `diff` them, as the DBA does."""
    result = ws.bash(f"set -uo pipefail\nsort out.{table}.mysql.txt > out.{table}.mysql.sorted\n"
                     f"sort out.{table}.ch.txt > out.{table}.ch.sorted\n"
                     f"diff out.{table}.mysql.sorted out.{table}.ch.sorted\n")
    return result


def run_recipe(ws, table, extra=()):
    for side in ("mysql", "ch"):
        (ws.ws / f"out.{table}.{side}.txt").unlink(missing_ok=True)
    ch = clickhouse_recipe(ws, table, extra)
    my = mysql_recipe(ws, table, extra)
    assert ch.returncode == 0, ch
    assert my.returncode == 0, my
    (ch_lines, my_lines) = (parse_checksum_lines(ch.output), parse_checksum_lines(my.output))
    assert len(ch_lines) == 1 and len(my_lines) == 1, (ch, my)
    return ch_lines[0], my_lines[0], sorted_diff(ws, table)


def test_manual_checksum_recipe_is_equal_on_clean_data(ws):
    """The recipe exactly as the DBA runs it: equal checksum lines, empty diff."""
    (ch, my, diff) = run_recipe(ws, "positions_bt")
    assert ch == (f"{DB}.positions_bt", my[1], 5), (ch, my)
    assert my[2] == 5, my
    assert diff.returncode == 0 and diff.output.strip() == "", diff
    assert len((ws.ws / "out.positions_bt.ch.txt").read_text().splitlines()) == 5


def test_manual_checksum_recipe_with_the_connector_binary_encoding(ws):
    """The same recipe on a table with VARBINARY and BIT(16) columns, told the connector's
    binary encoding (binary.handling.mode base64): equal."""
    (ch, my, diff) = run_recipe(ws, "fills", extra=("--binary_encoding", "base64"))
    assert ch[1:] == my[1:] and my[2] == 5, (ch, my)
    assert diff.returncode == 0 and diff.output.strip() == "", diff


def test_manual_checksum_recipe_names_the_diverged_row(ws):
    """A value changed in ClickHouse: the checksum lines differ and the diff of the sorted
    per-row files shows exactly that row, on each side."""
    with planted(DB, "positions_bt", "quantity", "position_id = 2", 999.25):
        (ch, my, diff) = run_recipe(ws, "positions_bt")
    assert ch[1] != my[1] and ch[2] == my[2] == 5, (ch, my)
    assert diff.returncode == 1, diff
    removed = [line for line in diff.output.splitlines() if line.startswith("< ")]
    added = [line for line in diff.output.splitlines() if line.startswith("> ")]
    assert len(removed) == 1 and len(added) == 1, diff
    assert removed[0].startswith("< 2#") and "#250.50#" in removed[0], diff
    assert added[0].startswith("> 2#") and "#999.25#" in added[0], diff


COUNT_TABLES = ["fills", "positions_bt", "quotes"]


def real_mysql_counts(partition=None):
    clause = f" PARTITION ({partition})" if partition else ""
    return {f"{DB}.{t}": mysql_query(f"SELECT count(*) FROM `{DB}`.`{t}`{clause}")[0][0] for t in COUNT_TABLES}


def test_mysql_count_agrees_with_real_counts(ws):
    regex = "^(" + "|".join(COUNT_TABLES) + ")$"
    whole = ws.py("db_compare/mysql_table_count.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                  "--defaults_file", ".my.cnf", "--include_tables_regex", regex)
    assert whole.returncode == 0, whole
    assert parse_counts(whole.output) == real_mysql_counts(), whole
    partition = f"p{YESTERDAY:%Y%m%d}"
    one = ws.py("db_compare/mysql_table_count.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                "--defaults_file", ".my.cnf", "--include_tables_regex", regex, "--include_partitions_regex", partition)
    assert one.returncode == 0, one
    assert parse_counts(one.output) == real_mysql_counts(partition), one
    named = ws.py("db_compare/mysql_table_count.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                  "--defaults_file", ".my.cnf", "--include_tables_regex", "fills", "--no_wc")
    assert named.returncode == 0, named
    assert parse_counts(named.output) == {f"{DB}.fills": real_mysql_counts()[f"{DB}.fills"]}, named


def real_clickhouse_counts(database=DB, tables=COUNT_TABLES):
    return {f"{database}.{t}": ch_query(f"SELECT count() FROM `{database}`.`{t}` FINAL")[0][0] for t in tables}


def clickhouse_count(ws, host, database, regex, extra=()):
    return ws.py("db_compare/clickhouse_table_count.py", "--clickhouse_host", host, "--clickhouse_database", database,
                 "--clickhouse_config_file", "clickhouse-client.xml", "--tables_regex", regex, *extra)


def test_clickhouse_count_agrees_with_real_counts(ws):
    """Without --include_partitions_regex the whole table is counted (spec 13.06 D-13.06-18);
    with it, only the matching partitions."""
    regex = "^(" + "|".join(COUNT_TABLES) + ")$"
    real = real_clickhouse_counts()
    assert real == real_mysql_counts(), "replica not in sync"
    whole = clickhouse_count(ws, CH_HOST, DB, regex)
    assert whole.returncode == 0, whole
    assert parse_counts(whole.output) == real, whole
    every = clickhouse_count(ws, CH_HOST, DB, regex, ("--include_partitions_regex", "."))
    assert every.returncode == 0, every
    assert parse_counts(every.output) == real, every
    named = clickhouse_count(ws, CH_HOST, DB, "fills", ("--no_wc",))
    assert named.returncode == 0, named
    assert parse_counts(named.output) == {f"{DB}.fills": real[f"{DB}.fills"]}, named
