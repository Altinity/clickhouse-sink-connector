"""``--where`` of the MySQL dumper reaches the mysqlsh dump options or is refused (Spec 13.03 D-13.03-11).

Offline: only the generated mysqlsh script text is inspected, no mysqlsh and no database.

Run from sink-connector/python:  python -m pytest db_dump/tests/test_mysql_dumper_where.py
"""
import ast
import io
import os
import re
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

from db_dump import mysql_dumper as legacy_md  # noqa: E402
from ch_sink_tools.db_dump import mysql_dumper as pkg_md  # noqa: E402

COPIES = [pytest.param(legacy_md, id="legacy"), pytest.param(pkg_md, id="packaged")]


def clause(md, **overrides):
    values = dict(dump_dir="/dumps/d", dry_run=False, database="appdb", tables_to_dump=["orders", "items"],
                  data_only=False, schema_only=False, where=None, partition_map=None, threads=2,
                  bytes_per_chunk="64M", consistent=True)
    values.update(overrides)
    return md.generate_mysqlsh_dump_tables_clause(**values)


def options(text):
    """The options dict of the util.dumpTables(...) call."""
    return ast.literal_eval(re.search(r"(\{.*\})\s*\); $", text.strip() + " ", re.S).group(1))


@pytest.mark.parametrize("md", COPIES)
class TestWhereReachesMysqlsh:
    def test_where_applies_to_every_dumped_table(self, md):
        opts = options(clause(md, where="id > 5"))
        assert opts["where"] == {"appdb.orders": "id > 5", "appdb.items": "id > 5"}

    def test_without_where_the_option_is_absent(self, md):
        assert "where" not in options(clause(md))

    def test_clause_differs_with_and_without_where(self, md):
        assert clause(md, where="id > 5") != clause(md)

    def test_quotes_in_condition_survive(self, md):
        condition = """name = 'O''Brien' AND note <> "x" """
        assert options(clause(md, where=condition))["where"]["appdb.orders"] == condition.strip()

    def test_where_with_partitions(self, md):
        opts = options(clause(md, where="id > 5", partition_map={"appdb.orders": ["p0"]}))
        assert opts["partitions"] == {"appdb.orders": ["p0"]}
        assert opts["where"]["appdb.orders"] == "id > 5"

    @pytest.mark.parametrize("where", ["", "   "])
    def test_empty_condition_is_refused(self, md, where):
        with pytest.raises(ValueError, match="non-empty"):
            clause(md, where=where)

    def test_schema_only_with_where_is_refused(self, md):
        with pytest.raises(ValueError, match="schema_only"):
            clause(md, where="id > 5", schema_only=True)

    def test_where_without_tables_is_refused(self, md):
        with pytest.raises(ValueError, match="no table"):
            clause(md, where="id > 5", tables_to_dump=[])

    def test_where_reaches_the_script_file_of_the_mysqlsh_command(self, md):
        script = io.StringIO()
        script.name = "/tmp/script.js"
        cmd = md.generate_mysqlsh_command("/dumps/d", False, "src.example", "dumper", None, 3306, None, "appdb",
                                          ["orders"], False, False, "id > 5", None, 2, "64M", script)
        assert "'where': {'appdb.orders': 'id > 5'}" in script.getvalue()
        assert "-f /tmp/script.js" in cmd
