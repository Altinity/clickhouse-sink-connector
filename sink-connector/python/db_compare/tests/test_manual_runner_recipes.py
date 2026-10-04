#!/usr/bin/env python3
"""Manual use of the MySQL verification runners (spec 13.06 D-13.06-17, -18,
-26 and -27), in both copies (legacy db_compare/ and packaged ch_sink_tools/).

The recipes an operator runs by hand to chase a difference:

    clickhouse_table_checksum.py --clickhouse_host H --clickhouse_database D --tables_regex T --no_wc
        --where "<filter>" --sign_column "" --exclude_columns _version is_deleted _is_deleted __is_deleted --debug_output
    mysql_table_checksum.py --mysql_host H --mysql_database D --tables_regex T --no_wc
        --where "<filter>" --exclude_columns _sign _version is_deleted _is_deleted __is_deleted --debug_output

plus the count runners and the driver's --no_wc and --debug_output.

Offline: connections and engine calls are stubbed. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_manual_runner_recipes.py
"""
import argparse
import hashlib
import logging
import os
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402
import db_compare.mysql_table_checksum as lm  # noqa: E402
import db_compare.clickhouse_table_checksum as lc  # noqa: E402
import db_compare.mysql_table_count as lmc  # noqa: E402
import db_compare.clickhouse_table_count as lcc  # noqa: E402
import ch_sink_tools.db_compare.top_level_table_checksum as pt  # noqa: E402
import ch_sink_tools.db_compare.mysql_table_checksum as pm  # noqa: E402
import ch_sink_tools.db_compare.clickhouse_table_checksum as pc  # noqa: E402
import ch_sink_tools.db_compare.mysql_table_count as pmc  # noqa: E402
import ch_sink_tools.db_compare.clickhouse_table_count as pcc  # noqa: E402
from db.checksum_common import DATETIME_MIN, DATETIME_MAX, checksum_from_aggregate  # noqa: E402

MD5_A = "0123456789abcdef0123456789abcdef"
RECIPE_CH_EXCLUSIONS = ["_version", "is_deleted", "_is_deleted", "__is_deleted"]
RECIPE_MYSQL_EXCLUSIONS = ["_sign", "_version", "is_deleted", "_is_deleted", "__is_deleted"]


def run_main(module, argv, *patches):
    """module.main() with ``argv``; returns (exit code, log lines)."""
    with patch.object(sys, "argv", ["prog"] + argv):
        for p in patches:
            p.start()
        try:
            with unittest.TestCase().assertLogs(level="INFO") as logs:
                try:
                    module.main()
                    code = None
                except SystemExit as exit_:
                    code = exit_.code
                logging.info("end of run")  # assertLogs needs one record
        finally:
            for p in reversed(patches):
                p.stop()
    return code, logs.output


def side_output(db_table, md5, count):
    return f"2026-10-01 10:00:00,000 - INFO - T - Checksum for table {db_table} = {md5} count {count}\n".encode()


def run_driver_config(driver, args):
    """driver.run_config() with the real table enumeration (get_tables_from_regexp
    is NOT stubbed) and matching side outputs; returns (exit code, log lines)."""
    config = {"source": {"mysql": {"host": "db1"}}, "replicas": [{"clickhouse": {"host": "ch-host"}}]}
    patches = [
        patch.object(driver, "args", args, create=True),
        patch.object(driver, "resolve_credentials_from_config", return_value=("u", "p")),
        patch.object(driver, "get_mysql_connection", return_value=MagicMock()),
        patch.object(driver, "mysql_pk_columns", return_value=["id"]),
        patch.object(driver, "get_min_max_pk_value", return_value=(1, 10)),
        patch.object(driver, "get_table_partition_key", return_value=None),
        patch.object(driver, "run_quick_safe_command", side_effect=lambda cmd: ("0", side_output("shop.orders", MD5_A, 4))),
    ]
    if driver is tl:
        patches += [patch.object(tl, "resolve_source_timezone", return_value="UTC"),
                    patch.object(tl, "mysql_columns_by_data_type", return_value=[]),
                    patch.object(tl, "mysql_column_names", return_value=[])]
    else:
        patches += [patch.object(pt, "mysql_json_columns", return_value=[])]
    for p in patches:
        p.start()
    try:
        with unittest.TestCase().assertLogs(level="INFO") as logs:
            try:
                driver.run_config(config)
                code = None
            except SystemExit as exit_:
                code = exit_.code
        return code, logs.output
    finally:
        for p in reversed(patches):
            p.stop()


def driver_namespace(**overrides):
    values = dict(
        mysql_user=None, defaults_file="~/.my.cnf", mysql_database="shop", mysql_port=3306,
        source_timezone="UTC", tables_regex="orders", threads=1, binary_encoding="hex", where=None,
        debug_output=False, lock_tables_on_source=False, sleep_after_lock=0, lock_wait_timeout=30,
        fail_on_lock_timeout=False, no_wc=True, include_partitions_regex=None,
        exclude_tables_regex=None, non_partitioned_tables_only=False,
        include_floating_point_columns=False, include_json_columns=False, partition_date=None,
        threads_per_table=1, fail_on_empty=False,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


class TestNoWc(unittest.TestCase):
    """D-13.06-26: --no_wc names the table directly instead of crashing with
    'list' object has no attribute 'fetchall'."""

    def test_legacy_driver(self):
        code, logs = run_driver_config(tl, driver_namespace())
        self.assertEqual(code, 0, logs)
        self.assertTrue(any("No difference for shop.orders" in line for line in logs), logs)

    def test_packaged_driver(self):
        code, logs = run_driver_config(pt, driver_namespace())
        self.assertEqual(code, 0, logs)
        self.assertTrue(any("No difference for shop.orders" in line for line in logs), logs)

    def check_side(self, module, argv, worker):
        with patch.object(module, worker) as calculate:
            code, logs = run_main(module, argv,
                                  patch.object(module, "resolve_credentials_from_config", return_value=("u", "p")),
                                  patch.object(module, "get_mysql_connection", return_value=MagicMock()))
        self.assertEqual(code, 0, logs)
        self.assertEqual([call.args[0] for call in calculate.call_args_list], ["orders"])

    def test_legacy_mysql_side(self):
        self.check_side(lm, ["--mysql_host", "h", "--mysql_database", "shop", "--tables_regex", "orders", "--no_wc"],
                        "calculate_checksum")

    def test_packaged_mysql_side(self):
        self.check_side(pm, ["--mysql_host", "h", "--mysql_database", "shop", "--tables_regex", "orders", "--no_wc"],
                        "calculate_checksum")

    def test_legacy_mysql_count(self):
        self.check_side(lmc, ["--mysql_host", "h", "--mysql_database", "shop", "--include_tables_regex", "orders",
                              "--no_wc"], "calculate_table_count")

    def test_packaged_mysql_count(self):
        self.check_side(pmc, ["--mysql_host", "h", "--mysql_database", "shop", "--include_tables_regex", "orders",
                              "--no_wc"], "calculate_table_count")

    def check_clickhouse_count(self, module):
        with patch.object(module, "calculate_table_count") as calculate:
            code, logs = run_main(module, ["--clickhouse_host", "h", "--clickhouse_database", "shop",
                                           "--tables_regex", "orders", "--no_wc"],
                                  patch.object(module, "resolve_credentials_from_config", return_value=("u", "p")),
                                  patch.object(module, "get_connection", return_value=MagicMock()))
        self.assertEqual(code, 0, logs)
        self.assertEqual([call.args[:2] for call in calculate.call_args_list], [("orders", None)])

    def test_legacy_clickhouse_count(self):
        self.check_clickhouse_count(lcc)

    def test_packaged_clickhouse_count(self):
        self.check_clickhouse_count(pcc)


def clickhouse_select(module, exclude_tokens):
    """The ClickHouse side's row expression for a connector table, with
    --exclude_columns given as ``exclude_tokens`` (argparse nargs words)."""
    columns = [("id", "Int32", 0, None, 0, 1), ("name", "String", 0, None, 0, 0),
               ("_version", "UInt64", 0, None, 0, 0), ("is_deleted", "UInt8", 0, None, 0, 0)]
    module.args = argparse.Namespace(
        clickhouse_database="shop", exclude_columns=exclude_tokens, hex_columns=[], binary_encoding="hex",
        include_floating_point_columns=False, include_json_columns=False, json_columns="", timestamp_columns="",
        source_timezone="UTC", min_datetime_value=DATETIME_MIN, max_datetime_value=DATETIME_MAX)

    def execute_sql(conn, sql):
        if "is_in_primary_key" in sql:
            return ([("id",)], 1)
        width = 6 if "is_in_partition_key" in sql else 4
        return ([column[:width] for column in columns], len(columns))
    with patch.object(module, "execute_sql", side_effect=execute_sql):
        return module.get_table_checksum_query(MagicMock(), "orders")[1]


class TestExcludeColumnsForms(unittest.TestCase):
    """D-13.06-17: --exclude_columns 'a b c' and 'a,b,c' exclude the same columns on both sides."""

    def test_legacy_clickhouse_side_space_separated_recipe(self):
        parsed = lc.build_argument_parser().parse_args(
            ["--clickhouse_host", "h", "--clickhouse_database", "shop", "--tables_regex", "orders",
             "--exclude_columns"] + RECIPE_CH_EXCLUSIONS)
        spaced = clickhouse_select(lc, parsed.exclude_columns)
        self.assertEqual(spaced, clickhouse_select(lc, [",".join(RECIPE_CH_EXCLUSIONS)]))
        self.assertEqual(spaced, clickhouse_select(lc, [", ".join(RECIPE_CH_EXCLUSIONS)]))
        self.assertNotIn("_version", spaced)
        self.assertNotIn("is_deleted", spaced)

    def test_packaged_clickhouse_side_space_separated_recipe(self):
        spaced = clickhouse_select(pc, list(RECIPE_CH_EXCLUSIONS))
        self.assertEqual(spaced, clickhouse_select(pc, [",".join(RECIPE_CH_EXCLUSIONS)]))
        self.assertEqual(spaced, clickhouse_select(pc, [", ".join(RECIPE_CH_EXCLUSIONS)]))
        self.assertNotIn("_version", spaced)
        self.assertNotIn("is_deleted", spaced)

    def mysql_exclusions(self, module, tokens):
        module.args = argparse.Namespace(mysql_host="h", mysql_port=3306, mysql_database="shop")
        with patch.object(module, "get_mysql_connection", return_value=MagicMock()), \
                patch.object(module, "calculate_sql_checksum") as calculate:
            module.calculate_checksum_single_thread("orders", "u", "p", {}, None, None, tokens, False, False)
        return calculate.call_args.args[3]

    def check_mysql_side(self, module):
        expected = list(RECIPE_MYSQL_EXCLUSIONS)
        for tokens in (list(RECIPE_MYSQL_EXCLUSIONS), [",".join(RECIPE_MYSQL_EXCLUSIONS)],
                       [", ".join(RECIPE_MYSQL_EXCLUSIONS)], ["_sign,_version", "is_deleted", "_is_deleted, __is_deleted"]):
            self.assertEqual(self.mysql_exclusions(module, tokens), expected, tokens)

    def test_legacy_mysql_side(self):
        self.check_mysql_side(lm)

    def test_packaged_mysql_side(self):
        self.check_mysql_side(pm)


class TestClickHouseCount(unittest.TestCase):
    """D-13.06-18: the ClickHouse count without --include_partitions_regex counts the table."""

    def count(self, module, partition_key, **overrides):
        values = dict(clickhouse_database="shop", ignore_tables_regex=None, include_partitions_regex=None, where=None)
        values.update(overrides)
        module.args = argparse.Namespace(**values)
        executed = []

        def execute_sql(conn, sql):
            executed.append(sql)
            if "from system.parts" in sql:
                # what the server returns for match(partition,'None') or for 'p.*'
                return ([("202609",), ("202610",)], 2) if overrides.get("include_partitions_regex") else ([], 0)
            if "from system.tables" in sql:
                return ([("toYYYYMM(dt)",)], 1)
            if "'202609'" in sql:
                return ([(10,)], 1)
            if "'202610'" in sql:
                return ([(20,)], 1)
            return ([(42,)], 1)
        with patch.object(module, "get_connection", return_value=MagicMock()), \
                patch.object(module, "execute_sql", side_effect=execute_sql), \
                unittest.TestCase().assertLogs(level="INFO") as logs:
            module.calculate_table_count("orders", partition_key, "u", "p")
        return logs.output, executed

    def check(self, module):
        logs, executed = self.count(module, "toYYYYMM(dt)")
        self.assertIn("INFO:root:Count for table shop.orders = 42", logs)
        self.assertFalse(any("'None'" in sql for sql in executed), executed)
        logs, executed = self.count(module, "toYYYYMM(dt)", include_partitions_regex="p.*", where="x > 0")
        self.assertIn("INFO:root:Count for table shop.orders = 30", logs)
        self.assertTrue(any("toYYYYMM(dt) = '202609'" in sql and "and x > 0" in sql for sql in executed), executed)
        # --no_wc passes no partition key; it is read when a partition regex needs it
        logs, executed = self.count(module, None, include_partitions_regex="p.*")
        self.assertIn("INFO:root:Count for table shop.orders = 30", logs)

    def test_legacy(self):
        self.check(lcc)

    def test_packaged(self):
        self.check(pcc)

    def test_no_prod_versus_dr_flag_exists(self):
        # The count runners compare nothing: run them once per host and compare
        # the "Count for table" lines (spec 13.06 section 3.14). There is no
        # --dr_host option.
        for module in (lcc, pcc):
            with self.assertRaises(SystemExit) as raised, patch.object(sys, "stderr"):
                with patch.object(sys, "argv", ["prog", "--clickhouse_host", "h", "--clickhouse_database", "d",
                                                "--tables_regex", "t", "--dr_host", "x"]):
                    module.main()
            self.assertEqual(raised.exception.code, 2)


class InTemporaryDirectory(unittest.TestCase):
    """--debug_output writes out.<table>.<side>.txt in the cwd."""

    def setUp(self):
        self.cwd = os.getcwd()
        self.tmp = tempfile.TemporaryDirectory()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def read(self, name):
        with open(name) as debug_file:
            return debug_file.read()


ROWS = ["1#bob", "2#alice"]


def aggregate(rows):
    sums = [0, 0, 0, 0]
    for row in rows:
        digest = hashlib.md5(row.encode()).hexdigest()
        for word in range(4):
            sums[word] += int(digest[8 * word:8 * word + 8], 16)
    return (len(rows),) + tuple(sums)


class FakeRows:
    def __init__(self, rows, returns_rows=True):
        (self.rows, self.returns_rows) = (rows, returns_rows)

    def __iter__(self):
        return iter(self.rows)

    def mappings(self):
        return [{"column_name": "id", "data_type": "int", "is_nullable": "NO", "collation": None}]


class TestDebugOutput(InTemporaryDirectory):
    """D-13.06-27: --debug_output writes the per-row strings AND prints the
    checksum line, so the driver's --debug_output no longer fails every table."""

    def mysql_engine(self, sql):
        lowered = sql.strip().lower()
        if lowered.startswith("set "):
            return (FakeRows([], returns_rows=False), -1)
        if 'count(*) as "cnt"' in lowered:
            return (FakeRows([aggregate(ROWS) + (0,)]), -1)
        if "as `hash`" in lowered:
            return (FakeRows([(row,) for row in ROWS]), -1)
        raise AssertionError("unexpected MySQL statement: " + sql)

    def test_legacy_mysql_side(self):
        lm.args = argparse.Namespace(
            mysql_host="h", mysql_port=3306, mysql_database="shop", where="id > 0", ignore_tables_regex=None,
            threads_per_table=1, chunk_size=10000, debug_output=True, debug_limit=None, binary_encoding="hex",
            min_date_value="1900-01-01", max_date_value="2299-12-31", min_datetime_value=DATETIME_MIN,
            max_datetime_value=DATETIME_MAX, source_timezone="UTC")
        with patch.object(lm, "get_mysql_connection", return_value=MagicMock()), \
                patch.object(lm, "mysql_pk_columns", return_value=[]), \
                patch.object(lm, "get_table_checksum_query", return_value=("q", "`id`", "", "", "0")), \
                patch.object(lm, "execute_mysql", side_effect=lambda conn, sql: self.mysql_engine(sql)), \
                self.assertLogs(level="INFO") as logs:
            lm.calculate_checksum("orders", "u", "p", RECIPE_MYSQL_EXCLUSIONS, False, False)
        self.assertIn(f"INFO:root:Checksum for table shop.orders = {checksum_from_aggregate(*aggregate(ROWS))} count 2",
                      logs.output)
        self.assertEqual(self.read("out.orders.mysql.txt"), "1#bob\n2#alice\n")

    def test_packaged_mysql_side(self):
        pm.args = argparse.Namespace(
            mysql_host="h", mysql_port=3306, mysql_database="shop", where="id > 0", ignore_tables_regex=None,
            threads_per_table=1, chunk_size=10000, debug_output=True, debug_limit=None, binary_encoding="base64")
        with patch.object(pm, "get_mysql_connection", return_value=MagicMock()), \
                patch.object(pm, "mysql_pk_columns", return_value=[]), \
                patch.object(pm, "get_table_checksum_query", return_value=("q", "`id`", "", "")), \
                patch.object(pm, "execute_mysql", side_effect=lambda conn, sql: self.mysql_engine(sql)), \
                self.assertLogs(level="INFO") as logs:
            pm.calculate_checksum("orders", "u", "p", RECIPE_MYSQL_EXCLUSIONS, False, False)
        self.assertTrue(any("Checksum for table shop.orders = " in line and line.endswith(" count 2")
                            for line in logs.output), logs.output)
        self.assertEqual(self.read("out.orders.mysql.txt"), "1#bob\n2#alice\n")

    def clickhouse_engine(self, sql):
        if 'count(*) as "cnt"' in sql:
            return ([aggregate(ROWS) + (0,)], 1)
        if 'as "hash"' in sql:
            return ([(ROWS[0].encode(),), (ROWS[1],)], 2)
        if "engine_full" in sql:
            return ([("ReplacingMergeTree(_version, is_deleted) ORDER BY id",)], 1)
        raise AssertionError("unexpected ClickHouse statement: " + sql)

    def test_legacy_clickhouse_side(self):
        lc.args = argparse.Namespace(
            clickhouse_database="shop", ignore_tables_regex=None, sign_column="", debug_output=True,
            debug_limit=None, max_memory_usage=None, min_datetime_value=DATETIME_MIN, max_datetime_value=DATETIME_MAX)
        with patch.object(lc, "get_connection", return_value=MagicMock()), \
                patch.object(lc, "get_table_checksum_query", return_value=("q", "toString(\"id\")", "", "", "0", False)), \
                patch.object(lc, "execute_sql", side_effect=lambda conn, sql: self.clickhouse_engine(sql)), \
                self.assertLogs(level="INFO") as logs:
            lc.calculate_checksum("orders", "u", "p", " 1=1 and id > 0", None)
        self.assertIn(f"INFO:root:Checksum for table shop.orders = {checksum_from_aggregate(*aggregate(ROWS))} count 2",
                      logs.output)
        self.assertEqual(self.read("out.orders.ch.txt"), "1#bob\n2#alice\n")

    def test_packaged_clickhouse_side(self):
        pc.args = argparse.Namespace(
            clickhouse_database="shop", ignore_tables_regex=None, sign_column="", debug_output=True,
            debug_limit=None, max_memory_usage=None)
        with patch.object(pc, "get_connection", return_value=MagicMock()), \
                patch.object(pc, "get_table_checksum_query", return_value=("q", "toString(\"id\")", "", "")), \
                patch.object(pc, "execute_sql", side_effect=lambda conn, sql: self.clickhouse_engine(sql)), \
                self.assertLogs(level="INFO") as logs:
            pc.calculate_checksum("orders", "u", "p", " 1=1 and id > 0", None)
        self.assertTrue(any("Checksum for table shop.orders = " in line and line.endswith(" count 2")
                            for line in logs.output), logs.output)
        self.assertEqual(self.read("out.orders.ch.txt"), "1#bob\n2#alice\n")

    def test_driver_forwards_debug_output_to_both_sides(self):
        tl.args = driver_namespace(debug_output=True, no_wc=False)
        mysql_cmd = tl.get_mysql_checksum_command("db1", "shop", "orders", "id", 10, where=None, debug_output=True)
        ch_cmd = tl.get_clickhouse_checksum_command("ch-host", "shop", "orders", "id", 10, debug_output=True)
        self.assertIn("--debug_output", mysql_cmd)
        self.assertIn("--debug_output", ch_cmd)


if __name__ == "__main__":
    unittest.main()
