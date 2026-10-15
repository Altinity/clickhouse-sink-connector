"""The top-level driver forwards --min_datetime_value / --max_datetime_value
to both side scripts (spec 13.06 section 3.6.1, spec 11.02 section 3.4).

Background: both sides accept the bounds, but the driver had no such options,
so a scheduled run could not narrow the clamp. A replica that holds a
far-future sentinel as ``2299-12-31 00:00:00`` (rows loaded before the
connector's clamp-on-write) while the connector clamps the same MySQL value
(``9999-12-31 ...``) to ``2299-12-31 23:59:59`` was reported DIFFERENT on every
run, and the only remedy, ``--max_datetime_value 2299-12-31``, was reachable
from the side scripts alone. These tests pin: no bounds in the argv by
default, identical canonical bounds on both sides when given, loud refusal
of an unusable bound, and that the forwarded bound makes both renderings of
that sentinel compare equal.
"""
import argparse
import os
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import db_compare.clickhouse_table_checksum as ch  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402
import db_compare.top_level_table_checksum as tl  # noqa: E402
from db.checksum_common import DATETIME_MAX, DATETIME_MIN, datetime_bounds  # noqa: E402

# What the replica holds for a sentinel row loaded with its time of day, and
# what the connector writes for the same MySQL value 9999-12-31 00:00:00.
LOADED_SENTINEL = "2299-12-31 00:00:00.000000"
CLAMPED_SENTINEL = DATETIME_MAX


def driver_args(**overrides):
    values = dict(
        partition_date=None, threads=8, threads_per_table=16, source_timezone="UTC",
        binary_encoding="base64", include_floating_point_columns=False, include_json_columns=False,
        min_datetime_value=None, max_datetime_value=None,
    )
    values.update(overrides)
    return SimpleNamespace(**values)


def side_commands():
    mysql_cmd = tl.get_mysql_checksum_command("mysql-host", "db1", "t1", "id", 10, where=None)
    replica_cmd = tl.get_clickhouse_checksum_command("clickhouse-host", "db1", "t1", "id", 10)
    return (mysql_cmd, replica_cmd)


def value_after(cmd, flag):
    return cmd[cmd.index(flag) + 1]


def side_bounds(module, cmd):
    """The bounds a side script ends up with after parsing ``cmd`` exactly as
    its main() does (argv after ``<interpreter> -m <side module>``)."""
    options = module.build_argument_parser().parse_args(cmd[cmd.index("-m") + 2:])
    return datetime_bounds(options)


def shared_clamp(rendered, bounds):
    """clamp_datetime_expression's rule (``>= max`` -> max, ``< min`` -> min),
    which both dialects render identically, applied to canonical text."""
    (low, high) = bounds
    if rendered >= high:
        return high
    if rendered < low:
        return low
    return rendered


class ParserError(Exception):
    pass


class RaisingParser:
    """Stands in for argparse's parser: error() raises instead of exiting."""

    def error(self, message):
        raise ParserError(message)


class TestForwarding(unittest.TestCase):
    def tearDown(self):
        tl.args = None

    def test_no_bounds_are_passed_by_default(self):
        tl.args = driver_args()
        for cmd in side_commands():
            self.assertNotIn("--min_datetime_value", cmd)
            self.assertNotIn("--max_datetime_value", cmd)

    def test_a_namespace_without_the_options_passes_no_bounds(self):
        tl.args = argparse.Namespace(partition_date=None, threads=1, threads_per_table=1, source_timezone="UTC",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        for cmd in side_commands():
            self.assertNotIn("--max_datetime_value", cmd)

    def test_given_bounds_reach_both_sides_identically(self):
        options = driver_args(max_datetime_value="2299-12-31")
        self.assertEqual(tl.resolve_datetime_bounds(options, RaisingParser()), (DATETIME_MIN, LOADED_SENTINEL))
        tl.args = options
        (mysql_cmd, replica_cmd) = side_commands()
        self.assertEqual(value_after(mysql_cmd, "--max_datetime_value"), LOADED_SENTINEL)
        self.assertEqual(value_after(replica_cmd, "--max_datetime_value"), LOADED_SENTINEL)
        self.assertNotIn("--min_datetime_value", mysql_cmd)
        self.assertNotIn("--min_datetime_value", replica_cmd)
        self.assertEqual(side_bounds(my, mysql_cmd), (DATETIME_MIN, LOADED_SENTINEL))
        self.assertEqual(side_bounds(ch, replica_cmd), (DATETIME_MIN, LOADED_SENTINEL))

    def test_both_bounds_are_forwarded(self):
        options = driver_args(min_datetime_value="1970-01-01", max_datetime_value="2299-12-31 00:00:00")
        tl.resolve_datetime_bounds(options, RaisingParser())
        tl.args = options
        for (module, cmd) in zip((my, ch), side_commands()):
            self.assertEqual(side_bounds(module, cmd), ("1970-01-01 00:00:00.000000", LOADED_SENTINEL))


class TestValidation(unittest.TestCase):
    def test_nothing_given_resolves_to_none(self):
        options = driver_args()
        self.assertIsNone(tl.resolve_datetime_bounds(options, RaisingParser()))
        self.assertIsNone(options.max_datetime_value)

    def test_a_value_that_is_not_a_datetime_is_refused(self):
        with self.assertRaisesRegex(ParserError, "--max_datetime_value: 'end of time' is not a datetime"):
            tl.resolve_datetime_bounds(driver_args(max_datetime_value="end of time"), RaisingParser())

    def test_bounds_that_leave_no_range_are_refused(self):
        with self.assertRaisesRegex(ParserError, "must be earlier than"):
            tl.resolve_datetime_bounds(driver_args(min_datetime_value="2299-12-31", max_datetime_value="2299-12-31"),
                                       RaisingParser())
        # The missing bound counts at its default.
        with self.assertRaisesRegex(ParserError, "must be earlier than"):
            tl.resolve_datetime_bounds(driver_args(max_datetime_value="1900-01-01"), RaisingParser())

    def test_a_bound_beyond_the_clickhouse_range_is_confined_with_a_warning(self):
        options = driver_args(max_datetime_value="9999-12-31 23:59:59")
        with self.assertLogs(level="WARNING") as logs:
            tl.resolve_datetime_bounds(options, RaisingParser())
        self.assertEqual(options.max_datetime_value, DATETIME_MAX)
        self.assertIn("outside the ClickHouse DateTime64 range", "\n".join(logs.output))

    def test_an_argparse_parser_exits_on_a_bad_bound(self):
        options = driver_args(max_datetime_value="2299-12-31")
        self.assertEqual(tl.resolve_datetime_bounds(options, argparse.ArgumentParser())[1], LOADED_SENTINEL)
        with self.assertRaises(SystemExit):
            tl.resolve_datetime_bounds(driver_args(max_datetime_value="later"), argparse.ArgumentParser())


class TestFarFutureSentinel(unittest.TestCase):
    """The scenario the option exists for, through the shared clamp rule."""

    def tearDown(self):
        tl.args = None

    def test_default_bounds_report_the_loaded_sentinel_as_different(self):
        bounds = (DATETIME_MIN, DATETIME_MAX)
        self.assertNotEqual(shared_clamp(LOADED_SENTINEL, bounds), shared_clamp(CLAMPED_SENTINEL, bounds))

    def test_max_bound_at_the_start_of_the_day_makes_every_time_of_that_day_equal(self):
        options = driver_args(max_datetime_value="2299-12-31")
        tl.resolve_datetime_bounds(options, RaisingParser())
        tl.args = options
        (mysql_cmd, replica_cmd) = side_commands()
        mysql_bounds = side_bounds(my, mysql_cmd)
        replica_bounds = side_bounds(ch, replica_cmd)
        self.assertEqual(mysql_bounds, replica_bounds)
        for replica_text in (LOADED_SENTINEL, "2299-12-31 12:34:56.789000", CLAMPED_SENTINEL):
            self.assertEqual(shared_clamp(CLAMPED_SENTINEL, mysql_bounds), shared_clamp(replica_text, replica_bounds),
                             replica_text)
        # A value before the bound is still compared as itself.
        self.assertNotEqual(shared_clamp("2299-12-30 23:59:59.000000", replica_bounds),
                            shared_clamp(CLAMPED_SENTINEL, mysql_bounds))


if __name__ == "__main__":
    unittest.main()
