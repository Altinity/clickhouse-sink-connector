"""Explicit source zone (spec 11.02 section 3.2 step 1, section 3.4).

Every checksum tool needs --source_timezone. There is no server-zone query and no UTC
default: a run without the flag is refused before any connection is opened. Only the
side-tool refusals run as subprocesses (they must refuse before connecting). Floating
point columns skipped by default produce a WARNING that names the count and the columns.
"""
import atexit
import inspect
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

PYTHON_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, PYTHON_ROOT)

import ch_sink_tools.db.checksum_common as checksum_common  # noqa: E402
import ch_sink_tools.db_compare.top_level_table_checksum as tl  # noqa: E402

TOOLS = {
    "top_level": "ch_sink_tools.db_compare.top_level_table_checksum",
    "clickhouse_side": "ch_sink_tools.db_compare.clickhouse_table_checksum",
    "mysql_side": "ch_sink_tools.db_compare.mysql_table_checksum",
}
# Config files exist, so a side tool that does not refuse reaches the zone step (and then fails to connect).
CONFIG_DIR = tempfile.mkdtemp(prefix="explicit-zone-")
atexit.register(shutil.rmtree, CONFIG_DIR, True)
CLICKHOUSE_CONFIG = os.path.join(CONFIG_DIR, "clickhouse-client.xml")
MYSQL_DEFAULTS = os.path.join(CONFIG_DIR, "my.cnf")
with open(CLICKHOUSE_CONFIG, "w") as handle:
    handle.write("<clickhouse></clickhouse>\n")
with open(MYSQL_DEFAULTS, "w") as handle:
    handle.write("[client]\nuser = test\npassword = test\n")
REQUIRED_ARGS = {
    "top_level": ["--config_file", os.path.join(PYTHON_ROOT, "no-such-config.yml")],
    "clickhouse_side": ["--clickhouse_host", "127.0.0.1", "--clickhouse_database", "db1", "--tables_regex", "t1",
                        "--clickhouse_config_file", CLICKHOUSE_CONFIG],
    "mysql_side": ["--mysql_host", "127.0.0.1", "--mysql_database", "db1", "--tables_regex", "t1",
                   "--defaults_file", MYSQL_DEFAULTS],
}


def run_tool(name, extra=()):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    return subprocess.run([sys.executable, "-m", TOOLS[name], *REQUIRED_ARGS[name], *extra],
                          cwd=PYTHON_ROOT, env=env, capture_output=True, text=True, timeout=120)


class TestNoFlagIsRefused(unittest.TestCase):
    """Without --source_timezone the parser exits 2 and names the flag, a zone and UTC."""

    def assert_refused(self, name):
        done = run_tool(name)
        output = done.stdout + done.stderr
        self.assertEqual(done.returncode, 2, f"expected argparse refusal, got rc={done.returncode}: {output[-800:]}")
        self.assertIn("--source_timezone", done.stderr)
        self.assertIn("America/Chicago", done.stderr)
        self.assertIn("UTC", done.stderr)
        self.assertNotIn("source time zone:", output)

    def test_top_level_refuses_without_source_timezone(self):
        self.assert_refused("top_level")

    def test_clickhouse_side_refuses_without_source_timezone(self):
        self.assert_refused("clickhouse_side")

    def test_mysql_side_refuses_without_source_timezone(self):
        self.assert_refused("mysql_side")


class TestNoServerZoneQuery(unittest.TestCase):
    """The driver never asks the server for its zone, and resolve_source_zone has no session or default input."""

    def test_driver_without_flag_is_refused_before_any_query(self):
        with patch.object(tl, "execute_mysql", side_effect=AssertionError("the server zone was queried")):
            with self.assertRaises(ValueError) as caught:
                tl.resolve_source_timezone(MagicMock(), None)
        self.assertIn("--source_timezone", str(caught.exception))
        self.assertIn("America/Chicago", str(caught.exception))

    def test_resolve_source_zone_has_no_session_or_default_parameter(self):
        names = set(inspect.signature(checksum_common.resolve_source_zone).parameters)
        self.assertEqual(names, {"explicit", "option_name"})

    def test_no_flag_error_names_the_option_a_zone_and_utc(self):
        with self.assertRaises(ValueError) as caught:
            checksum_common.resolve_source_zone(None, option_name="--source_timezone")
        message = str(caught.exception)
        self.assertIn("--source_timezone", message)
        self.assertIn("America/Chicago", message)
        self.assertIn("UTC", message)


class TestExplicitFlagUnchanged(unittest.TestCase):
    """An explicit zone is used as given, with no server query."""

    def test_explicit_zone_is_used_without_a_server_query(self):
        with patch.object(tl, "execute_mysql", side_effect=AssertionError("queried")):
            self.assertEqual(tl.resolve_source_timezone(MagicMock(), "Europe/Berlin"), "Europe/Berlin")
            self.assertEqual(tl.resolve_source_timezone(MagicMock(), "UTC"), "UTC")

    def test_explicit_zone_resolves_to_itself_with_flag_provenance(self):
        self.assertEqual(checksum_common.resolve_source_zone("Asia/Tokyo", option_name="--source_timezone"),
                         ("Asia/Tokyo", "flag"))


class TestOneZonePerRun(unittest.TestCase):
    """The run states that one zone applies to every table it compares."""

    def test_the_run_states_that_one_zone_applies_to_every_table(self):
        with self.assertLogs(level="INFO") as logs:
            tl.resolve_source_timezone(MagicMock(), "America/Chicago")
        self.assertIn("one source time zone per run", "\n".join(logs.output))


class TestSkippedFloatingPointColumnsWarn(unittest.TestCase):
    """A float column skipped by default is named with its count: a WARNING on the side, an INFO note in the driver.

    The driver log is scanned for WARNING by the production job, and a skipped column is not a difference.
    """

    def test_side_note_about_skipped_float_columns_is_an_info_note_with_count_and_names(self):
        line = ("2026-10-22 10:00:00,000 - WARNING - MainThread - Not compared in table db1.t1: "
                "2 floating point columns ['price', 'ratio'] (pass --include_floating_point_columns "
                "to compare their text renderings)")
        with self.assertLogs(level="INFO") as logs:
            tl.relay_side_messages(line, "mysql-host", "t1")
        self.assertEqual([r for r in logs.records if r.levelno >= logging.WARNING], [])
        text = "\n".join(logs.output)
        self.assertIn("2 floating point columns", text)
        self.assertIn("'price'", text)
        self.assertIn("'ratio'", text)

    def test_side_warning_names_the_count(self):
        with self.assertLogs(level="WARNING") as logs:
            checksum_common.warn_not_compared("db1", "t1", {"floating point": ["price", "ratio"]}, set())
        self.assertIn("2 floating point columns", "\n".join(logs.output))


if __name__ == "__main__":
    unittest.main()
