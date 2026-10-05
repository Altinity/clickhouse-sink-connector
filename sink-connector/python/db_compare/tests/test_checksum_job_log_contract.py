#!/usr/bin/env python3
"""The log contract scheduled checksum jobs rely on (spec 13.06 section 3.17).

A scheduled job runs the legacy driver, tees its stdout to
``top_level_table_checksum_*.log`` and then fails when any line of those logs
contains the word WARNING; that scan exists to catch "Checksum difference". So
a clean run (matches, EMPTY tables, side notes such as columns not compared)
must log no WARNING and exit 0, and a difference must still log its WARNING
line unchanged.

Offline: the driver's main() runs with the job's own flags; only the MySQL
catalog helpers are patched. The side commands really run, through
subprocess, against a stand-in ``python`` executable that prints canned side
output, so no database or network is involved. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_job_log_contract.py
"""
import io
import json
import logging
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402

MD5_A = "0123456789abcdef0123456789abcdef"
MD5_B = "fedcba9876543210fedcba9876543210"
MD5_EMPTY = "5f1de31ae1fd2a3d9d0cd6ba4cbd6a45"

# Prints, in the side scripts' log format, what SIDES[<db>.<table>][<side>]
# says: [md5, count, [(level, message), ...], exit code].
STAND_IN_SIDE = r'''#!{python}
import json, os, re, sys
argv = sys.argv[1:]
side = "mysql" if "mysql_table_checksum" in " ".join(argv[:2]) else "clickhouse"
def value(flag):
    return argv[argv.index(flag) + 1]
database = value("--mysql_database" if side == "mysql" else "--clickhouse_database")
table = re.sub(r"\[(.)\]", r"\1", value("--tables_regex"))[1:-1]
spec = json.load(open(os.environ["STAND_IN_SIDES"]))["shop." + table][side]
(md5, count, notes, code) = spec
for (level, message) in notes:
    print(f"2026-10-01 10:00:00,000 - {{level}} - ThreadPoolExecutor-0_0 - {{message}}")
print(f"2026-10-01 10:00:00,000 - INFO - ThreadPoolExecutor-0_0 - Checksum for table {{database}}.{{table}} = {{md5}} count {{count}}")
sys.exit(code)
'''

CONFIG_YAML = """\
replicas:
  - clickhouse:
      host: ch-host
      database_override_map: "other:other_ch,shop:shop"
source:
  mysql:
    host: db1
    databases: [shop]
    source_timezone: UTC
    ignored_columns: ["shop.orders.secret"]
    table_include_list: "shop.orders$,shop.empty_t$,shop.items$"
    tables:
      - "shop.orders":
          where: "{partition_expression} >= 0 /*!50000 and status <> 'X' */"
"""

# The two scheduled-job command lines (partitioned and non-partitioned runs).
PARTITIONED_RUN = ["--binary_encoding", "base64", "--source_timezone", "UTC", "--debug",
                   "--tables_regex", "orders|empty_t|items", "--mysql_database", "shop",
                   "--include_partitions_regex", "p.*", "--partition_date", "2026/09/28",
                   "--threads", "8", "--threads_per_table", "16",
                   "--exclude_tables_regex", "(temp|no_partition|heartbeat)"]
NON_PARTITIONED_RUN = ["--binary_encoding", "base64", "--source_timezone", "UTC",
                       "--tables_regex", ".", "--non_partitioned_tables_only",
                       "--lock_tables_on_source", "--sleep_after_lock", "3",
                       "--threads", "8", "--threads_per_table", "16",
                       "--exclude_tables_regex", "(temp|_p[0-9]|no_partition|heartbeat)"]

CLEAN_NOTES = [
    ("WARNING", "Not compared in table shop.orders: JSON columns ['doc'] (pass --include_json_columns for a best-effort text comparison)"),
    ("WARNING", "Connection was closed, reconnecting."),
    ("WARNING", "first warning : WARNING-level server notice"),
]


def clean_sides():
    return {
        "shop.orders": {"mysql": [MD5_A, 5, CLEAN_NOTES, 0], "clickhouse": [MD5_A, 5, CLEAN_NOTES, 0]},
        "shop.empty_t": {"mysql": [MD5_EMPTY, 0, [], 0], "clickhouse": [MD5_EMPTY, 0, [], 0]},
        "shop.items": {"mysql": [MD5_B, 3, [], 0], "clickhouse": [MD5_B, 3, [], 0]},
    }


class JobRun:
    """One driver run as the scheduled job starts it; returns (exit code, log lines)."""

    def __init__(self, directory):
        self.directory = directory
        with open(os.path.join(directory, "python"), "w") as script:
            script.write(STAND_IN_SIDE.format(python=sys.executable))
        os.chmod(os.path.join(directory, "python"), 0o755 | stat.S_IXUSR)
        self.config = os.path.join(directory, "checksum.yaml")
        with open(self.config, "w") as config:
            config.write(CONFIG_YAML)
        self.defaults_file = os.path.join(directory, ".my.cnf")
        with open(self.defaults_file, "w") as cnf:
            cnf.write("[client]\nuser = checker\npassword = secret\n")
        self.sides_file = os.path.join(directory, "sides.json")

    def __call__(self, sides, flags, tables=("orders", "empty_t", "items")):
        with open(self.sides_file, "w") as spec:
            json.dump(sides, spec)
        table_rows = MagicMock()
        table_rows.mappings.return_value.fetchall.return_value = [{"table_name": t} for t in tables]

        def columns_by_type(conn, database, table, data_types):
            return ["doc"] if data_types == ("json",) and table == "orders" else []

        argv = ["db_compare/top_level_table_checksum.py", "--defaults_file", self.defaults_file,
                "--config", self.config] + flags
        environment = {"PATH": self.directory + os.pathsep + os.environ.get("PATH", ""),
                       "STAND_IN_SIDES": self.sides_file}
        root = logging.getLogger()
        (handlers, level) = (list(root.handlers), root.level)
        out = io.StringIO()
        with patch.object(sys, "argv", argv), patch.dict(os.environ, environment), \
                patch.object(tl, "side_command",
                             lambda module: [os.path.join(self.directory, "python"), "-m", module]), \
                patch.object(tl, "get_mysql_connection", return_value=MagicMock()), \
                patch.object(tl, "execute_mysql", return_value=(MagicMock(), -1)), \
                patch.object(tl.time, "sleep"), \
                patch.object(tl, "get_tables_from_regexp", return_value=table_rows), \
                patch.object(tl, "mysql_pk_columns", return_value=["id"]), \
                patch.object(tl, "get_min_max_pk_value", return_value=(1, 10)), \
                patch.object(tl, "get_table_partition_key", return_value="to_days(`created`)"), \
                patch.object(tl, "mysql_columns_by_data_type", side_effect=columns_by_type), \
                patch.object(tl, "mysql_column_names", return_value=["id", "doc"]), \
                redirect_stdout(out):
            try:
                tl.main()
                code = None
            except SystemExit as exit_:
                code = exit_.code
            finally:
                for handler in root.handlers[len(handlers):]:
                    root.removeHandler(handler)
                root.setLevel(level)
        return code, out.getvalue().splitlines()


class TestScheduledJobLogContract(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.run_job = JobRun(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_run_with_empty_table_and_side_notes_logs_no_warning(self):
        (partitioned_code, partitioned_log) = self.run_job(clean_sides(), PARTITIONED_RUN)
        (non_partitioned_code, non_partitioned_log) = self.run_job(clean_sides(), NON_PARTITIONED_RUN)
        combined = partitioned_log + non_partitioned_log
        # the job's own check: grep -Hn "WARNING" top_level_table_checksum_*.log
        self.assertEqual([line for line in combined if "WARNING" in line], [], "\n".join(combined))
        self.assertEqual((partitioned_code, non_partitioned_code), (0, 0), "\n".join(combined))
        for log in (partitioned_log, non_partitioned_log):
            self.assertTrue(any(" - INFO - " in line and "No difference for shop.orders" in line for line in log), log)
            self.assertTrue(any(" - INFO - " in line and "EMPTY on both sides for shop.empty_t" in line for line in log), log)
            self.assertTrue(any(" - INFO - " in line and "EMPTY on both sides: 1 table(s) compared 0 rows" in line
                                for line in log), log)
            self.assertTrue(any(" - INFO - " in line and "side note" in line and "JSON columns ['doc']" in line
                                for line in log), log)
            self.assertTrue(any("Run summary: 3 table(s) verified: 2 MATCH, 0 DIFFERENT, 1 EMPTY, 0 ERROR" in line
                                for line in log), log)
        # --debug dumps the side output too, without the word WARNING
        self.assertTrue(any(" - DEBUG - " in line and "side output: " in line and "Connection was closed" in line
                            for line in partitioned_log), partitioned_log)

    def test_difference_still_logs_the_checksum_difference_warning(self):
        sides = clean_sides()
        sides["shop.orders"]["clickhouse"] = [MD5_B, 5, CLEAN_NOTES, 0]
        (code, log) = self.run_job(sides, PARTITIONED_RUN)
        warnings = [line for line in log if "WARNING" in line]
        self.assertEqual(len(warnings), 1, "\n".join(log))
        self.assertIn(" - WARNING - ", warnings[0])
        self.assertTrue(warnings[0].endswith(
            f" - Checksum difference : ('ch-host', 'shop.orders', '{MD5_B}', 5) to ('db1', 'shop.orders', '{MD5_A}', 5)"),
            warnings[0])
        self.assertEqual(code, 0, "a difference keeps exit 0 (spec 11.02 FM-11.02-1); the WARNING scan fails the job")

    def test_side_error_line_fails_the_run(self):
        sides = clean_sides()
        sides["shop.items"]["mysql"] = [MD5_B, 3, [("ERROR", "Checksum failed for items")], 0]
        (code, log) = self.run_job(sides, NON_PARTITIONED_RUN)
        self.assertEqual(code, 1, "\n".join(log))
        self.assertTrue(any(" - ERROR - " in line and "Checksum ERROR for shop.items" in line for line in log), log)

    REPLICA_ONLY_REPORT = ("WARNING", "Replica-only columns in table shop.items: ['name'] "
                                      "(present on the ClickHouse destination, absent from the source table; not compared)")

    def test_replica_only_column_fails_the_job_by_default(self):
        """Spec 11.02 section 3.9: a column added on the destination only is
        reported through the job's WARNING scan and fails the run."""
        sides = clean_sides()
        sides["shop.items"]["clickhouse"] = [MD5_B, 3, [self.REPLICA_ONLY_REPORT], 0]
        (code, log) = self.run_job(sides, NON_PARTITIONED_RUN)
        self.assertEqual(code, 1, "\n".join(log))
        warnings = [line for line in log if "WARNING" in line]
        self.assertTrue(any("REPLICA-ONLY COLUMNS -- ch-host shop.items: ['name']" in line for line in warnings), log)
        self.assertFalse(any("Checksum difference" in line for line in log), log)
        self.assertTrue(any("No difference for shop.items" in line for line in log), log)

    def test_allow_replica_only_columns_flag_keeps_the_job_green(self):
        sides = clean_sides()
        sides["shop.items"]["clickhouse"] = [MD5_B, 3, [self.REPLICA_ONLY_REPORT], 0]
        (code, log) = self.run_job(sides, NON_PARTITIONED_RUN + ["--allow_replica_only_columns"])
        self.assertEqual(code, 0, "\n".join(log))
        self.assertEqual([line for line in log if "WARNING" in line], [], "\n".join(log))
        self.assertTrue(any(" - INFO - " in line and "replica-only columns ['name'] accepted" in line for line in log), log)


if __name__ == "__main__":
    unittest.main()
