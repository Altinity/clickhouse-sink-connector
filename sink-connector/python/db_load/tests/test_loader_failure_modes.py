"""Failure modes of the ClickHouse loader's credential handling (spec 11.05 section 6).

Offline and runnable without the antlr4 runtime: the ANTLR-generated DDL
translator is replaced by a stub module while the loader is imported (the
code under test never translates DDL). No database is contacted; the failing
load is the shell builtin ``false``.

Run from sink-connector/python:  python3 -m pytest db_load/tests/test_loader_failure_modes.py
"""
import importlib
import logging
import os
import sys
import types
import unittest
from argparse import Namespace

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

SECRET = "s3cr3t-pw"


def import_loader(module_name, parser_module_name):
    """Import a loader module once. When the antlr4 runtime is missing, its ANTLR translator module is stubbed for
    the duration of the import only (the stub entry is removed again, so a later import of the real translator by
    another test is unaffected); when the runtime is present the real translator is used."""
    if module_name in sys.modules:
        return sys.modules[module_name]
    stub = None
    try:
        importlib.import_module(parser_module_name)
    except ImportError:
        stub = types.ModuleType(parser_module_name)
        stub.convert_to_clickhouse_table_antlr = lambda *a, **k: ("", [])
        sys.modules[parser_module_name] = stub
    try:
        return importlib.import_module(module_name)
    finally:
        if stub is not None and sys.modules.get(parser_module_name) is stub:
            del sys.modules[parser_module_name]


class TestLegacyLoaderFailurePath(unittest.TestCase):
    """db_load/clickhouse_loader.py -- the copy the spec 11.05 unit tests cover."""

    def setUp(self):
        self.cl = import_loader("db_load.clickhouse_loader", "db_load.mysql_parser.mysql_parser")
        self.cl.args = Namespace(dry_run=False)
        self.cl.register_secret(SECRET)

    def test_logged_command_is_redacted(self):
        with self.assertLogs(level="INFO") as logs, self.assertRaises(AssertionError):
            self.cl.execute_load(f"false --password '{SECRET}'")
        self.assertFalse(any(SECRET in line for line in logs.output), logs.output)

    def test_failure_message_is_redacted(self):
        with self.assertRaises(AssertionError) as raised:
            self.cl.execute_load(f"false --password '{SECRET}'")
        self.assertNotIn(SECRET, str(raised.exception))


class TestPackagedLoaderRedaction(unittest.TestCase):
    """ch_sink_tools/db_load/clickhouse_loader.py -- the copy ch-mysql-resync runs by default
    (``python -m ch_sink_tools.db_load.clickhouse_loader``)."""

    def setUp(self):
        self.cl = import_loader("ch_sink_tools.db_load.clickhouse_loader",
                                "ch_sink_tools.db_load.mysql_parser.mysql_parser")
        self.cl.args = Namespace(dry_run=True)

    def test_logged_command_is_redacted(self):
        with self.assertLogs(level="INFO") as logs:
            self.cl.execute_load(f"clickhouse-client --password '{SECRET}' --query 'select 1'")
        self.assertFalse(any(SECRET in line for line in logs.output), logs.output)

    def test_failure_message_is_redacted(self):
        self.cl.register_secret(SECRET)
        self.cl.args = Namespace(dry_run=False)
        with self.assertRaises(AssertionError) as raised:
            self.cl.execute_load(f"false --password '{SECRET}'")
        self.assertNotIn(SECRET, str(raised.exception))

    def test_password_with_shell_metacharacters_stays_one_word(self):
        """FM-11.05-1: the password was spliced as --password '<pw>', so a quote ended the word and the rest of
        the password ran as shell syntax. Through the helper both load paths use it is one shell word, and it is
        registered for redaction."""
        import shlex
        hostile = "x'; echo PWNED #"
        self.assertEqual(["--password", hostile], shlex.split(self.cl.shell_password_arg(hostile)))
        self.assertNotIn("PWNED", self.cl.redact_password("clickhouse-client " + self.cl.shell_password_arg(hostile)))
        self.assertEqual("", self.cl.shell_password_arg(None))
        self.assertEqual(["--config-file", "/a b/c.xml"],
                         shlex.split(self.cl.shell_config_file_arg("/a b/c.xml")))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    unittest.main()
