#!/usr/bin/env python3
"""Verdicts and exit codes of the legacy db_compare driver (spec 13.06 FM-13.06-1
to -5, -8 and -10).

Offline: database helpers are patched. The side commands are either stubbed or
run against a stand-in ``python`` executable that prints its argv, so no
database, network or real side script is involved. Run from
sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_verdicts.py
"""
import argparse
import json
import os
import re
import stat
import sys
import tempfile
import unittest
from datetime import datetime
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402

MYSQL_HOST = "db1"
CH_HOST = "ch-host"
MD5_A = "0123456789abcdef0123456789abcdef"
MD5_B = "fedcba9876543210fedcba9876543210"
MD5_EMPTY = "5f1de31ae1fd2a3d9d0cd6ba4cbd6a45"


def side_line(level, message, thread="ThreadPoolExecutor-0_0"):
    return f"2026-10-01 10:00:00,000 - {level} - {thread} - {message}"


def side_output(db_table, md5, count, extra=()):
    lines = list(extra) + [side_line("INFO", f"Checksum for table {db_table} = {md5} count {count}")]
    return ("\n".join(lines) + "\n").encode("utf-8")


def driver_args(**overrides):
    values = dict(
        mysql_user=None, defaults_file="~/.my.cnf", mysql_database="shop", mysql_port=3306,
        source_timezone="UTC", tables_regex=".", threads=1, binary_encoding="hex", where=None,
        debug_output=False, lock_tables_on_source=False, sleep_after_lock=0, lock_wait_timeout=30,
        fail_on_lock_timeout=False, no_wc=False, include_partitions_regex=None,
        exclude_tables_regex=None, non_partitioned_tables_only=False,
        include_floating_point_columns=False, include_json_columns=False, partition_date=None,
        threads_per_table=1, fail_on_empty=False,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def run_driver(side_outputs, tables=("orders",), config=None, source_columns=(), **arg_overrides):
    """Run the real run_config()/compute_checksum()/command builders with only
    the catalog helpers and the process runner stubbed.

    ``side_outputs(cmd)`` returns the (rc, stdout) a side command would give.
    ``source_columns`` is what the stubbed information_schema lists as the
    source table's columns (spec 11.02 section 3.3).
    Returns (exit code, log lines)."""
    config = config or {"source": {"mysql": {"host": MYSQL_HOST}}, "replicas": [{"clickhouse": {"host": CH_HOST}}]}
    table_rows = MagicMock()
    table_rows.mappings.return_value.fetchall.return_value = [{"table_name": t} for t in tables]
    patches = [
        patch.object(tl, "args", driver_args(**arg_overrides), create=True),
        patch.object(tl, "resolve_credentials_from_config", return_value=("u", "p")),
        patch.object(tl, "get_mysql_connection", return_value=MagicMock()),
        patch.object(tl, "resolve_source_timezone", return_value="UTC"),
        patch.object(tl, "get_tables_from_regexp", return_value=table_rows),
        patch.object(tl, "mysql_pk_columns", return_value=["id"]),
        patch.object(tl, "get_min_max_pk_value", return_value=(1, 10)),
        patch.object(tl, "get_table_partition_key", return_value=None),
        patch.object(tl, "mysql_columns_by_data_type", return_value=[]),
        patch.object(tl, "mysql_column_names", return_value=list(source_columns)),
        patch.object(tl, "run_quick_safe_command", side_effect=side_outputs),
    ]
    for p in patches:
        p.start()
    try:
        with unittest.TestCase().assertLogs(level="INFO") as logs:
            try:
                tl.run_config(config)
                code = None
            except SystemExit as exit_:
                code = exit_.code
        return code, logs.output
    finally:
        for p in reversed(patches):
            p.stop()


def is_mysql_side(cmd):
    return "db_compare/mysql_table_checksum.py" in cmd


class TestFailedSidesAreErrors(unittest.TestCase):
    """FM-13.06-1 (legacy part): a side that fails or prints nothing usable is an ERROR."""

    def test_both_sides_failing_exits_non_zero_and_never_reports_equal(self):
        code, logs = run_driver(lambda cmd: ("1", b"Traceback (most recent call last):\nModuleNotFoundError: No module named 'db'\n"))
        self.assertEqual(code, 1)
        self.assertFalse(any("No difference" in line for line in logs), logs)
        self.assertTrue(any("Checksum ERROR for shop.orders" in line for line in logs), logs)
        self.assertTrue(any("No module named 'db'" in line for line in logs), "the side's own error must be logged")

    def test_both_sides_without_a_checksum_line_exit_non_zero(self):
        code, logs = run_driver(lambda cmd: ("0", b"python: can't open file 'db_compare/x.py'\n"))
        self.assertEqual(code, 1)
        self.assertFalse(any("No difference" in line for line in logs), logs)

    def test_missing_replica_table_is_an_error(self):
        def outputs(cmd):
            if is_mysql_side(cmd):
                return "0", side_output("shop.orders", MD5_A, 10)
            return "0", (side_line("INFO", "REGEX QUERY: select name from system.tables ...") + "\n").encode()
        code, logs = run_driver(outputs)
        self.assertEqual(code, 1)
        self.assertTrue(any("1 table(s) have NO verdict" in line for line in logs), logs)

    def test_a_side_that_cannot_start_is_an_error(self):
        with patch.object(tl.subprocess, "Popen", side_effect=FileNotFoundError("python")):
            rc, out = tl.run_quick_safe_command(["python", "db_compare/mysql_table_checksum.py"])
        self.assertNotEqual(rc, "0")
        self.assertEqual(tl.analyze_differences([None, None], MYSQL_HOST, [CH_HOST], "shop.orders"), tl.VERDICT_ERROR)

    def test_result_count_mismatch_is_an_error(self):
        verdict = tl.analyze_differences([(MYSQL_HOST, "shop.orders", MD5_A, 3)], MYSQL_HOST, [CH_HOST])
        self.assertEqual(verdict, tl.VERDICT_ERROR)


class TestChecksumWordInNames(unittest.TestCase):
    """FM-13.06-2: a column, table or database name containing "checksum"."""

    NOISE = (
        side_line("INFO", "REGEX QUERY: select name from system.tables where match(name,'^orders$')"),
        side_line("WARNING", "Not compared in table shop.orders: floating point columns ['checksum_ratio'] "
                             "(pass --include_floating_point_columns to compare their text renderings)"),
        side_line("INFO", "Excluded columns, ['checksum_note']"),
    )

    def test_parse_takes_only_the_result_line(self):
        name, md5, count = tl.parse_checksum(side_output("shop.orders", MD5_A, 2, self.NOISE), "orders", "shop.orders")
        self.assertEqual((name, md5, count), ("shop.orders", MD5_A, 2))

    def test_two_result_lines_are_invalid(self):
        data = side_output("shop.orders", MD5_A, 2) + side_output("shop.orders", MD5_B, 2)
        with self.assertLogs(level="ERROR"):
            self.assertIsNone(tl.parse_checksum(data, "orders")[1])

    def test_result_for_another_table_is_invalid(self):
        with self.assertLogs(level="ERROR") as logs:
            self.assertIsNone(tl.parse_checksum(side_output("shop.orders", MD5_A, 2), "orders$archive",
                                                "shop.orders$archive")[1])
        self.assertTrue(any("expected shop.orders$archive" in line for line in logs.output), logs.output)

    def test_differing_data_with_checksum_named_column_reports_a_difference(self):
        def outputs(cmd):
            md5 = MD5_A if is_mysql_side(cmd) else MD5_B
            return "0", side_output("shop.orders", md5, 2, self.NOISE)
        code, logs = run_driver(outputs)
        self.assertTrue(any("Checksum difference" in line for line in logs), logs)
        self.assertFalse(any("No difference" in line for line in logs), logs)

    def test_checksum_named_table_is_compared(self):
        def outputs(cmd):
            return "0", side_output("shop.checksums", MD5_A, 4, (
                side_line("INFO", "REGEX QUERY: ... match(name,'^checksums$')"),))
        code, logs = run_driver(outputs, tables=("checksums",))
        self.assertEqual(code, 0)
        self.assertTrue(any("No difference for shop.checksums" in line for line in logs), logs)


def stand_in_python(directory, exit_code=0):
    """An executable named ``python`` that prints its argv as JSON."""
    path = os.path.join(directory, "python")
    with open(path, "w") as script:
        script.write(f"#!{sys.executable}\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\nsys.exit({exit_code})\n")
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
    return path


class TestArgumentsReachTheSideUnchanged(unittest.TestCase):
    """FM-13.06-3 and FM-13.06-10: no shell between the driver and the sides."""

    TABLE = "orders$archive"
    WHERE = "`status` = 'A' and note <> '$HOME' and x = \"q\" and y = '\\\\'"

    def setUp(self):
        tl.args = driver_args(partition_date=datetime(2026, 9, 28))
        self.tmp = tempfile.TemporaryDirectory()
        stand_in_python(self.tmp.name)
        self.env = patch.dict(os.environ, {"PATH": self.tmp.name + os.pathsep + os.environ.get("PATH", "")})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def received(self, cmd):
        rc, stdout = tl.run_quick_safe_command(cmd)
        self.assertEqual(rc, "0", stdout)
        return json.loads(stdout.decode())

    def test_mysql_side_receives_table_and_where_byte_for_byte(self):
        argv = self.received(tl.get_mysql_checksum_command(MYSQL_HOST, "shop", self.TABLE, "id", 10, where=self.WHERE))
        self.assertEqual(argv[argv.index("--where") + 1], f" 1=1  and {self.WHERE}  and {{partition_expression}}=20260928")
        regex = argv[argv.index("--tables_regex") + 1]
        self.assertTrue(re.fullmatch(regex, self.TABLE), regex)
        self.assertFalse(re.fullmatch(regex, "orders"), regex)

    def test_clickhouse_side_receives_table_where_and_function_partition_key(self):
        argv = self.received(tl.get_clickhouse_checksum_command(CH_HOST, "shop", self.TABLE, "id", 10, where=self.WHERE,
                                                                 partition_key="to_days(`created`)"))
        self.assertEqual(argv[argv.index("--where") + 1],
                         f" 1=1  and {self.WHERE}  and {{partition_expression}}=toDate('2026-09-28') ")
        self.assertEqual(argv[argv.index("--partition_key") + 1], "to_days(created)")
        regex = argv[argv.index("--tables_regex") + 1]
        self.assertTrue(re.fullmatch(regex, self.TABLE), regex)
        self.assertFalse(re.fullmatch(regex, "orders"), regex)

    def test_plain_table_regex_is_unchanged(self):
        self.assertEqual(tl.exact_table_regex("order_items2"), "^order_items2$")

    def test_side_exit_code_is_the_scripts_own(self):
        stand_in_python(self.tmp.name, exit_code=3)
        rc, _ = tl.run_quick_safe_command(tl.get_mysql_checksum_command(MYSQL_HOST, "shop", "t1", "id", 10, where=None))
        self.assertEqual(rc, "3")


class TestSameHostString(unittest.TestCase):
    """FM-13.06-4: the source is identified by position, not by host string."""

    def test_equal_host_strings_still_get_a_difference_verdict(self):
        results = [("localhost", "shop.orders", MD5_A, 2), ("localhost", "shop.orders", MD5_B, 2)]
        with self.assertLogs(level="INFO") as logs:
            verdict = tl.analyze_differences(results, "localhost", ["localhost"], "shop.orders")
        self.assertEqual(verdict, tl.VERDICT_DIFFERENT)
        self.assertTrue(any("Checksum difference" in line for line in logs.output), logs.output)

    def test_run_with_equal_host_strings_logs_a_verdict(self):
        config = {"source": {"mysql": {"host": "localhost"}}, "replicas": [{"clickhouse": {"host": "localhost"}}]}
        code, logs = run_driver(lambda cmd: ("0", side_output("shop.orders", MD5_A, 2)), config=config)
        self.assertEqual(code, 0)
        self.assertTrue(any("No difference for shop.orders" in line for line in logs), logs)
        self.assertTrue(any("1 MATCH" in line for line in logs), logs)


class TestEmptyOnBothSides(unittest.TestCase):
    """FM-13.06-5: zero rows on both sides is EMPTY, never a silent match."""

    def outputs(self, cmd):
        return "0", side_output("shop.orders", MD5_EMPTY, 0)

    def test_empty_is_logged_at_info_and_exit_zero_by_default(self):
        # INFO, not WARNING: scheduled jobs fail on any WARNING line, and that
        # word is reserved for "Checksum difference" (spec 13.06 section 3.8).
        code, logs = run_driver(self.outputs)
        self.assertEqual(code, 0)
        self.assertFalse(any("No difference" in line for line in logs), logs)
        self.assertIn("INFO:root:EMPTY on both sides for shop.orders: 0 rows compared (empty table, or a filter "
                      "or --partition_date that matched nothing)", logs)
        self.assertTrue(any(line.startswith("INFO:") and "EMPTY on both sides: 1 table(s)" in line for line in logs), logs)
        self.assertFalse(any("WARNING" in line for line in logs), logs)

    def test_fail_on_empty_makes_it_non_zero(self):
        code, logs = run_driver(self.outputs, fail_on_empty=True)
        self.assertEqual(code, 1)
        self.assertTrue(any(line.startswith("ERROR:") and "EMPTY on both sides: 1 table(s)" in line for line in logs), logs)

    def test_fail_on_empty_flag_exists(self):
        with patch.object(sys, "argv", ["x", "--config_file", "/nonexistent.yaml", "--fail_on_empty"]), \
                patch.object(tl, "parse_config", side_effect=SystemExit(7)):
            with self.assertRaises(SystemExit) as raised:
                tl.main()
        self.assertEqual(raised.exception.code, 7)
        self.assertTrue(tl.args.fail_on_empty)

    def test_rows_on_one_side_only_is_a_difference(self):
        def outputs(cmd):
            return "0", (side_output("shop.orders", MD5_EMPTY, 0) if is_mysql_side(cmd) else side_output("shop.orders", MD5_A, 3))
        code, logs = run_driver(outputs)
        self.assertTrue(any("Checksum difference" in line for line in logs), logs)


class TestSideWarningsReachTheDriverLog(unittest.TestCase):
    """FM-13.06-8: coverage and clamp WARNINGs of the sides are relayed, at INFO
    as side notes without the word WARNING (spec 13.06 section 3.17)."""

    def test_side_warnings_are_logged_by_the_driver(self):
        coverage = "Not compared in table shop.orders: floating point columns ['ratio'] (pass --include_floating_point_columns ...)"
        clamp = "3 out-of-range datetime values clamped in table shop.orders"
        def outputs(cmd):
            return "0", side_output("shop.orders", MD5_A, 2, (side_line("WARNING", coverage), side_line("WARNING", clamp)))
        code, logs = run_driver(outputs)
        self.assertEqual(code, 0)
        self.assertTrue(any(line.startswith("INFO:") and "side note" in line and coverage in line for line in logs), logs)
        self.assertTrue(any(line.startswith("INFO:") and "side note" in line and clamp in line for line in logs), logs)
        self.assertTrue(any("No difference for shop.orders" in line for line in logs), logs)
        self.assertFalse(any("WARNING" in line for line in logs), logs)

    def test_side_error_line_fails_the_table_even_with_exit_zero(self):
        def outputs(cmd):
            extra = (side_line("ERROR", "Exception in table orders"),) if is_mysql_side(cmd) else ()
            return "0", side_output("shop.orders", MD5_A, 2, extra)
        code, logs = run_driver(outputs)
        self.assertEqual(code, 1)
        self.assertTrue(any(line.startswith("ERROR:") and "Exception in table orders" in line for line in logs), logs)
        self.assertTrue(any("Checksum ERROR for shop.orders" in line for line in logs), logs)
        self.assertFalse(any("No difference" in line for line in logs), logs)


class TestSourceColumnSetReachesTheReplicaSide(unittest.TestCase):
    """Spec 11.02 section 3.3 (column set): the driver hands the source table's
    column names to every ClickHouse side, never to the MySQL side, and the
    replica-only coverage note comes back as a side note, not a WARNING."""

    def test_replica_sides_get_the_source_columns_and_the_note_is_info(self):
        commands = []
        note = ("Not compared in table shop.orders: replica-only columns ['name'] "
                "(absent from the source table, so there is no source value to compare)")

        def outputs(cmd):
            commands.append(list(cmd))
            extra = () if is_mysql_side(cmd) else (side_line("WARNING", note),)
            return "0", side_output("shop.orders", MD5_A, 2, extra)
        code, logs = run_driver(outputs, source_columns=("id", "user"))
        self.assertEqual(code, 0)
        mysql_cmds = [cmd for cmd in commands if is_mysql_side(cmd)]
        replica_cmds = [cmd for cmd in commands if not is_mysql_side(cmd)]
        self.assertTrue(mysql_cmds and replica_cmds, commands)
        for cmd in replica_cmds:
            self.assertEqual(json.loads(cmd[cmd.index("--source_columns") + 1]), ["id", "user"])
        for cmd in mysql_cmds:
            self.assertNotIn("--source_columns", cmd)
        self.assertTrue(any(line.startswith("INFO:") and "side note" in line and "replica-only columns ['name']" in line
                            for line in logs), logs)
        self.assertFalse(any("WARNING" in line for line in logs), logs)

    def test_unknown_source_columns_pass_no_flag(self):
        commands = []

        def outputs(cmd):
            commands.append(list(cmd))
            return "0", side_output("shop.orders", MD5_A, 2)
        run_driver(outputs, source_columns=())
        self.assertTrue(commands)
        self.assertFalse(any("--source_columns" in cmd for cmd in commands), commands)


if __name__ == "__main__":
    unittest.main()
