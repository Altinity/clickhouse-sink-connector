"""The two checksum side tools log every line once, through the handler they install (spec 13.06 section 3.17).

The first logging call made while the root logger has no handler installs a second, stderr one
(``INFO:root:...``), so every later line is printed twice and a manual recipe sees two ``Checksum
for table`` lines. The in-process tests cannot see it (assertLogs installs a handler first), so the
tools are started as the operator starts them, against a port nothing listens on. Run from
sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_cli_logging.py
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest

PYTHON_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ROOT_FORMAT = re.compile(r"^(DEBUG|INFO|WARNING|ERROR|CRITICAL):[^:\s]+:")
ZONE_LINE = re.compile(r" - INFO - MainThread - source time zone: UTC \(source=flag\)$")


def run_tool(script, args, workdir):
    environment = dict(os.environ, PYTHONPATH=PYTHON_ROOT)
    return subprocess.run([sys.executable, os.path.join(PYTHON_ROOT, "db_compare", script), *args],
                          capture_output=True, text=True, timeout=180, env=environment, cwd=workdir)


class TestCliLogsEachLineOnce(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, text):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def assert_logged_once(self, result):
        root_lines = [line for line in result.stderr.splitlines() if ROOT_FORMAT.match(line)]
        self.assertEqual(root_lines, [], "a second, stderr handler printed the log again:\n" + result.stderr)
        zone_lines = [line for line in result.stdout.splitlines() if ZONE_LINE.search(line)]
        self.assertEqual(len(zone_lines), 1, "the zone the run compares in is logged once:\n" + result.stdout)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_clickhouse_side_logs_each_line_once(self):
        config = self.write("clickhouse-client.xml", "<clickhouse><user>u</user><password>p</password></clickhouse>")
        result = run_tool("clickhouse_table_checksum.py",
                          ["--clickhouse_host", "127.0.0.1", "--clickhouse_port", "1", "--clickhouse_database", "d",
                           "--tables_regex", "t", "--no_wc", "--source_timezone", "UTC", "--clickhouse_config_file", config],
                          self.tmp.name)
        self.assert_logged_once(result)

    def test_mysql_side_logs_each_line_once(self):
        defaults = self.write(".my.cnf", "[client]\nuser=u\npassword=p\n")
        result = run_tool("mysql_table_checksum.py",
                          ["--mysql_host", "127.0.0.1", "--mysql_port", "1", "--mysql_database", "d", "--tables_regex", "t",
                           "--no_wc", "--source_timezone", "UTC", "--defaults_file", defaults],
                          self.tmp.name)
        self.assert_logged_once(result)


if __name__ == "__main__":
    unittest.main()
