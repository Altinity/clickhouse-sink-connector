#!/usr/bin/env python3
"""Real SQLAlchemy result rows through the MySQL runners and the dumper (spec 13.06
D-13.06-9, spec 13.03 D-13.03-3).

The other offline tests hand the code plain dicts, which hid that a SQLAlchemy
2.x ``Row`` is a tuple: ``row['table_name']`` raised TypeError and every run
exited 1 before any table was compared or dumped (found end to end: the scheduled
job of sink-connector/python/tests_e2e/mysql on a fresh install.sh, which installs
SQLAlchemy 2.x). Here the rows are real SQLAlchemy results from an in-memory
SQLite database, so the installed SQLAlchemy major version is exercised (1.4 and
2.x both pass). No MySQL or ClickHouse is contacted. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_sqlalchemy_rows.py
"""
import argparse
import logging
import os
import sys
import unittest
from unittest.mock import MagicMock, patch

import pytest
from sqlalchemy import create_engine, text

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as legacy_driver  # noqa: E402
import ch_sink_tools.db_compare.top_level_table_checksum as packaged_driver  # noqa: E402
import db_compare.mysql_table_count as legacy_mysql_count  # noqa: E402
import ch_sink_tools.db_compare.mysql_table_count as packaged_mysql_count  # noqa: E402
import ch_sink_tools.db_compare.mysql_table_checksum as packaged_mysql_side  # noqa: E402
import ch_sink_tools.db.mysql as packaged_db_mysql  # noqa: E402
from db_dump import mysql_dumper as legacy_dumper  # noqa: E402
from ch_sink_tools.db_dump import mysql_dumper as packaged_dumper  # noqa: E402

MD5 = "0123456789abcdef0123456789abcdef"


@pytest.fixture
def sqlite_rows():
    """``rows(sql)`` returns a real SQLAlchemy CursorResult of the installed version."""
    engine = create_engine("sqlite://")
    conn = engine.connect()
    yield lambda sql: conn.execute(text(sql))
    conn.close()
    engine.dispose()


def side_output(db_table, md5, count):
    return f"2026-10-01 10:00:00,000 - INFO - T - Checksum for table {db_table} = {md5} count {count}\n".encode()


def run_driver(module, table_rows, extra_patches=(), **arg_values):
    """Run a driver's run_config over ``table_rows`` with only the catalog helpers and
    the side processes stubbed; return (exit code, log lines)."""
    values = dict(mysql_user=None, defaults_file="~/.my.cnf", mysql_database="shop", mysql_port=3306,
                  source_timezone="UTC", tables_regex=".", threads=1, binary_encoding="hex", where=None,
                  debug_output=False, lock_tables_on_source=False, sleep_after_lock=0, lock_wait_timeout=30,
                  fail_on_lock_timeout=False, no_wc=False, include_partitions_regex=None,
                  exclude_tables_regex=None, non_partitioned_tables_only=False,
                  include_floating_point_columns=False, include_json_columns=False, partition_date=None,
                  threads_per_table=1, fail_on_empty=False)
    values.update(arg_values)
    config = {"source": {"mysql": {"host": "db1"}}, "replicas": [{"clickhouse": {"host": "ch1"}}]}
    patches = [
        patch.object(module, "args", argparse.Namespace(**values), create=True),
        patch.object(module, "resolve_credentials_from_config", return_value=("u", "p")),
        patch.object(module, "get_mysql_connection", return_value=MagicMock()),
        patch.object(module, "get_tables_from_regexp", return_value=table_rows),
        patch.object(module, "mysql_pk_columns", return_value=["id"]),
        patch.object(module, "get_min_max_pk_value", return_value=(1, 10)),
        patch.object(module, "get_table_partition_key", return_value=None),
        patch.object(module, "run_quick_safe_command",
                     side_effect=lambda cmd: ("0", side_output("shop.orders", MD5, 3))),
    ] + list(extra_patches)
    for p in patches:
        p.start()
    try:
        with unittest.TestCase().assertLogs(level="INFO") as logs:
            try:
                module.run_config(config)
                code = None
            except SystemExit as exit_:
                code = exit_.code
        return code, logs.output
    finally:
        for p in reversed(patches):
            p.stop()


class TestDriversReadRealRows:
    """D-13.06-9: the drivers enumerate tables from real SQLAlchemy rows."""

    def test_legacy_driver_compares_a_table_listed_by_sqlalchemy(self, sqlite_rows):
        rows = sqlite_rows("select 'shop' as table_schema, 'orders' as table_name")
        code, logs = run_driver(legacy_driver, rows, extra_patches=[
            patch.object(legacy_driver, "resolve_source_timezone", return_value="UTC"),
            patch.object(legacy_driver, "mysql_columns_by_data_type", return_value=[])])
        assert code == 0, logs
        assert any("No difference for shop.orders" in line for line in logs), logs

    def test_packaged_driver_compares_a_table_listed_by_sqlalchemy(self, sqlite_rows):
        rows = sqlite_rows("select 'shop' as table_schema, 'orders' as table_name")
        code, logs = run_driver(packaged_driver, rows, extra_patches=[
            patch.object(packaged_driver, "mysql_json_columns", return_value=[])])
        assert code == 0, logs
        assert any("No difference for shop.orders" in line for line in logs), logs


@pytest.mark.parametrize("module", [legacy_mysql_count, packaged_mysql_count], ids=["legacy", "packaged"])
def test_mysql_count_builds_statements_from_real_rows(module, sqlite_rows):
    """D-13.06-9: the MySQL count reads partition rows by name."""
    rows = sqlite_rows("select 'shop' as table_schema, 'orders' as table_name, "
                       "NULL as partition_name, NULL as partition_expression")
    args = argparse.Namespace(mysql_database="shop", where=None, exclude_tables_regex=None,
                              include_partitions_regex=None, non_partitioned_tables_only=False)
    with patch.object(module, "args", args, create=True), \
            patch.object(module, "get_partitions_from_regex", return_value=rows):
        statements = module.select_table_statements(MagicMock(), "orders")
    assert [sql.strip() for (_, sql) in statements] == ["select count(*) from shop.orders"]


def test_packaged_mysql_side_reads_column_rows_by_name(sqlite_rows):
    """D-13.06-9: the packaged MySQL side builds its row expression from real rows."""
    rows = sqlite_rows("select 'id' as column_name, 'int' as data_type, 'NO' as is_nullable, "
                       "NULL as collation union all "
                       "select 'name', 'varchar(10)', 'YES', 'utf8mb4_0900_ai_ci'")
    packaged_mysql_side.args = argparse.Namespace(
        mysql_database="shop", min_date_value="1900-01-01", max_date_value="2299-12-31",
        min_datetime_value="1900-01-01 00:00:00", max_datetime_value="2299-12-31 23:59:59")
    with patch.object(packaged_mysql_side, "execute_mysql", return_value=(rows, -1)):
        (query, select, order_by, external) = packaged_mysql_side.get_table_checksum_query(
            "orders", MagicMock(), "hex", None, [], False, False)
    assert "`id`" in select and "`name`" in select, select


def test_packaged_partition_key_reads_rows_by_name(sqlite_rows):
    """D-13.06-9: the packaged get_table_partition_key reads partition rows by name."""
    rows = sqlite_rows("select 'shop' as table_schema, 'orders' as table_name, "
                       "'p2026' as partition_name, 'to_days(`created`)' as partition_expression")
    with patch.object(packaged_db_mysql, "get_partitions_from_regex", return_value=rows):
        assert packaged_db_mysql.get_table_partition_key(MagicMock(), "shop", "orders") == "to_days(`created`)"


@pytest.mark.parametrize("module", [legacy_dumper, packaged_dumper], ids=["legacy", "packaged"])
def test_dumper_selects_tables_from_real_rows(module, sqlite_rows):
    """D-13.03-3: the dumper resolves its table list from real SQLAlchemy rows."""
    tables = sqlite_rows("select 'appdb' as table_schema, 't1' as table_name union all select 'appdb', 't2'")
    partitions = sqlite_rows("select 'appdb' as table_schema, 't1' as table_name, NULL as partition_name, "
                             "NULL as partition_expression union all select 'appdb', 't2', NULL, NULL")
    args = argparse.Namespace(mysql_database="appdb", include_tables_regex=".", exclude_tables_regex=None,
                              non_partitioned_tables_only=False, include_partitions_regex=None,
                              partitioned_tables_only=False)
    with patch.object(module, "get_tables_from_regex", return_value=tables), \
            patch.object(module, "get_partitions_from_regex", return_value=partitions):
        (tables_to_dump, partition_map) = module.select_tables(MagicMock(), args)
    assert tables_to_dump == ["t1", "t2"]
    assert partition_map == {"appdb.t1": [], "appdb.t2": []}
