#!/usr/bin/env python3
"""The packaged MySQL verification runners (``ch-mysql-checksum`` and its side
modules in ch_sink_tools.db_compare): verdicts, exit codes, which side scripts
run, datetime bounds and JSON coverage (spec 13.06 FM-13.06-1 to -8, -20, -21).

Offline: database helpers are patched and side processes are stubbed, except
one ``--help`` launch of the real packaged side module from a foreign cwd.
Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_packaged_checksum_verdicts.py
"""
import argparse
import logging
import os
import re
import sys
import tempfile
import unittest
from datetime import datetime
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import ch_sink_tools.db_compare.top_level_table_checksum as pt  # noqa: E402
import ch_sink_tools.db_compare.mysql_table_checksum as pm  # noqa: E402
import ch_sink_tools.db_compare.clickhouse_table_checksum as pc  # noqa: E402

MYSQL_HOST = "db1"
CH_HOST = "ch-host"
MD5_A = "0123456789abcdef0123456789abcdef"
MD5_B = "fedcba9876543210fedcba9876543210"
MD5_EMPTY = "5f1de31ae1fd2a3d9d0cd6ba4cbd6a45"
CONFIG = {"source": {"mysql": {"host": MYSQL_HOST}}, "replicas": [{"clickhouse": {"host": CH_HOST}}]}


def side_line(level, message):
    return f"2026-10-01 10:00:00,000 - {level} - ThreadPoolExecutor-0_0 - {message}"


def side_output(db_table, md5, count, extra=()):
    lines = list(extra) + [side_line("INFO", f"Checksum for table {db_table} = {md5} count {count}")]
    return ("\n".join(lines) + "\n").encode("utf-8")


def driver_args(**overrides):
    values = dict(
        mysql_user=None, defaults_file="~/.my.cnf", mysql_database="shop", mysql_port=3306,
        tables_regex=".", threads=1, where=None, debug_output=False, lock_tables_on_source=False,
        sleep_after_lock=0, no_wc=False, include_partitions_regex=None, exclude_tables_regex=None,
        non_partitioned_tables_only=False, partition_date=None, threads_per_table=1, fail_on_empty=False,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def run_driver(side_outputs, tables=("orders",), config=CONFIG, json_columns=(), compute=None, **arg_overrides):
    """Run the packaged run_config() with the real command builders and verdict
    logic; ``side_outputs(cmd)`` gives each side's (rc, stdout)."""
    table_rows = MagicMock()
    table_rows.fetchall.return_value = [{"table_name": t} for t in tables]
    patches = [
        patch.object(pt, "args", driver_args(**arg_overrides), create=True),
        patch.object(pt, "resolve_credentials_from_config", return_value=("u", "p")),
        patch.object(pt, "get_mysql_connection", return_value=MagicMock()),
        patch.object(pt, "get_tables_from_regexp", return_value=table_rows),
        patch.object(pt, "mysql_pk_columns", return_value=["id"]),
        patch.object(pt, "get_min_max_pk_value", return_value=(1, 10)),
        patch.object(pt, "get_table_partition_key", return_value=None),
        patch.object(pt, "mysql_json_columns", return_value=list(json_columns)),
        patch.object(pt, "run_quick_safe_command", side_effect=side_outputs),
    ]
    if compute is not None:
        patches.append(patch.object(pt, "compute_checksum", side_effect=compute))
    for p in patches:
        p.start()
    try:
        with unittest.TestCase().assertLogs(level="INFO") as logs:
            try:
                pt.run_config(config)
                code = None
            except SystemExit as exit_:
                code = exit_.code
        return code, logs.output
    finally:
        for p in reversed(patches):
            p.stop()


def is_mysql_side(cmd):
    return pt.MYSQL_SIDE_MODULE in cmd


def value_after(cmd, flag):
    return cmd[cmd.index(flag) + 1]


class TestPackagedSidesAndFailures(unittest.TestCase):
    """FM-13.06-1: the packaged driver runs the packaged sides, and a failed
    side fails the table and the run."""

    def setUp(self):
        pt.args = driver_args()

    def test_commands_run_the_packaged_side_modules_with_this_interpreter(self):
        mysql_cmd = pt.get_mysql_checksum_command(MYSQL_HOST, "shop", "orders", "id", 10, where=None)
        ch_cmd = pt.get_clickhouse_checksum_command(CH_HOST, "shop", "orders", "id", 10)
        self.assertEqual(mysql_cmd[:3], [sys.executable, "-m", "ch_sink_tools.db_compare.mysql_table_checksum"])
        self.assertEqual(ch_cmd[:3], [sys.executable, "-m", "ch_sink_tools.db_compare.clickhouse_table_checksum"])
        for cmd in (mysql_cmd, ch_cmd):
            self.assertFalse(any("db_compare/" in word for word in cmd), cmd)
            self.assertFalse(any("pipefail" in word or "grep" in word for word in cmd), cmd)

    def test_side_module_starts_from_a_foreign_cwd_without_pythonpath(self):
        environment = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        with tempfile.TemporaryDirectory() as foreign, patch.dict(os.environ, environment, clear=True):
            cwd = os.getcwd()
            os.chdir(foreign)
            try:
                rc, out = pt.run_quick_safe_command(pt.side_command(pt.CLICKHOUSE_SIDE_MODULE) + ["--help"])
            finally:
                os.chdir(cwd)
        self.assertEqual(rc, "0", out)
        self.assertIn(b"--clickhouse_host", out)

    def test_both_sides_failing_exits_non_zero_and_never_reports_equal(self):
        code, logs = run_driver(lambda cmd: ("1", b"/usr/bin/python: No module named ch_sink_tools\n"))
        self.assertEqual(code, 1)
        self.assertFalse(any("No difference" in line for line in logs), logs)
        self.assertTrue(any("Checksum ERROR for shop.orders" in line for line in logs), logs)

    def test_garbage_output_with_exit_zero_is_an_error(self):
        code, logs = run_driver(lambda cmd: ("0", b"python: can't open file 'db_compare/x.py'\n"))
        self.assertEqual(code, 1)
        self.assertFalse(any("No difference" in line for line in logs), logs)

    def test_one_failed_side_is_an_error_not_a_difference(self):
        def outputs(cmd):
            return ("0", side_output("shop.orders", MD5_A, 2)) if is_mysql_side(cmd) else ("1", b"boom\n")
        code, logs = run_driver(outputs, tables=("orders", "customers"))
        self.assertEqual(code, 1)
        self.assertTrue(any("2 table(s) have NO verdict" in line for line in logs), logs)

    def test_results_are_in_submission_order(self):
        def outputs(cmd):
            return "0", side_output(f"{value_after(cmd, '--mysql_database') if is_mysql_side(cmd) else 'ch_shop'}.orders", MD5_A, 2)
        with patch.object(pt, "run_quick_safe_command", side_effect=outputs):
            results = pt.compute_checksum("shop", {CH_HOST: "shop:ch_shop"}, {}, "orders", "u", "p", MYSQL_HOST,
                                          [CH_HOST], "id", 10, None)
        self.assertEqual([r[0] for r in results], [MYSQL_HOST, CH_HOST])
        self.assertEqual(results[1][1], "ch_shop.orders")

    def test_unexpected_exception_is_logged_and_exits_one(self):
        def compute(*a, **k):
            raise RuntimeError("unexpected")
        code, logs = run_driver(lambda cmd: ("0", b""), compute=compute)
        self.assertEqual(code, 1)
        self.assertTrue(any("Exception in main thread : unexpected" in line for line in logs), logs)


class TestPackagedParsingQuotingAndVerdicts(unittest.TestCase):
    """FM-13.06-2 to -5 and -8 on the packaged driver."""

    def setUp(self):
        pt.args = driver_args(partition_date=datetime(2026, 9, 28))

    def test_checksum_named_column_does_not_hide_a_difference(self):
        noise = (side_line("INFO", "Excluding column checksum_note"),
                 side_line("WARNING", "Not compared in table shop.orders: floating point column `checksum_ratio`"))
        def outputs(cmd):
            return "0", side_output("shop.orders", MD5_A if is_mysql_side(cmd) else MD5_B, 2, noise)
        code, logs = run_driver(outputs)
        self.assertTrue(any("Checksum difference" in line for line in logs), logs)
        self.assertFalse(any("No difference" in line for line in logs), logs)
        self.assertTrue(any(line.startswith("INFO:") and "side note" in line and "checksum_ratio" in line for line in logs),
                        "side WARNINGs must reach the driver log as side notes")
        self.assertEqual([line for line in logs if "WARNING" in line],
                         [f"WARNING:root:Checksum difference : ('{CH_HOST}', 'shop.orders', '{MD5_B}', 2) to "
                          f"('{MYSQL_HOST}', 'shop.orders', '{MD5_A}', 2)"])

    def test_dollar_table_name_and_where_are_passed_verbatim(self):
        where = "`status` = 'A' and note <> '$HOME'"
        for cmd in (pt.get_mysql_checksum_command(MYSQL_HOST, "shop", "orders$archive", "id", 10, where=where),
                    pt.get_clickhouse_checksum_command(CH_HOST, "shop", "orders$archive", "id", 10, where=where,
                                                       partition_key="to_days(`dt`)")):
            regex = value_after(cmd, "--tables_regex")
            self.assertTrue(re.fullmatch(regex, "orders$archive"), regex)
            self.assertFalse(re.fullmatch(regex, "orders"), regex)
            self.assertIn(f" and {where} ", value_after(cmd, "--where"))
        self.assertEqual(value_after(cmd, "--partition_key"), "to_days(dt)")

    def test_partition_literal_is_what_the_packaged_fstr_expects(self):
        cmd = pt.get_clickhouse_checksum_command(CH_HOST, "shop", "orders", "id", 10, partition_key="dt")
        self.assertEqual(pc.fstr(value_after(cmd, "--where"), "dt"), " 1=1  and dt=toDate('2026-09-28') ")
        cmd = pt.get_mysql_checksum_command(MYSQL_HOST, "shop", "orders", "id", 10, where=None)
        self.assertEqual(pm.fstr(value_after(cmd, "--where"), "dt"), " 1=1  and dt=20260928")

    def test_equal_host_strings_get_a_verdict(self):
        config = {"source": {"mysql": {"host": "localhost"}}, "replicas": [{"clickhouse": {"host": "localhost"}}]}
        def outputs(cmd):
            return "0", side_output("shop.orders", MD5_A if is_mysql_side(cmd) else MD5_B, 2)
        code, logs = run_driver(outputs, config=config)
        self.assertTrue(any("Checksum difference" in line for line in logs), logs)

    def test_empty_on_both_sides_warns_and_fails_only_when_asked(self):
        outputs = lambda cmd: ("0", side_output("shop.orders", MD5_EMPTY, 0))  # noqa: E731
        code, logs = run_driver(outputs)
        self.assertEqual(code, 0)
        self.assertFalse(any("No difference" in line for line in logs), logs)
        self.assertTrue(any(line.startswith("INFO:") and "EMPTY on both sides for shop.orders" in line for line in logs), logs)
        self.assertTrue(any(line.startswith("INFO:") and "EMPTY on both sides: 1 table(s)" in line for line in logs), logs)
        self.assertFalse(any("WARNING" in line for line in logs), logs)
        code, _ = run_driver(outputs, fail_on_empty=True)
        self.assertEqual(code, 1)

    def test_side_error_line_fails_the_table_even_with_exit_zero(self):
        def outputs(cmd):
            extra = (side_line("ERROR", "Exception in main thread : boom"),) if not is_mysql_side(cmd) else ()
            return "0", side_output("shop.orders", MD5_A, 2, extra)
        code, logs = run_driver(outputs)
        self.assertEqual(code, 1)
        self.assertTrue(any("Checksum ERROR for shop.orders" in line for line in logs), logs)
        self.assertFalse(any("No difference" in line for line in logs), logs)


class TestPackagedDatetimeBounds(unittest.TestCase):
    """FM-13.06-6: both sides get the full ClickHouse DateTime64 range."""

    def setUp(self):
        pt.args = driver_args()

    def test_driver_passes_identical_full_range_bounds_to_both_sides(self):
        mysql_cmd = pt.get_mysql_checksum_command(MYSQL_HOST, "shop", "orders", "id", 10, where=None)
        ch_cmd = pt.get_clickhouse_checksum_command(CH_HOST, "shop", "orders", "id", 10)
        for flag, expected in (("--min_datetime_value", "1900-01-01 00:00:00"),
                               ("--max_datetime_value", "2299-12-31 23:59:59")):
            self.assertEqual(value_after(mysql_cmd, flag), expected)
            self.assertEqual(value_after(ch_cmd, flag), expected)
        self.assertFalse(any("1969" in word for word in mysql_cmd + ch_cmd))

    def test_pre_1969_values_render_as_themselves_on_both_sides(self):
        mysql_select = mysql_select_for([("dt", "datetime", "NO", None)], args_overrides={
            "min_datetime_value": pt.DATETIME_RANGE_MIN, "max_datetime_value": pt.DATETIME_RANGE_MAX})
        ch_select = clickhouse_select_for([("dt", "DateTime64(0, 'UTC')", 0, None)], args_overrides={
            "min_datetime_value": pt.DATETIME_RANGE_MIN, "max_datetime_value": pt.DATETIME_RANGE_MAX})
        self.assertIn("<= '1900-01-01 00:00:00'", mysql_select)
        self.assertIn("< '1900-01-01 00:00:00'", ch_select)

    def test_standalone_side_defaults_agree(self):
        mysql_defaults = parsed_defaults(pm, ["--mysql_host", "h", "--mysql_database", "d", "--tables_regex", "t"])
        ch_defaults = parsed_defaults(pc, ["--clickhouse_host", "h", "--clickhouse_database", "d", "--tables_regex", "t"])
        self.assertEqual(mysql_defaults.min_datetime_value, ch_defaults.min_datetime_value)
        self.assertEqual(mysql_defaults.max_datetime_value, ch_defaults.max_datetime_value)


class TestPackagedJsonCoverage(unittest.TestCase):
    """FM-13.06-7: JSON is excluded on both sides by default, with a WARNING."""

    def test_standalone_defaults_exclude_json(self):
        self.assertFalse(parsed_defaults(pm, ["--mysql_host", "h", "--mysql_database", "d", "--tables_regex", "t"]).include_json_columns)
        self.assertFalse(parsed_defaults(pc, ["--clickhouse_host", "h", "--clickhouse_database", "d", "--tables_regex", "t"]).include_json_columns)

    def test_mysql_side_excludes_json_with_a_warning(self):
        with self.assertLogs(level="WARNING") as logs:
            select = mysql_select_for([("id", "int", "NO", None), ("j", "json", "YES", None), ("name", "varchar(10)", "NO", "utf8mb4_bin")])
        self.assertNotIn("json_pretty", select)
        self.assertNotIn("`j`", select)
        self.assertTrue(any("JSON column `j`" in line for line in logs.output), logs.output)

    def test_clickhouse_side_excludes_named_json_string_columns_with_a_warning(self):
        with self.assertLogs(level="WARNING") as logs:
            select = clickhouse_select_for([("id", "Int32", 0, None), ("j", "Nullable(String)", 1, None),
                                            ("name", "String", 0, None)], args_overrides={"json_columns": "j"})
        self.assertEqual(select, "toString(\"id\")||'#'||toString(\"name\")")
        self.assertTrue(any('JSON column "j"' in line for line in logs.output), logs.output)

    def test_driver_derives_json_columns_for_the_clickhouse_side(self):
        captured = []
        def outputs(cmd):
            captured.append(cmd)
            return "0", side_output("shop.orders", MD5_A, 2)
        run_driver(outputs, json_columns=["doc", "meta"])
        ch_cmd = next(cmd for cmd in captured if not is_mysql_side(cmd))
        self.assertEqual(value_after(ch_cmd, "--json_columns"), "doc,meta")
        mysql_cmd = next(cmd for cmd in captured if is_mysql_side(cmd))
        self.assertNotIn("--include_json_columns", mysql_cmd)

    def test_skipped_last_column_leaves_no_dangling_separator(self):
        select = clickhouse_select_for([("id", "Int32", 0, None), ("name", "String", 0, None), ("f", "Float64", 0, None)])
        self.assertEqual(select, "toString(\"id\")||'#'||toString(\"name\")")


class TestPackagedClickHouseSideIsReadOnly(unittest.TestCase):
    """D-13.06-25: no CREATE FUNCTION and no dead count pre-check."""

    def test_main_runs_no_ddl(self):
        execute = MagicMock(return_value=([], 0))
        argv = ["ch-ch-checksum", "--clickhouse_host", "h", "--clickhouse_database", "d", "--tables_regex", "t",
                "--clickhouse_user", "u", "--clickhouse_password", "p"]
        root = logging.getLogger()
        handlers = list(root.handlers)
        with patch.object(sys, "argv", argv), patch.object(pc, "get_connection", return_value=MagicMock()), \
                patch.object(pc, "get_tables_from_regex", return_value=[]), patch.object(pc, "execute_sql", execute):
            with self.assertRaises(SystemExit) as raised:
                pc.main()
        for handler in root.handlers[len(handlers):]:
            root.removeHandler(handler)
        self.assertEqual(raised.exception.code, 0)
        self.assertFalse(any("CREATE FUNCTION" in str(call) for call in execute.call_args_list), execute.call_args_list)

    def test_calculate_checksum_runs_no_count_pre_check(self):
        pc.args = argparse.Namespace(ignore_tables_regex=None, clickhouse_database="d")
        execute = MagicMock(return_value=([(0,)], 1))
        with patch.object(pc, "get_connection", return_value=MagicMock()), patch.object(pc, "execute_sql", execute), \
                patch.object(pc, "get_table_checksum_query", return_value=("q", "s", "o", "")), \
                patch.object(pc, "select_table_statements", return_value=["agg"]), \
                patch.object(pc, "compute_checksum") as compute:
            pc.calculate_checksum("t", "u", "p", " 1=1 ", None)
        execute.assert_not_called()
        compute.assert_called_once()


def parsed_defaults(module, argv):
    """The module's parsed CLI defaults (main() parses into module.args)."""
    with patch.object(sys, "argv", ["prog"] + argv), patch.object(module, "logging") as fake_logging:
        fake_logging.getLogger.side_effect = Exception("stop after parsing")
        try:
            module.main()
        except Exception:
            pass
    return module.args


def mysql_select_for(columns, args_overrides=None):
    """The packaged MySQL side's row expression for (name, column_type, is_nullable, collation) rows."""
    values = dict(mysql_database="shop", min_date_value="1900-01-01", max_date_value="2299-12-31",
                  min_datetime_value="1900-01-01 00:00:00", max_datetime_value="2299-12-31 23:59:59")
    values.update(args_overrides or {})
    pm.args = argparse.Namespace(**values)
    rows = [{"column_name": n, "data_type": t, "is_nullable": nl, "collation": c} for (n, t, nl, c) in columns]
    with patch.object(pm, "execute_mysql", return_value=(rows, -1)):
        (query, select, order_by, external) = pm.get_table_checksum_query("orders", MagicMock(), "hex", None, [], False, False)
    return select


def clickhouse_select_for(columns, args_overrides=None):
    """The packaged ClickHouse side's row expression for (name, type, is_nullable, scale) rows."""
    values = dict(clickhouse_database="shop", exclude_columns=[], hex_columns=[], include_floating_point_columns=False,
                  include_json_columns=False, json_columns="", min_datetime_value="1900-01-01 00:00:00",
                  max_datetime_value="2299-12-31 23:59:59")
    values.update(args_overrides or {})
    pc.args = argparse.Namespace(**values)

    def execute_sql(conn, sql):
        if "is_in_primary_key" in sql:
            return ([], 0)
        return (list(columns), len(columns))
    with patch.object(pc, "execute_sql", side_effect=execute_sql):
        (query, select, order_by, external) = pc.get_table_checksum_query(MagicMock(), "orders")
    return select


if __name__ == "__main__":
    unittest.main()
