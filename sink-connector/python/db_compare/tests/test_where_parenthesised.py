"""The operator's --where text is one operand of every statement it is part of
(spec 11.02 section 3.11).

SQL binds AND tighter than OR. Text with a top-level OR that is joined bare to
another predicate hands that predicate to the last OR branch only, so a sign
filter, a chunk bound or a partition filter would not apply to the first
branch. Every builder that ANDs the text onto another predicate must emit it
as "(<text>)" first. These tests build the statements through the real
builders with a text that has an OR and assert that the text sits inside
parentheses and that the predicate composed onto it follows the closing one.
"""
import os
import re
import sys
import unittest
from datetime import datetime
from types import SimpleNamespace
from unittest import mock

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import pandas as pd  # noqa: E402

from ch_sink_tools.db import mysql as db_mysql  # noqa: E402
from ch_sink_tools.db_compare import clickhouse_table_checksum as ch  # noqa: E402
from ch_sink_tools.db_compare import clickhouse_table_count as ch_count  # noqa: E402
from ch_sink_tools.db_compare import mysql_table_checksum as my  # noqa: E402
from ch_sink_tools.db_compare import postgres_table_checksum as pg  # noqa: E402
from ch_sink_tools.db_compare import top_level_table_checksum as tl  # noqa: E402

WHERE = "a = 'x' or b = 'zz'"
OPERAND = "(a = 'x' or b = 'zz')"
PG_COLUMNS = [
    {"column_name": "id", "pg_type": "integer", "nullable": False, "udt_name": "int4"},
    {"column_name": "a", "pg_type": "text", "nullable": True, "udt_name": "text"},
]


def top_level_args(**overrides):
    values = dict(
        partition_date=datetime(2026, 9, 28), threads=8, threads_per_table=16,
        source_timezone="UTC", binary_encoding="base64",
        include_floating_point_columns=False, include_json_columns=False,
    )
    values.update(overrides)
    return SimpleNamespace(**values)


def where_word(command):
    """The argv word that follows ``--where`` in a command the top-level builds."""
    return command[command.index("--where") + 1]


class ChunkedAndUnchunkedSourceTests(unittest.TestCase):
    """MySQL side: the single-thread builder and the chunk divider."""

    def setUp(self):
        my.args = SimpleNamespace(mysql_host="my1", mysql_port=3306, mysql_database="db1")

    def side_where(self, chunk, pk):
        with mock.patch.object(my, "get_mysql_connection", return_value=object()), \
                mock.patch.object(my, "calculate_sql_checksum", return_value=None) as calc:
            my.calculate_checksum_single_thread("t1", "user", "pw", chunk, pk, WHERE, "", False, False)
        return calc.call_args[0][2]

    def test_chunked_statement_keeps_where_as_one_operand(self):
        where = self.side_where({"min_pk": 1, "max_pk": 10}, "id")
        self.assertIn(OPERAND + " and id between 1 and 10", where)
        self.assertNotIn("and " + WHERE, where)

    def test_unchunked_statement_keeps_where_as_one_operand(self):
        where = self.side_where({}, None)
        self.assertIn("and " + OPERAND, where)
        self.assertNotIn("and " + WHERE, where)

    def test_chunk_divider_queries_keep_where_as_one_operand(self):
        statements = []

        def fake_execute(conn, sql):
            statements.append(sql)
            if "min(" in sql:
                return pd.DataFrame({"min_pk": [1], "max_pk": [10]})
            if sql.lstrip().lower().startswith("explain"):
                return pd.DataFrame({"rows": [6]})
            return pd.DataFrame({"id": [1]})

        with mock.patch.object(db_mysql, "mysql_execute_df", side_effect=fake_execute):
            chunks = list(db_mysql.divide_table_into_even_chunks(object(), "t1", 3, "id", WHERE))
        bounded = [sql for sql in statements if "between" in sql]
        self.assertTrue(chunks)
        self.assertTrue(bounded)
        for sql in bounded:
            self.assertIn("where " + OPERAND + " and id between", sql)


class ClickHouseReplicaTests(unittest.TestCase):
    """ClickHouse side: the statement builder that appends the sign filter."""

    def setUp(self):
        ch.args = SimpleNamespace(clickhouse_database="db1", debug_limit=None,
                                  max_memory_usage=None, debug_output=False)

    def test_sign_filter_follows_the_parenthesised_where(self):
        statements = ch.select_table_statements(
            "t1", "", "select_expr", "", "", WHERE, "0", False, "sign")
        self.assertTrue(any(OPERAND + " and sign > 0" in s for s in statements), statements)
        self.assertFalse(any("and " + WHERE in s for s in statements), statements)


class TopLevelDriverTests(unittest.TestCase):
    """Top-level driver: the source and replica command builders, the
    consistent-snapshot slice filter and the per-table override."""

    def setUp(self):
        tl.args = top_level_args()

    def test_source_command_keeps_where_as_one_operand(self):
        cmd = tl.get_mysql_checksum_command("my1", "db1", "t1", "id", 10, where=WHERE)
        word = where_word(cmd)
        self.assertIn("and " + OPERAND, word)
        self.assertNotIn("and " + WHERE, word)

    def test_replica_command_partition_filter_follows_the_operand(self):
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10, where=WHERE,
                                                 partition_key="trade_date")
        self.assertRegex(where_word(cmd), r"\(a = 'x' or b = 'zz'\)\s+and \{partition_expression\}=toDate")

    def test_replica_side_command_without_extra_filter(self):
        _, replicas = tl.side_commands("db1", {}, "t1", "my1", ["ch1"], "id", 10, WHERE)
        self.assertIn("and " + OPERAND, where_word(replicas[0][1]))

    def test_replica_side_command_with_extra_filter(self):
        _, replicas = tl.side_commands("db1", {}, "t1", "my1", ["ch1"], "id", 10, WHERE,
                                       ch_extra_where="_version > 3")
        self.assertIn(OPERAND + " and _version > 3", where_word(replicas[0][1]))

    def test_slice_filter_keeps_where_as_one_operand(self):
        result = tl.mysql_where_for_slicing(WHERE, "trade_date")
        self.assertIn("and " + OPERAND, result)
        self.assertNotIn("and " + WHERE, result)

    def test_table_override_joined_to_user_where_keeps_both_as_operands(self):
        result = tl.table_where("db1.t1", WHERE, {"db1.t1": {"where": "c = 1 or d = 2"}})
        self.assertIn(OPERAND, result)
        self.assertIn("(c = 1 or d = 2)", result)


class PostgresChunkTests(unittest.TestCase):
    """PostgreSQL checksum: the tier-1 and tier-2 chunk queries."""

    def test_tier2_chunk_query_keeps_where_as_one_operand(self):
        sql = pg.build_tier2_chunk_query("t1", "s1", "id", 1, 10, where=WHERE)
        self.assertIn(OPERAND + ' AND "id" BETWEEN 1 AND 10', sql)

    def test_tier1_chunk_query_keeps_where_as_one_operand(self):
        sql = pg.build_tier1_chunk_query("t1", "s1", PG_COLUMNS, "id", 1, 10, where=WHERE)
        self.assertIn(OPERAND + ' AND "id" BETWEEN 1 AND 10', sql)


class CountToolTests(unittest.TestCase):
    """Standalone ClickHouse count: the user where is added to the base predicate."""

    def test_count_where_is_one_operand(self):
        ch_count.args = SimpleNamespace(ignore_tables_regex=None, include_partitions_regex=None,
                                        clickhouse_database="db1", where=WHERE)
        captured = []

        def fake_execute(conn, sql):
            captured.append(sql)
            return ([[0]], 1)

        with mock.patch.object(ch_count, "get_connection", return_value=mock.Mock()), \
                mock.patch.object(ch_count, "execute_sql", side_effect=fake_execute):
            ch_count.calculate_table_count("t1", None, "user", "pw")
        self.assertTrue(captured)
        self.assertIn("where 1=1 and " + OPERAND, captured[0])
        self.assertNotIn("and " + WHERE, captured[0])


if __name__ == "__main__":
    unittest.main()
