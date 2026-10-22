"""The loader and ch-mysql-resync refuse to run without an explicit --source_timezone (spec 11.02 section 3.2 step 1).

There is no UTC fallback and no server-zone guess: a run without the flag exits 2 before any connection is opened, and the
message names the flag and suggests a value. ch-mysql-resync refuses the same way and passes the flag to the loader.
"""
import os
import subprocess
import sys
from types import SimpleNamespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

from ch_sink_tools.db_load import mysql_resync as mr  # noqa: E402

LOADER_ARGS = ["--clickhouse_host", "ch-host", "--clickhouse_database", "d", "--mysql_source_database", "s",
               "--dump_dir", "/nonexistent-dump", "--clickhouse_config_file", "/nonexistent.xml",
               "--threads", "1", "--mysqlshell", "--data_only"]


def run_loader_cli(extra):
    return subprocess.run([sys.executable, "-m", "ch_sink_tools.db_load.clickhouse_loader"] + LOADER_ARGS + extra,
                          cwd=ROOT, capture_output=True, text=True, check=False)


def test_loader_refuses_without_source_timezone():
    result = run_loader_cli([])
    assert result.returncode == 2, result.stderr
    assert "--source_timezone is required" in result.stderr
    assert "America/Chicago" in result.stderr
    assert "no zone is assumed" in result.stderr


def test_loader_refuses_a_non_iana_source_timezone():
    result = run_loader_cli(["--source_timezone", "CST"])
    assert result.returncode == 2, result.stderr
    assert "IANA" in result.stderr


def test_resync_patch_refuses_without_source_timezone():
    args = SimpleNamespace(source_timezone=None, ch_config="/nonexistent.xml")
    with pytest.raises(SystemExit) as refused:
        mr.cmd_patch(args)
    assert "--source_timezone is required" in str(refused.value)
    assert "America/Chicago" in str(refused.value)


def test_resync_passes_source_timezone_to_the_loader(tmp_path):
    args = SimpleNamespace(loader_cmd=None, loader_cwd=None, ch_host="ch", ch_port=9000, ch_config="c.xml",
                           load_threads=1, apply=False, source_timezone="America/Chicago")
    with mock.patch.object(mr.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
        rc, _ = mr.run_loader(args, "restore_db", "schema", "table", str(tmp_path), str(tmp_path))
    cmd = run.call_args[0][0]
    assert rc == 0
    assert cmd[cmd.index("--source_timezone") + 1] == "America/Chicago"
