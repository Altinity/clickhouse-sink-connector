"""One implementation per tool (Spec 13.01 section 3.16, invariant I-13.01-1).

The legacy paths under db/, db_compare/, db_load/ and db_dump/ are compatibility
names for the ch_sink_tools modules. Importing a legacy name must give the SAME
module object, and a legacy file must contain no logic of its own -- two copies
drifted before (fixes landed on one side only), and this test is what stops a
second copy from growing back.

Run from sink-connector/python:  python -m pytest tests/test_single_source.py
"""
import ast
import importlib
import os
import subprocess
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # sink-connector/python
sys.path.insert(0, ROOT)

# legacy module name -> the one implementation
SHIMS = {
    "db.mysql": "ch_sink_tools.db.mysql",
    "db.clickhouse": "ch_sink_tools.db.clickhouse",
    "db.checksum_common": "ch_sink_tools.db.checksum_common",
    "db_compare.mysql_table_checksum": "ch_sink_tools.db_compare.mysql_table_checksum",
    "db_compare.clickhouse_table_checksum": "ch_sink_tools.db_compare.clickhouse_table_checksum",
    "db_compare.top_level_table_checksum": "ch_sink_tools.db_compare.top_level_table_checksum",
    "db_compare.mysql_table_count": "ch_sink_tools.db_compare.mysql_table_count",
    "db_compare.clickhouse_table_count": "ch_sink_tools.db_compare.clickhouse_table_count",
    "db_load.clickhouse_loader": "ch_sink_tools.db_load.clickhouse_loader",
    "db_load.mysql_resync": "ch_sink_tools.db_load.mysql_resync",
    "db_dump.mysql_dumper": "ch_sink_tools.db_dump.mysql_dumper",
    "db_load.mysql_parser.mysql_parser": "ch_sink_tools.db_load.mysql_parser.mysql_parser",
    "db_load.mysql_parser.CreateTableMySQLParserListener":
        "ch_sink_tools.db_load.mysql_parser.CreateTableMySQLParserListener",
}

# The statements a shim may contain: imports, the root on sys.path, the __main__
# dispatch and the sys.modules alias. A function or class definition is logic.
ALLOWED = (ast.Import, ast.ImportFrom, ast.Assign, ast.If, ast.Expr)


@pytest.mark.parametrize("legacy,implementation", sorted(SHIMS.items()))
def test_legacy_name_is_the_implementation(legacy, implementation):
    assert importlib.import_module(legacy) is importlib.import_module(implementation)


@pytest.mark.parametrize("legacy", sorted(SHIMS))
def test_legacy_file_holds_no_logic(legacy):
    path = os.path.join(ROOT, *legacy.split(".")) + ".py"
    tree = ast.parse(open(path).read())
    for node in tree.body:
        assert isinstance(node, ALLOWED), f"{path}:{node.lineno}: {type(node).__name__} -- a shim holds no logic"
    assert len(open(path).read().splitlines()) < 30, f"{path} grew beyond a shim"


@pytest.mark.parametrize("script", [
    "db_compare/top_level_table_checksum.py",
    "db_compare/mysql_table_checksum.py",
    "db_compare/clickhouse_table_checksum.py",
    "db_compare/mysql_table_count.py",
    "db_compare/clickhouse_table_count.py",
    "db_load/clickhouse_loader.py",
    "db_dump/mysql_dumper.py",
    "db_load/mysql_resync.py",
])
def test_legacy_launcher_runs_the_tool_without_pythonpath(script):
    """A legacy launcher started by path from another directory, with no PYTHONPATH, prints the tool's usage
    (before this, eight of the nine died with ModuleNotFoundError: No module named 'db' -- spec 13.01 R4)."""
    env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
    done = subprocess.run([sys.executable, os.path.join(ROOT, script), "--help"], cwd="/", env=env,
                          capture_output=True, text=True, timeout=120)
    assert done.returncode == 0, done.stdout + done.stderr
    assert "usage:" in done.stdout, done.stdout
