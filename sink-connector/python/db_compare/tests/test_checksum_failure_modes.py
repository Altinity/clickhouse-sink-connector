#!/usr/bin/env python3
"""Failure modes of the db_compare driver (spec 11.02 section 7).

Offline: every database helper the driver calls is patched; no connection is
opened and no side script is spawned. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_failure_modes.py
"""
import argparse
import os
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402

MYSQL_HOST = "mysql-host"
CH_HOST = "clickhouse-host"
CONFIG = {"source": {"mysql": {"host": MYSQL_HOST}}, "replicas": [{"clickhouse": {"host": CH_HOST}}]}


def driver_args(**overrides):
    values = dict(
        mysql_user=None, defaults_file="~/.my.cnf", mysql_database="shop", mysql_port=3306,
        source_timezone="UTC", tables_regex=".", threads=1, binary_encoding="hex", where=None,
        debug_output=False, lock_tables_on_source=False, sleep_after_lock=0, lock_wait_timeout=30,
        fail_on_lock_timeout=False, no_wc=False, include_partitions_regex=None,
        exclude_tables_regex=None, non_partitioned_tables_only=False,
        include_floating_point_columns=False, include_json_columns=False, partition_date=None,
        threads_per_table=1,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def run_driver(compute_checksum, tables=("orders",), **arg_overrides):
    """Run run_config() over stubbed catalog helpers; return (exit code, log lines)."""
    table_rows = MagicMock()
    table_rows.fetchall.return_value = [{"table_name": t} for t in tables]
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
        patch.object(tl, "compute_checksum", side_effect=compute_checksum),
    ]
    for p in patches:
        p.start()
    try:
        with unittest.TestCase().assertLogs(level="INFO") as logs:
            try:
                tl.run_config(CONFIG)
                code = None
            except SystemExit as exit_:
                code = exit_.code
        return code, logs.output
    finally:
        for p in reversed(patches):
            p.stop()


def results(mysql_checksum, ch_checksum, count=10, table="orders"):
    return [(MYSQL_HOST, table, mysql_checksum, count), (CH_HOST, table, ch_checksum, count)]


class TestVerdictOnUnparseableOutput(unittest.TestCase):
    """FM-11.02-2: a side whose output is not exactly one checksum line."""

    def test_parse_checksum_refuses_more_than_one_line(self):
        # A second line containing the word "checksum" (a table or database whose
        # name contains it is named in the side script's own log lines) makes the
        # grep|awk pipeline emit two lines.
        with self.assertLogs(level="ERROR") as logs:
            table, checksum, count = tl.parse_checksum(b"shop.checksums abc 10\nshop.checksums def 10\n", "checksums")
        self.assertIsNone(checksum)
        self.assertIsNone(count)
        self.assertTrue(any("Invalid checksum output" in line for line in logs.output), logs.output)

    @unittest.skip("DEFECT FM-11.02-2: two unparseable sides both yield (None, None) and "
                   "analyze_differences() logs 'No difference'")
    def test_both_sides_unparseable_is_never_reported_equal(self):
        unparseable = [(MYSQL_HOST, "orders", None, None), (CH_HOST, "orders", None, None)]
        with self.assertLogs(level="INFO") as logs:
            tl.analyze_differences(unparseable, MYSQL_HOST, [CH_HOST])
        self.assertFalse(any("No difference" in line for line in logs.output), logs.output)


class TestRunExitCode(unittest.TestCase):
    """FM-11.02-1 / FM-11.02-3: what the process exit code says about the run."""

    def test_equal_run_exits_zero(self):
        code, logs = run_driver(lambda *a, **k: results("abc", "abc"))
        self.assertEqual(code, 0)
        self.assertTrue(any("No difference for orders" in line for line in logs), logs)

    def test_failed_side_script_aborts_the_whole_run_non_zero(self):
        # run_quick_safe_checksum() returns None for a side whose pipeline failed;
        # analyze_differences() then raises, the driver logs the traceback and
        # exits 1 -- every table after this one is left uncompared.
        code, logs = run_driver(lambda *a, **k: [None, (CH_HOST, "orders", "abc", 10)],
                                tables=("orders", "customers"))
        self.assertEqual(code, 1)
        self.assertTrue(any("Exception in main thread" in line for line in logs), logs)

    def test_lock_timeout_skips_the_table_and_names_it_in_the_summary(self):
        def compute(database, dom, tom, table, *a, **k):
            if table == "hot":
                raise tl.LockAcquisitionError("Could not acquire READ lock on hot within 30s")
            return results("abc", "abc", table=table)
        code, logs = run_driver(compute, tables=("hot", "orders"))
        self.assertEqual(code, 0, "skip-and-warn is the documented default")
        self.assertTrue(any("COVERAGE GAP -- skipping checksum for shop.hot" in line for line in logs), logs)
        self.assertTrue(any("1 table(s) were NOT compared" in line and "shop.hot" in line for line in logs), logs)
        self.assertTrue(any("No difference for orders" in line for line in logs), logs)

    def test_lock_timeout_fails_the_run_when_asked(self):
        def compute(*a, **k):
            raise tl.LockAcquisitionError("Could not acquire READ lock on hot within 30s")
        code, _ = run_driver(compute, tables=("hot",), fail_on_lock_timeout=True)
        self.assertEqual(code, 1)

    @unittest.skip("DEFECT FM-11.02-1: a checksum DIFFERENCE is only a WARNING line; run_config() "
                   "ends with sys.exit(0), so a scheduler sees a green run")
    def test_a_difference_makes_the_run_exit_non_zero(self):
        code, logs = run_driver(lambda *a, **k: results("abc", "def"))
        self.assertTrue(any("Checksum difference" in line for line in logs), logs)
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
