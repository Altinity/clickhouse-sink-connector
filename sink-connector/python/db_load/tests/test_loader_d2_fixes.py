"""Regression tests for the loader defects of Spec 13.04 section 8 (D-13.04-40..44), run against BOTH import names.

Offline: no database, no network, no ClickHouse server. Driver connections and ``execute_load`` are mocked; the CSV
unescape filter is run for real through the shell, as the load pipeline runs it.

Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_d2_fixes.py
"""
import concurrent.futures
import importlib
import os
import shutil
import subprocess
import sys
import threading
import time
from argparse import Namespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

COPIES = ["db_load.clickhouse_loader", "ch_sink_tools.db_load.clickhouse_loader"]


@pytest.fixture(params=COPIES)
def loader(request):
    return importlib.import_module(request.param)


def loader_args(dump_dir, **overrides):
    values = dict(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                  clickhouse_database="target_ch", clickhouse_config_file=None, clickhouse_user="loader",
                  clickhouse_password=None, mysql_source_database="srcdb", dump_dir=str(dump_dir), threads=2,
                  truncate_tables=False, dry_run=False, mysqlshell=True, rmt_delete_support=True,
                  use_regexp_parser=False, virtual_columns=['`_sign`', '`_version`', '`is_deleted`', '`_is_deleted`'],
                  binary_handling_mode="bytes", persist_raw_bytes=False, source_timezone="UTC")
    values.update(overrides)
    return Namespace(**values)


COLUMN = {"column_name": "`id`", "datatype": "Int32", "mysql_datatype": "int", "nullable": False,
          "generated": False, "source_column": True}


def mysqlshell_dump(dump_dir, table="t1"):
    (dump_dir / f"srcdb@{table}.sql").write_text(f"CREATE TABLE `{table}` (`id` int NOT NULL) ENGINE=InnoDB;\n")
    (dump_dir / f"srcdb@{table}@@0.tsv.zst").write_bytes(b"")


# ------------------------------------------------------------------------------ (a) --truncate_tables target database

class TestTruncateTargetsClickHouseDatabase:
    def run_load(self, loader, tmp_path, **overrides):
        mysqlshell_dump(tmp_path)
        statements = []
        args = loader_args(tmp_path, **{"truncate_tables": True, **overrides})
        with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
                mock.patch.object(loader, "clickhouse_execute_conn",
                                  lambda conn, sql: statements.append(sql)), \
                mock.patch.object(loader, "execute_load", mock.MagicMock()) as execute_load:
            loader.load_data_mysqlshell(args, "UTC", {"srcdb.t1": [COLUMN]}, "loader", None)
        return statements, execute_load

    def test_truncates_clickhouse_database_not_mysql_source_schema(self, loader, tmp_path):
        statements, _ = self.run_load(loader, tmp_path)
        assert statements == ["TRUNCATE TABLE `target_ch`.`t1`"]
        assert not any("srcdb" in s for s in statements)

    def test_dry_run_truncates_nothing(self, loader, tmp_path):
        statements, execute_load = self.run_load(loader, tmp_path, dry_run=True)
        assert statements == []
        assert execute_load.call_count == 1  # the load itself is still planned (execute_load honours dry_run)

    def test_without_flag_nothing_is_truncated(self, loader, tmp_path):
        statements, _ = self.run_load(loader, tmp_path, truncate_tables=False)
        assert statements == []

    def test_insert_and_truncate_name_the_same_database(self, loader, tmp_path):
        statements, execute_load = self.run_load(loader, tmp_path)
        insert = execute_load.call_args[0][0]
        assert "INSERT INTO target_ch.t1(" in insert
        assert "`target_ch`" in statements[0]

    def test_statement_quotes_both_names(self, loader):
        assert loader.truncate_table_statement("my-db", "order") == "TRUNCATE TABLE `my-db`.`order`"

    def test_statement_escapes_backticks(self, loader):
        assert loader.truncate_table_statement("d`b", "t`x") == "TRUNCATE TABLE `d\\`b`.`t\\`x`"


# ----------------------------------------------------------------------------------------- (b) dependency check

class TestCheckDependencies:
    def present(self, *names):
        return lambda name: name in names

    def test_mydumper_mode_does_not_need_zstd(self, loader):
        with mock.patch.object(loader, "check_program_exists", self.present("clickhouse-client")):
            loader.check_dependencies(Namespace(mysqlshell=False))  # used to raise for every mydumper run

    def test_mysqlshell_mode_needs_zstd(self, loader):
        with mock.patch.object(loader, "check_program_exists", self.present("clickhouse-client")):
            with pytest.raises(AssertionError, match="zstd"):
                loader.check_dependencies(Namespace(mysqlshell=True))

    def test_mysqlshell_mode_with_zstd_passes(self, loader):
        with mock.patch.object(loader, "check_program_exists", self.present("clickhouse-client", "zstd")):
            loader.check_dependencies(Namespace(mysqlshell=True))

    @pytest.mark.parametrize("mysqlshell", [True, False])
    def test_clickhouse_client_is_always_needed(self, loader, mysqlshell):
        with mock.patch.object(loader, "check_program_exists", self.present("zstd")):
            with pytest.raises(AssertionError, match="clickhouse-client"):
                loader.check_dependencies(Namespace(mysqlshell=mysqlshell))


# ------------------------------------------------------------------------------ (c) mydumper escape -> CSV quoting

def run_filter(loader, data: bytes) -> bytes:
    return subprocess.run(["sh", "-c", loader.mydumper_csv_unescape_filter()], input=data, capture_output=True,
                          check=True).stdout


class TestMydumperCsvUnescape:
    @pytest.mark.parametrize("raw, expected", [
        (rb'"a\"b",x', rb'"a""b",x'),                      # escaped quote -> CSV doubled quote
        (rb"it\'s", b"it's"),                              # escaped apostrophe -> apostrophe
        (rb'"abc\\",x', rb'"abc\\",x'),                    # escaped backslash then the closing quote: untouched
        (rb'"abc\\\"",x', rb'"abc\\""",x'),                # \\ kept, then \" -> "" and the real closing quote
        (rb'"\"\"",1', rb'"""""",1'),                      # adjacent escaped quotes are both converted
        (rb"a\\'b", rb"a\\'b"),                            # escaped backslash before an apostrophe stays as is
        (rb'"\\\\",x', rb'"\\\\",x'),                      # two escaped backslashes
        (rb'plain,"text",1', rb'plain,"text",1'),
        (rb'\N,1', rb'\N,1'),                              # NULL marker: other escapes are kept
        (rb'"a\n",1', rb'"a\n",1'),
    ])
    def test_unescape(self, loader, raw, expected):
        assert run_filter(loader, raw + b"\n") == expected + b"\n"

    def test_lines_are_independent_and_trailing_backslash_is_kept(self, loader):
        data = b'"a\\"\n"b\\\\",1\n"c\\\n'
        assert run_filter(loader, data) == b'"a""\n"b\\\\",1\n"c\\\n'

    def test_non_utf8_bytes_pass_through(self, loader):
        data = b'"\xff\xfe\\"",1\n'
        assert run_filter(loader, data) == b'"\xff\xfe""",1\n'

    def test_old_sed_corrupted_the_escaped_backslash_field(self):
        # documents the defect: sed read the second backslash of \\ as an escape of the closing quote
        if shutil.which("sed") is None:
            pytest.skip("sed not installed")
        out = subprocess.run(["sed", "-e", r's/\\"/""/g'], input=b'"abc\\\\",x\n', capture_output=True).stdout
        assert out == b'"abc\\"",x\n'

    def test_mydumper_load_pipeline_uses_the_filter(self, loader, tmp_path):
        (tmp_path / "srcdb.t1-schema.sql.gz").write_bytes(b"")
        (tmp_path / "srcdb.t1.00000.dat.gz").write_bytes(b"")
        commands = []
        args = loader_args(tmp_path, mysqlshell=False)
        with mock.patch.object(loader, "execute_load", commands.append):
            loader.load_data(args, "UTC", {})
        assert len(commands) == 1
        assert loader.mydumper_csv_unescape_filter() in commands[0]
        assert "sed -e" not in commands[0]
        assert commands[0].index("gunzip") < commands[0].index(sys.executable) < commands[0].index("clickhouse-client")


# --------------------------------------------------------------------------------- (d) no schema files -> clear error

class TestNoSchemaFiles:
    def test_mysqlshell_schema_load_raises_a_clear_error_not_none(self, loader, tmp_path):
        args = loader_args(tmp_path)
        with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
                mock.patch.object(loader, "clickhouse_execute_conn", mock.MagicMock()):
            with pytest.raises(FileNotFoundError, match="srcdb@"):
                loader.load_schema(args, dry_run=True)

    def test_unpacking_never_sees_none(self, loader, tmp_path):
        args = loader_args(tmp_path)
        with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
                mock.patch.object(loader, "clickhouse_execute_conn", mock.MagicMock()):
            try:
                (timezone, schema_map) = loader.load_schema(args, dry_run=True)
            except FileNotFoundError:
                return
            pytest.fail(f"expected FileNotFoundError, got {(timezone, schema_map)!r}")

    def test_dump_with_schema_file_still_loads(self, loader, tmp_path):
        mysqlshell_dump(tmp_path)
        args = loader_args(tmp_path)
        with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
                mock.patch.object(loader, "clickhouse_execute_conn", mock.MagicMock()):
            (timezone, schema_map) = loader.load_schema(args, dry_run=True)
        assert timezone == "UTC" and "srcdb.t1" in schema_map


# --------------------------------------------------------------------------------- (e) first failure surfaces promptly

class TestWaitForLoads:
    def test_first_failure_cancels_the_queued_loads(self, loader):
        started = []
        release = threading.Event()

        def fail():
            raise AssertionError("load 0 failed")

        def slow(n):
            started.append(n)
            release.wait(5)

        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            futures = [executor.submit(fail)] + [executor.submit(slow, n) for n in range(1, 6)]
            try:
                with pytest.raises(AssertionError, match="load 0 failed"):
                    loader.wait_for_loads(futures)
            finally:
                release.set()
        # at most the one load the worker had already picked up ran; the rest never started
        assert len(started) <= 1
        assert sum(f.cancelled() for f in futures) >= 4

    def test_error_is_raised_before_slow_loads_finish(self, loader):
        release = threading.Event()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            blocker = executor.submit(release.wait, 5)

            def fail():
                raise ValueError("boom")

            futures = [blocker, executor.submit(fail)]
            started = time.monotonic()
            try:
                with pytest.raises(ValueError, match="boom"):
                    loader.wait_for_loads(futures)
                assert time.monotonic() - started < 2.5
            finally:
                release.set()

    def test_success_returns_quietly(self, loader):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            loader.wait_for_loads([executor.submit(lambda: None) for _ in range(3)])

    def test_load_data_mysqlshell_raises_the_first_failure_and_skips_later_tables(self, loader, tmp_path):
        for table in ("t1", "t2", "t3", "t4"):
            mysqlshell_dump(tmp_path, table)
        schema_map = {f"srcdb.{t}": [COLUMN] for t in ("t1", "t2", "t3", "t4")}
        ran = []

        def execute_load(cmd):
            ran.append(cmd)
            raise AssertionError("load failed")

        args = loader_args(tmp_path, threads=1)
        with mock.patch.object(loader, "execute_load", execute_load):
            with pytest.raises(AssertionError, match="load failed"):
                loader.load_data_mysqlshell(args, "UTC", schema_map, "loader", None)
        assert len(ran) < 4
