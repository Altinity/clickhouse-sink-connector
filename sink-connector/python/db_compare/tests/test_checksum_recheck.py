#!/usr/bin/env python3
"""A table that differs is checksummed again before it is reported (spec 13.06
section 3.7.3).

The sides of a table written during the run read it at different moments (the
MySQL side even reads its PK chunks over separate connections), so one pass can
differ although no row diverges. Both drivers re-check a DIFFERENT table
``--recheck_differences`` more times (default 1), ``--recheck_delay_seconds``
apart (default 60), and only the last pass may log the WARNING
"Checksum difference" that scheduled jobs fail on.

Offline. The end-to-end cases run the legacy driver's main() with the scheduled
job's flags against a stand-in ``python`` executable whose canned output changes
from one pass to the next; only the MySQL catalog helpers and time.sleep are
patched. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_recheck.py
"""
import argparse
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
import ch_sink_tools.db_compare.top_level_table_checksum as pt  # noqa: E402

MD5_A = "0123456789abcdef0123456789abcdef"
MD5_B = "fedcba9876543210fedcba9876543210"

# Prints the result for the n-th call of this side for this table:
# SIDES[<db>.<table>][<side>][n] is [md5, count]; the last entry repeats.
STAND_IN_SIDE = r'''#!{python}
import json, os, re, sys
argv = sys.argv[1:]
side = "mysql" if "mysql_table_checksum.py" in argv[0] else "clickhouse"
def value(flag):
    return argv[argv.index(flag) + 1]
database = value("--mysql_database" if side == "mysql" else "--clickhouse_database")
table = re.sub(r"\[(.)\]", r"\1", value("--tables_regex"))[1:-1]
passes = json.load(open(os.environ["STAND_IN_SIDES"]))["shop." + table][side]
counter = os.path.join(os.environ["STAND_IN_DIR"], f"calls.{{table}}.{{side}}")
calls = int(open(counter).read()) if os.path.exists(counter) else 0
open(counter, "w").write(str(calls + 1))
(md5, count) = passes[min(calls, len(passes) - 1)]
print(f"2026-10-01 10:00:00,000 - INFO - ThreadPoolExecutor-0_0 - Checksum for table {{database}}.{{table}} = {{md5}} count {{count}}")
'''

CONFIG_YAML = """\
replicas:
  - clickhouse:
      host: ch-host
source:
  mysql:
    host: db1
    databases: [shop]
    source_timezone: UTC
"""

# The scheduled job's partitioned command line (no re-check flag: the default applies).
JOB_FLAGS = ["--binary_encoding", "base64", "--source_timezone", "UTC",
             "--tables_regex", "orders|items", "--mysql_database", "shop",
             "--include_partitions_regex", "p.*", "--partition_date", "2026/09/30",
             "--threads", "8", "--threads_per_table", "16",
             "--exclude_tables_regex", "(temp|no_partition|heartbeat)"]


class JobRun:
    """One legacy driver run; returns (exit code, log lines, sleeps, side calls)."""

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

    def side_calls(self, table, side):
        counter = os.path.join(self.directory, f"calls.{table}.{side}")
        return int(open(counter).read()) if os.path.exists(counter) else 0

    def __call__(self, sides, flags, tables=("orders", "items")):
        with open(self.sides_file, "w") as spec:
            json.dump(sides, spec)
        table_rows = MagicMock()
        table_rows.mappings.return_value.fetchall.return_value = [{"table_name": t} for t in tables]
        argv = ["db_compare/top_level_table_checksum.py", "--defaults_file", self.defaults_file,
                "--config", self.config] + flags
        environment = {"PATH": self.directory + os.pathsep + os.environ.get("PATH", ""),
                       "STAND_IN_SIDES": self.sides_file, "STAND_IN_DIR": self.directory}
        root = logging.getLogger()
        (handlers, level) = (list(root.handlers), root.level)
        out = io.StringIO()
        sleep = MagicMock()
        with patch.object(sys, "argv", argv), patch.dict(os.environ, environment), \
                patch.object(tl, "get_mysql_connection", return_value=MagicMock()), \
                patch.object(tl, "execute_mysql", return_value=(MagicMock(), -1)), \
                patch.object(tl.time, "sleep", sleep), \
                patch.object(tl, "get_tables_from_regexp", return_value=table_rows), \
                patch.object(tl, "mysql_pk_columns", return_value=["id"]), \
                patch.object(tl, "get_min_max_pk_value", return_value=(1, 10)), \
                patch.object(tl, "get_table_partition_key", return_value="to_days(`created`)"), \
                patch.object(tl, "mysql_columns_by_data_type", return_value=[]), \
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
        return code, out.getvalue().splitlines(), [c.args[0] for c in sleep.call_args_list]


class TestScheduledJobRecheck(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.run_job = JobRun(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_difference_gone_on_recheck_logs_no_warning_and_matches(self):
        # As observed on 2026-10-01: equal counts, different checksums while rows
        # changed mid-scan; the next run matched.
        sides = {"shop.orders": {"mysql": [[MD5_A, 27368866]],
                                 "clickhouse": [[MD5_B, 27368866], [MD5_A, 27368866]]},
                 "shop.items": {"mysql": [[MD5_B, 3]], "clickhouse": [[MD5_B, 3]]}}
        (code, log, sleeps) = self.run_job(sides, JOB_FLAGS)
        self.assertEqual([line for line in log if "WARNING" in line], [], "\n".join(log))
        self.assertEqual(code, 0, "\n".join(log))
        self.assertEqual(sleeps, [60], "one re-check after the default 60 s delay")
        self.assertEqual(self.run_job.side_calls("orders", "mysql"), 2)
        self.assertEqual(self.run_job.side_calls("orders", "clickhouse"), 2)
        self.assertEqual(self.run_job.side_calls("items", "mysql"), 1, "a matching table is not re-checked")
        self.assertTrue(any(" - INFO - " in line and "Checksum mismatch on pass 1 of 2, checksumming again in 60 s"
                            in line and "'shop.orders'" in line for line in log), log)
        self.assertTrue(any(" - INFO - " in line and "Re-check of shop.orders: the mismatch of the earlier pass(es) "
                            "is gone on pass 2 of 2; verdict MATCH" in line for line in log), log)
        self.assertTrue(any("Run summary: 2 table(s) verified: 2 MATCH, 0 DIFFERENT, 0 EMPTY, 0 ERROR" in line
                            for line in log), log)

    def test_difference_seen_again_logs_exactly_one_warning(self):
        sides = {"shop.orders": {"mysql": [[MD5_A, 5]], "clickhouse": [[MD5_B, 5]]},
                 "shop.items": {"mysql": [[MD5_B, 3]], "clickhouse": [[MD5_B, 3]]}}
        (code, log, sleeps) = self.run_job(sides, JOB_FLAGS)
        warnings = [line for line in log if "WARNING" in line]
        self.assertEqual(len(warnings), 1, "\n".join(log))
        self.assertTrue(warnings[0].endswith(
            f" - Checksum difference : ('ch-host', 'shop.orders', '{MD5_B}', 5) to ('db1', 'shop.orders', '{MD5_A}', 5)"),
            warnings[0])
        self.assertEqual(code, 0, "a difference keeps exit 0; the job's WARNING scan fails it")
        self.assertEqual(sleeps, [60])
        self.assertEqual(self.run_job.side_calls("orders", "mysql"), 2)
        self.assertTrue(any("Run summary: 2 table(s) verified: 1 MATCH, 1 DIFFERENT, 0 EMPTY, 0 ERROR" in line
                            for line in log), log)

    def test_recheck_zero_reports_the_first_difference(self):
        sides = {"shop.orders": {"mysql": [[MD5_A, 5]], "clickhouse": [[MD5_B, 5], [MD5_A, 5]]},
                 "shop.items": {"mysql": [[MD5_B, 3]], "clickhouse": [[MD5_B, 3]]}}
        (code, log, sleeps) = self.run_job(sides, JOB_FLAGS + ["--recheck_differences", "0"])
        warnings = [line for line in log if "WARNING" in line]
        self.assertEqual(len(warnings), 1, "\n".join(log))
        self.assertIn(" - Checksum difference : ('ch-host', 'shop.orders'", warnings[0])
        self.assertEqual(sleeps, [])
        self.assertEqual(self.run_job.side_calls("orders", "clickhouse"), 1)

    def test_delay_and_count_are_honoured(self):
        sides = {"shop.orders": {"mysql": [[MD5_A, 5]],
                                 "clickhouse": [[MD5_B, 5], [MD5_B, 5], [MD5_A, 5]]},
                 "shop.items": {"mysql": [[MD5_B, 3]], "clickhouse": [[MD5_B, 3]]}}
        (code, log, sleeps) = self.run_job(sides, JOB_FLAGS + ["--recheck_differences", "2",
                                                               "--recheck_delay_seconds", "5"])
        self.assertEqual([line for line in log if "WARNING" in line], [], "\n".join(log))
        self.assertEqual(sleeps, [5, 5])
        self.assertEqual(self.run_job.side_calls("orders", "mysql"), 3)
        self.assertTrue(any("Checksum mismatch on pass 2 of 3, checksumming again in 5 s" in line for line in log), log)


class TestVerifyTableBothCopies(unittest.TestCase):
    """verify_table in the legacy (tl) and the packaged (pt) driver."""

    def results(self, ch_md5, mysql_md5=MD5_A, count=5):
        return [("db1", "shop.orders", mysql_md5, count), ("ch-host", "shop.orders", ch_md5, count)]

    def run_verify(self, module, passes, recheck_differences=1, delay=60):
        checksum = MagicMock(side_effect=passes)
        with patch.object(module.time, "sleep") as sleep, self.assertLogs(level="INFO") as logs:
            verdict = module.verify_table(checksum, "db1", ["ch-host"], "shop.orders", recheck_differences, delay)
        return verdict, checksum.call_count, [c.args[0] for c in sleep.call_args_list], logs.output

    def test_both_copies(self):
        for module in (tl, pt):
            with self.subTest(module=module.__name__):
                (verdict, calls, sleeps, output) = self.run_verify(
                    module, [self.results(MD5_B), self.results(MD5_A)])
                self.assertEqual((verdict, calls, sleeps), (module.VERDICT_MATCH, 2, [60]))
                self.assertFalse([line for line in output if line.startswith("WARNING")], output)

                (verdict, calls, sleeps, output) = self.run_verify(
                    module, [self.results(MD5_B), self.results(MD5_B)])
                self.assertEqual((verdict, calls, sleeps), (module.VERDICT_DIFFERENT, 2, [60]))
                self.assertEqual(len([line for line in output if line.startswith("WARNING")]), 1, output)

    def test_error_on_recheck_is_an_error(self):
        for module in (tl, pt):
            with self.subTest(module=module.__name__):
                (verdict, calls, _, _) = self.run_verify(module, [self.results(MD5_B), [None, None]])
                self.assertEqual((verdict, calls), (module.VERDICT_ERROR, 2))

    def test_match_on_first_pass_is_not_rechecked(self):
        for module in (tl, pt):
            with self.subTest(module=module.__name__):
                (verdict, calls, sleeps, _) = self.run_verify(module, [self.results(MD5_A)])
                self.assertEqual((verdict, calls, sleeps), (module.VERDICT_MATCH, 1, []))

    def test_negative_values_are_refused(self):
        for module in (tl, pt):
            with self.subTest(module=module.__name__):
                self.assertEqual(module.non_negative_int("0"), 0)
                with self.assertRaises(argparse.ArgumentTypeError):
                    module.non_negative_int("-1")
                with self.assertRaises(argparse.ArgumentTypeError):
                    module.non_negative_int("x")


if __name__ == "__main__":
    unittest.main()
