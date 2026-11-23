"""Offline tests for the checksum tool hardening (spec 11.02, review findings
CS-2 .. CS-12 and the Postgres checksum fixes).

Every test stubs the database layer; no test opens a connection. Each class
names the finding it pins and the behaviour the unfixed code had.
"""
import argparse
import os
import re
import sqlite3
import sys
import unittest
from decimal import Decimal
from pathlib import Path
from unittest import mock

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import pandas as pd  # noqa: E402

from ch_sink_tools.db import mysql as db_mysql  # noqa: E402
from ch_sink_tools.db.checksum_common import (  # noqa: E402
    DATETIME_MAX, DATETIME_MIN, checksum_from_aggregate,
)
from ch_sink_tools.db_compare import _sql_text  # noqa: E402
from ch_sink_tools.db_compare import clickhouse_table_checksum as ch  # noqa: E402
from ch_sink_tools.db_compare import clickhouse_table_count as ch_count  # noqa: E402
from ch_sink_tools.db_compare import mysql_table_checksum as my  # noqa: E402
from ch_sink_tools.db_compare import postgres_table_checksum as pg  # noqa: E402
from ch_sink_tools.db_compare import top_level_postgres_checksum as tlp  # noqa: E402
from ch_sink_tools.db_compare import top_level_table_checksum as tl  # noqa: E402

PYTHON_ROOT = Path(__file__).resolve().parents[2]


def clickhouse_args(**overrides):
    values = dict(
        clickhouse_host="clickhouse-host", clickhouse_database="db1", clickhouse_port=9000, secure=False,
        sign_column="", tables_regex=".", where=None, order_by=None, partition_key=None, ignore_tables_regex=None,
        no_wc=False, debug_output=False, debug_limit=None, hex_columns=[], debug=False,
        exclude_columns=["_sign,_version,is_deleted,_is_deleted,__is_deleted"], threads=1,
        min_datetime_value=DATETIME_MIN, max_datetime_value=DATETIME_MAX, max_memory_usage=None,
        include_floating_point_columns=False, include_json_columns=False, source_timezone="UTC",
        timestamp_columns="", binary_encoding="base64", json_columns="", source_columns="",
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def mysql_args(**overrides):
    values = dict(
        mysql_host="mysql-host", mysql_user="u", mysql_password="p", defaults_file=None, mysql_database="db1",
        mysql_port=3306, tables_regex=".", where=None, order_by=None, ignore_tables_regex=None, no_wc=False,
        debug_output=False, debug_limit=None, binary_encoding="base64", min_date_value="1900-01-01",
        max_date_value="2299-12-31", min_datetime_value=DATETIME_MIN, max_datetime_value=DATETIME_MAX,
        debug=False, exclude_columns=[], threads_per_table=1, chunk_size=10000, threads=1,
        include_floating_point_columns=False, include_json_columns=False, source_timezone="UTC",
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def mysql_column(name, data_type, column_type=None, nullable="NO", collation=None):
    return {"column_name": name, "data_type": data_type, "column_type": column_type or data_type,
            "is_nullable": nullable, "collation": collation, "datetime_precision": None}


def clickhouse_row_expression(rows, **overrides):
    """(select, skipped) of ``build_clickhouse_row_expression`` for (name, type, nullable, scale) rows."""
    options = clickhouse_args(**overrides)
    (select, nullables, columns, data_types, clamped, skipped) = ch.build_clickhouse_row_expression(rows, options)
    return (select, skipped)


# ---------------------------------------------------------------------------
# CS-2 / NEW-P2: ClickHouse types are read by base name, never by substring
# ---------------------------------------------------------------------------
class TestClickHouseTypeClassification(unittest.TestCase):

    ENUM_TYPES = [
        "Enum8('Float' = 1, 'JSON' = 2)",
        "Enum16('Bool' = 1, 'DateTime' = 2, 'Decimal' = 3, 'String' = 4)",
        "Nullable(Enum8('Float' = 1, 'JSON' = 2))",
        "LowCardinality(Nullable(Enum8('Nullable' = 1)))",
    ]

    def test_base_type_unwraps_nullable_and_low_cardinality(self):
        self.assertEqual(ch.base_type("Nullable(String)"), "String")
        self.assertEqual(ch.base_type("LowCardinality(Nullable(String))"), "String")
        self.assertEqual(ch.base_type("Nullable(Decimal(10, 2))"), "Decimal(10, 2)")
        self.assertEqual(ch.base_type("Array(Nullable(String))"), "Array(Nullable(String))")
        self.assertEqual(ch.base_type("Enum8('Nullable(x)' = 1)"), "Enum8('Nullable(x)' = 1)")

    def test_enum_labels_do_not_classify_the_column(self):
        for data_type in self.ENUM_TYPES:
            with self.subTest(data_type=data_type):
                self.assertTrue(ch.is_enum_type(data_type))
                for predicate in (ch.is_float_type, ch.is_json_type, ch.is_bool_type, ch.is_decimal_type,
                                  ch.is_datetime_type, ch.is_string_type):
                    self.assertFalse(predicate(data_type), predicate.__name__)

    def test_enum_column_is_compared_as_text_not_skipped_or_rewritten(self):
        for data_type in self.ENUM_TYPES:
            with self.subTest(data_type=data_type):
                (select, skipped) = clickhouse_row_expression([("kind", data_type, 0, None)])
                self.assertEqual(skipped, {"floating point": [], "JSON": []})
                self.assertIn('toString("kind")', select)
                self.assertNotIn("toDecimalString", select)
                self.assertNotIn("toDateTime64", select)
                self.assertNotIn("toUInt8", select)

    def test_enum_column_is_not_taken_for_raw_bytes_or_char_text(self):
        (select, _) = clickhouse_row_expression(
            [("kind", "Enum8('String' = 1)", 0, None)], hex_columns=["kind"], binary_encoding="raw", char_columns="kind")
        self.assertNotIn("hex(", select)
        self.assertNotIn("rtrim", select.lower())

    def test_real_types_are_still_recognised_through_wrappers(self):
        self.assertTrue(ch.is_float_type("Nullable(Float64)"))
        self.assertTrue(ch.is_float_type("Float32"))
        self.assertTrue(ch.is_json_type("JSON"))
        self.assertTrue(ch.is_json_type("Nullable(JSON)"))
        self.assertTrue(ch.is_bool_type("Nullable(Bool)"))
        self.assertTrue(ch.is_decimal_type("Decimal(10, 2)"))
        self.assertTrue(ch.is_decimal_type("Nullable(Decimal64(4))"))
        self.assertTrue(ch.is_decimal_type("Decimal128(6)"))
        self.assertTrue(ch.is_datetime_type("DateTime64(6, 'UTC')"))
        self.assertTrue(ch.is_datetime_type("Nullable(DateTime)"))
        self.assertTrue(ch.is_string_type("FixedString(8)"))
        self.assertTrue(ch.is_string_type("LowCardinality(String)"))
        self.assertFalse(ch.is_datetime_type("Date32"))
        self.assertFalse(ch.is_string_type("Array(String)"))

    def test_float_and_json_columns_are_still_skipped_by_default(self):
        (select, skipped) = clickhouse_row_expression(
            [("id", "Int32", 0, None), ("f", "Nullable(Float64)", 1, None), ("j", "JSON", 0, None),
             ("name", "String", 0, None)])
        self.assertEqual(skipped, {"floating point": ["f"], "JSON": ["j"]})
        self.assertNotIn('"f"', select)
        self.assertNotIn('"j"', select)

    def test_nullable_flag_is_read_from_the_start_of_the_type(self):
        """match(type,'Nullable') hit an Enum8('Nullable' = 1) and made it Nullable."""
        executed = []

        def execute_sql(conn, sql):
            executed.append(sql)
            if "from system.columns" in sql and "is_in_primary_key" not in sql:
                return ([("kind", "Enum8('Nullable' = 1)", 0, None, 0, 0)], 1)
            if "is_in_primary_key" in sql:
                return ([], 0)
            raise AssertionError(sql)

        ch.args = clickhouse_args()
        with mock.patch.object(ch, "execute_sql", side_effect=execute_sql):
            ch.get_table_checksum_query(mock.Mock(), "t")
        catalog = [sql for sql in executed if "is_nullable" in sql][0]
        self.assertNotIn("match(type,'Nullable')", catalog)
        self.assertIn("startsWith(type,'Nullable(')", catalog)


class TestNullableDecimalScale(unittest.TestCase):
    """NEW-P2: numeric_scale is NULL for Nullable(Decimal(..)); the scale is in the type text."""

    def test_scale_comes_from_the_catalog_when_present(self):
        self.assertEqual(ch.decimal_scale("Decimal(10, 2)", 2), 2)
        self.assertEqual(ch.decimal_scale("Decimal(10, 2)", Decimal(3)), 3)

    def test_scale_is_parsed_from_the_type_when_the_catalog_has_none(self):
        self.assertEqual(ch.decimal_scale("Nullable(Decimal(10, 2))", None), 2)
        self.assertEqual(ch.decimal_scale("Decimal(38,0)", None), 0)
        self.assertEqual(ch.decimal_scale("Nullable(Decimal64(4))", None), 4)
        self.assertEqual(ch.decimal_scale("LowCardinality(Nullable(Decimal(9, 6)))", None), 6)

    def test_unparseable_scale_raises_instead_of_emitting_none(self):
        with self.assertRaises(ValueError):
            ch.decimal_scale("Decimal", None)

    def test_nullable_decimal_expression_has_a_numeric_scale(self):
        (select, _) = clickhouse_row_expression([("price", "Nullable(Decimal(10, 2))", 1, None)])
        self.assertIn('toDecimalString("price",2)', select)
        self.assertNotIn("None", select)


# ---------------------------------------------------------------------------
# CS-3: {partition_expression} on a table with no partition expression
# ---------------------------------------------------------------------------
class TestPartitionPlaceholder(unittest.TestCase):

    def test_substitutes_a_present_expression(self):
        self.assertEqual(_sql_text.substitute_partition_expression("{partition_expression}=1", "to_days(d)", "db.t"),
                         "to_days(d)=1")

    def test_a_where_without_the_placeholder_is_untouched_whatever_the_key(self):
        self.assertEqual(_sql_text.substitute_partition_expression("a=1", None, "db.t"), "a=1")
        self.assertEqual(_sql_text.substitute_partition_expression(None, None, "db.t"), None)

    def test_missing_expression_raises_naming_the_table(self):
        for missing in (None, "", "   "):
            with self.subTest(missing=missing):
                with self.assertRaises(ValueError) as context:
                    _sql_text.substitute_partition_expression("{partition_expression} >= 1", missing, "db.t")
                self.assertIn("db.t", str(context.exception))
                self.assertIn("{partition_expression}", str(context.exception))

    def test_mysql_side_never_sends_an_unresolved_placeholder(self):
        my.args = mysql_args(where="{partition_expression} >= 1")
        with mock.patch.object(my, "mysql_pk_columns", return_value=[]), \
                mock.patch.object(my, "get_table_partition_key", return_value=None), \
                mock.patch.object(my, "divide_table_into_even_chunks") as chunks:
            with self.assertRaises(ValueError) as context:
                my.collect_chunk_results(mock.Mock(), "t", "u", "p", [], False, False)
        self.assertIn("db1.t", str(context.exception))
        chunks.assert_not_called()

    def test_mysql_side_substitutes_a_partitioned_table(self):
        my.args = mysql_args(where="{partition_expression} >= 1")
        seen = []
        with mock.patch.object(my, "mysql_pk_columns", return_value=[]), \
                mock.patch.object(my, "get_table_partition_key", return_value="to_days(`d`)"), \
                mock.patch.object(my, "divide_table_into_even_chunks", side_effect=lambda c, t, size, pk, where: seen.append(where) or iter([])):
            my.collect_chunk_results(mock.Mock(), "t", "u", "p", [], False, False)
        self.assertEqual(seen, ["to_days(`d`) >= 1"])

    def test_clickhouse_side_raises_for_a_table_with_no_partition_key_and_closes_its_connection(self):
        ch.args = clickhouse_args()
        conn = mock.MagicMock()
        for rows in ([], [("",)]):
            with self.subTest(rows=rows):
                conn.reset_mock()
                with mock.patch.object(ch, "get_connection", return_value=conn), \
                        mock.patch.object(ch, "get_table_partition_key", return_value=rows):
                    with self.assertRaises(ValueError) as context:
                        ch.calculate_checksum("t", "u", "p", "{partition_expression} = toDate('2026-09-28')", None)
                self.assertIn("db1.t", str(context.exception))
                conn.close.assert_called()


# ---------------------------------------------------------------------------
# CS-4 / CS-5 / CS-12 (driver): include list, ignored columns, database override
# ---------------------------------------------------------------------------
class TestTableIncludeList(unittest.TestCase):

    def test_pattern_must_match_the_whole_table_name(self):
        include = ["shop.orders"]
        self.assertTrue(tl.match_table_include_list("shop", "orders", include))
        self.assertFalse(tl.match_table_include_list("shop", "orders_archive", include))
        self.assertFalse(tl.match_table_include_list("shop", "old_orders", include))

    def test_regex_patterns_keep_working(self):
        self.assertTrue(tl.match_table_include_list("shop", "orders_2026", ["shop.orders_.*"]))
        self.assertTrue(tl.match_table_include_list("shop", "orders", ["shop.orders$"]))
        self.assertTrue(tl.match_table_include_list("shop", "anything", ["shop..*"]))
        self.assertTrue(tl.match_table_include_list("shop", {"table_name": "orders"}, ["shop.orders"]))

    def test_entries_are_stripped(self):
        self.assertTrue(tl.match_table_include_list("shop", "orders", ["  shop.orders  "]))
        self.assertEqual(tl.parse_table_include_list("shop.orders, shop.items ,\nshop.users"),
                         ["shop.orders", "shop.items", "shop.users"])

    def test_stray_commas_are_not_entries(self):
        self.assertEqual(tl.parse_table_include_list("shop.orders,,"), ["shop.orders"])
        self.assertFalse(tl.match_table_include_list("shop", "orders", [""]))

    def test_invalid_pattern_is_skipped_and_logged(self):
        with self.assertLogs(level="ERROR") as logs:
            self.assertTrue(tl.match_table_include_list("shop", "orders", ["shop.(", "shop.orders"]))
        self.assertTrue(any("Invalid regex pattern" in line for line in logs.output))

    def test_absent_option_checks_every_table(self):
        self.assertIsNone(tl.parse_table_include_list(None))
        self.assertIsNone(tl.include_list_for_database(None, "shop"))

    def test_present_but_empty_option_selects_nothing(self):
        include = tl.parse_table_include_list("")
        self.assertEqual(include, [])
        self.assertFalse(tl.match_table_include_list("shop", "orders", tl.include_list_for_database(include, "shop")))

    def test_a_database_with_no_entry_selects_nothing_instead_of_everything(self):
        include = tl.parse_table_include_list("shop.orders")
        self.assertEqual(tl.include_list_for_database(include, "shop"), ["shop.orders"])
        other = tl.include_list_for_database(include, "erp")
        self.assertEqual(other, [])
        self.assertFalse(tl.match_table_include_list("erp", "orders", other))


class TestIgnoredColumns(unittest.TestCase):

    def test_well_formed_entries(self):
        self.assertEqual(tl.parse_ignored_columns(["shop.orders.note", "shop.orders.blob", "erp.items.x"]),
                         {"shop": {"orders": {"note": True, "blob": True}}, "erp": {"items": {"x": True}}})

    def test_empty_or_missing_list(self):
        self.assertEqual(tl.parse_ignored_columns([]), {})
        self.assertEqual(tl.parse_ignored_columns(None), {})

    def test_malformed_entry_raises_value_error_not_index_error(self):
        for entry in ("shop", "shop.orders", "shop.orders.a.b", "shop..a", ""):
            with self.subTest(entry=entry):
                with self.assertRaises(ValueError) as context:
                    tl.parse_ignored_columns([entry])
                self.assertIn("<database>.<table>.<column>", str(context.exception))


class TestDatabaseOverrideMap(unittest.TestCase):

    def lookup(self, source, mapping="shop_archive:shop_a,shop:shop_b"):
        return tl.replica_database_for("ch1", source, {"ch1": mapping}, log=False)

    def test_exact_name_selects_its_entry(self):
        self.assertEqual(self.lookup("shop"), "shop_b")
        self.assertEqual(self.lookup("shop_archive"), "shop_a")

    def test_a_substring_of_the_mapping_text_is_not_a_match(self):
        """'sho' and 'archive' occur inside the mapping text; neither is an entry."""
        self.assertEqual(self.lookup("sho"), "sho")
        self.assertEqual(self.lookup("archive"), "archive")
        self.assertEqual(self.lookup("shop_a"), "shop_a")

    def test_unmapped_host_or_database_keeps_the_name(self):
        self.assertEqual(tl.replica_database_for("ch2", "shop", {"ch1": "shop:x"}, log=False), "shop")
        self.assertEqual(self.lookup("erp"), "erp")

    def test_whitespace_is_ignored_and_malformed_entry_raises(self):
        self.assertEqual(self.lookup("shop", " shop : shop_b , erp:erp_b "), "shop_b")
        with self.assertRaises(ValueError):
            self.lookup("shop", "shop")


# ---------------------------------------------------------------------------
# CS-6: --non_partitioned_tables_only
# ---------------------------------------------------------------------------
class TestNonPartitionedTablesOnly(unittest.TestCase):

    def sql(self):
        return db_mysql.get_tables_from_regex_sql(None, False, "db1", ".*", non_partitioned_tables_only=True)

    def test_clause_counts_partition_names_not_rows(self):
        sql = self.sql()
        self.assertIn("having count(partition_name) = 0", sql)
        self.assertNotIn("count(*) = 1", sql)

    def test_the_clause_selects_only_unpartitioned_tables(self):
        """Run the generated subquery on information_schema.partitions-shaped rows."""
        subquery = re.search(r"in \((select .*? having count\(partition_name\) = 0 )\)", self.sql()).group(1)
        connection = sqlite3.connect(":memory:")
        connection.execute("attach database ':memory:' as information_schema")
        connection.execute("create table information_schema.partitions (table_schema text, table_name text, partition_name text)")
        connection.executemany("insert into information_schema.partitions values ('db1', ?, ?)", [
            ("plain", None),                       # not partitioned: one row, NULL partition_name
            ("one_partition", "p0"),               # one partition: one row too, but a name
            ("many", "p0"), ("many", "p1"),
        ])
        self.assertEqual(connection.execute(subquery).fetchall(), [("db1", "plain")])


# ---------------------------------------------------------------------------
# CS-7: aggregate SQL, no session variables, no overflow
# ---------------------------------------------------------------------------
class TestAggregateSql(unittest.TestCase):

    def mysql_statements(self, **overrides):
        my.args = mysql_args(**overrides)
        return my.select_table_statements("t", "q", "`id`", "", "", "id > 0", "0")

    def test_mysql_aggregate_uses_no_user_variables(self):
        statements = self.mysql_statements()
        self.assertFalse(any("@" in statement for statement in statements), statements)
        self.assertFalse(any(statement.lower().startswith("set @") for statement in statements))

    def test_mysql_aggregate_sums_unsigned_words_in_a_derived_table(self):
        aggregate = [s for s in self.mysql_statements() if 'count(*) as "cnt"' in s][0]
        for start in (1, 9, 17, 25):
            self.assertIn(f"sum(cast(conv(substring(`hash`, {start}, 8), 16, 10) as unsigned))", aggregate)
        self.assertNotIn("as signed", aggregate)
        self.assertNotIn("max(", aggregate)
        self.assertIn("NO_MERGE(t)", aggregate)
        self.assertEqual(len(re.findall(r"md5\(", aggregate)), 1, "the md5 is computed once per row")

    def test_mysql_session_statements_are_unchanged(self):
        statements = self.mysql_statements()
        self.assertEqual(statements[:3], ['set names utf8mb4', 'set session wait_timeout=28000', "set time_zone = '+00:00'"])

    def test_clickhouse_sums_as_uint128(self):
        ch.args = clickhouse_args()
        (aggregate,) = ch.select_table_statements("t", "q", "toString(\"id\")", "", "", "id > 0")
        self.assertEqual(len(re.findall(r"sum\(toUInt128\(reinterpretAsUInt64\(", aggregate)), 4)
        self.assertNotIn("reinterpretAsInt64", aggregate)

    def test_totals_past_the_signed_bigint_range_are_exact(self):
        """DECIMAL sums (> 2**63) from the server are added and printed exactly."""
        my.args = mysql_args()
        big = 2 ** 70
        chunks = [[Decimal(2), Decimal(big), Decimal(1), Decimal(2), Decimal(3), Decimal(0)],
                  [Decimal(1), Decimal(big), Decimal(1), Decimal(2), Decimal(3), Decimal(0)]]
        with mock.patch.object(my, "get_mysql_connection", return_value=mock.MagicMock()), \
                mock.patch.object(my, "collect_chunk_results", return_value=chunks), \
                self.assertLogs(level="INFO") as logs:
            my.calculate_checksum("t", "u", "p", [], False, False)
        expected = checksum_from_aggregate(3, 2 * big, 2, 4, 6)
        self.assertIn(f"INFO:root:Checksum for table db1.t = {expected} count 3", logs.output)


# ---------------------------------------------------------------------------
# CS-8: identifiers, connections, empty column set, dead key code
# ---------------------------------------------------------------------------
class TestIdentifierQuoting(unittest.TestCase):

    def test_mysql_identifier_and_literal_helpers(self):
        self.assertEqual(db_mysql.quote_identifier("id"), "`id`")
        self.assertEqual(db_mysql.quote_identifier("we`ird"), "`we``ird`")
        self.assertEqual(db_mysql.sql_literal("it's"), "'it\\'s'")
        self.assertEqual(db_mysql.sql_literal("a\\b"), "'a\\\\b'")

    def test_primary_key_and_table_are_quoted_in_every_statement(self):
        statements = []

        def fake(conn, sql):
            statements.append(sql)
            if "min(" in sql:
                return pd.DataFrame({"min_pk": [1], "max_pk": [10]})
            if sql.lstrip().lower().startswith("explain"):
                return pd.DataFrame({"rows": [6]})
            return pd.DataFrame({"i`d": [1]})

        with mock.patch.object(db_mysql, "mysql_execute_df", side_effect=fake):
            list(db_mysql.divide_table_into_even_chunks(object(), "t`1", 3, "i`d", "1=1"))
        self.assertTrue(statements)
        for sql in statements:
            self.assertIn("`i``d`", sql)
            self.assertIn("`t``1`", sql)
            self.assertNotRegex(sql, r"(?<!`)\bi`d\b")

    def test_no_key_sentinel_is_not_queried_as_a_column(self):
        with mock.patch.object(db_mysql, "mysql_execute_df") as execute:
            self.assertEqual(db_mysql.get_min_max_pk_value(object(), "t", "NULL", "1=1"), (None, None))
            self.assertEqual(db_mysql.get_min_max_pk_value(object(), "t", None, "1=1"), (None, None))
        execute.assert_not_called()

    def test_catalog_lookups_escape_their_values(self):
        seen = []
        with mock.patch.object(db_mysql, "mysql_execute_df",
                               side_effect=lambda conn, sql: seen.append(sql) or pd.DataFrame({"COLUMN_NAME": []})):
            db_mysql.mysql_pk_columns(object(), "d'b", "t'1")
            db_mysql.mysql_columns(object(), "d'b", "t'1", "p'k")
            db_mysql.mysql_columns_by_data_type(object(), "d'b", "t'1", ("time'stamp",))
        for sql in seen:
            self.assertNotIn("'d'b'", sql)
            self.assertIn("'d\\'b'", sql)
            self.assertIn("'t\\'1'", sql)

    def test_mysql_row_expression_quotes_column_names(self):
        my.args = mysql_args()
        (select, nullables, data_types, clamped, skipped) = my.build_mysql_row_expression(
            [mysql_column("we`ird", "int")], my.args, "base64", [], False, False)
        self.assertIn("`we``ird`", select)

    def test_mysql_statement_quotes_schema_and_table(self):
        my.args = mysql_args(mysql_database="d`b")
        aggregate = [s for s in my.select_table_statements("t`1", "q", "`id`", "", "", None) if 'count(*) as "cnt"' in s][0]
        self.assertIn("`d``b`.`t``1`", aggregate)

    def test_clickhouse_identifiers_and_table_reference(self):
        self.assertEqual(_sql_text.clickhouse_identifier('we"ird'), '"we\\"ird"')
        self.assertEqual(_sql_text.clickhouse_identifier("a\\b"), '"a\\\\b"')
        self.assertEqual(_sql_text.clickhouse_literal("it's"), "'it\\'s'")
        self.assertEqual(_sql_text.clickhouse_table_reference("d", "t"), '"d"."t"')

    def test_clickhouse_statement_quotes_database_and_table(self):
        ch.args = clickhouse_args(clickhouse_database='d"b', debug_output=True)
        statements = ch.select_table_statements('t"1', "q", 'toString("id")', "", "", None)
        self.assertEqual(len(statements), 2)
        for statement in statements:
            self.assertIn('"d\\"b"."t\\"1" final', statement)

    def test_clickhouse_catalog_queries_escape_their_values(self):
        seen = []
        ch.args = clickhouse_args(clickhouse_database="d'b")
        with mock.patch.object(ch, "execute_sql", side_effect=lambda conn, sql: seen.append(sql) or ([], 0)):
            ch.get_primary_key_columns(None, "d'b", "t'1")
            ch.get_engine_full(None, "d'b", "t'1")
        for sql in seen:
            self.assertIn("'d\\'b'", sql)
            self.assertIn("'t\\'1'", sql)

    def test_debug_limit_must_be_an_integer(self):
        my.args = mysql_args(debug_output=True, debug_limit="5; drop table t")
        with self.assertRaises(ValueError):
            my.select_table_statements("t", "q", "`id`", "", "", None)
        ch.args = clickhouse_args(debug_output=True, debug_limit="5; drop table t")
        with self.assertRaises(ValueError):
            ch.select_table_statements("t", "q", 'toString("id")', "", "", None)
        my.args = mysql_args(debug_output=True, debug_limit="5")
        self.assertIn(" limit 5", my.select_table_statements("t", "q", "`id`", "", "", None)[-1])


class TestConnectionsAreClosed(unittest.TestCase):

    def test_mysql_connection_is_closed_when_the_table_fails(self):
        my.args = mysql_args()
        conn = mock.MagicMock()
        with mock.patch.object(my, "get_mysql_connection", return_value=conn), \
                mock.patch.object(my, "collect_chunk_results", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                my.calculate_checksum("t", "u", "p", [], False, False)
        conn.close.assert_called_once()

    def test_mysql_connection_is_closed_on_success(self):
        my.args = mysql_args()
        conn = mock.MagicMock()
        with mock.patch.object(my, "get_mysql_connection", return_value=conn), \
                mock.patch.object(my, "collect_chunk_results", return_value=[[0, 0, 0, 0, 0, 0]]):
            my.calculate_checksum("t", "u", "p", [], False, False)
        conn.close.assert_called_once()

    def test_clickhouse_catalog_connection_is_closed_when_the_table_fails(self):
        ch.args = clickhouse_args()
        conn = mock.MagicMock()
        with mock.patch.object(ch, "get_connection", return_value=conn), \
                mock.patch.object(ch, "get_engine_full", return_value=""), \
                mock.patch.object(ch, "get_table_checksum_query", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                ch.calculate_checksum("t", "u", "p", None, None)
        conn.close.assert_called_once()

    def test_count_connections_are_closed_when_a_query_fails(self):
        ch_count.args = argparse.Namespace(ignore_tables_regex=None, include_partitions_regex=None,
                                           clickhouse_database="db1", where=None, sign_column="")
        connections = []

        def new_connection(user, password):
            connections.append(mock.MagicMock())
            return connections[-1]

        def execute_sql(conn, sql):
            if sql.startswith("select count(*)"):
                raise RuntimeError("boom")
            return ([], 0)

        with mock.patch.object(ch_count, "get_connection", side_effect=new_connection), \
                mock.patch.object(ch_count, "execute_sql", side_effect=execute_sql):
            with self.assertRaises(RuntimeError):
                ch_count.calculate_table_count("t", "", "u", "p")
        self.assertEqual(len(connections), 2)
        for conn in connections:
            conn.close.assert_called_once()


class TestNoComparableColumns(unittest.TestCase):

    def test_mysql_raises_when_every_column_is_excluded(self):
        my.args = mysql_args()
        columns = [mysql_column("id", "int"), mysql_column("f", "double")]
        rowset = mock.Mock()
        rowset.mappings.return_value = columns
        with mock.patch.object(my, "execute_mysql", return_value=(rowset, -1)):
            with self.assertRaises(ValueError) as context:
                my.get_table_checksum_query("t", mock.Mock(), "base64", None, ["id"], False, False)
        self.assertIn("No comparable column in db1.t", str(context.exception))

    def test_clickhouse_raises_when_every_column_is_excluded(self):
        ch.args = clickhouse_args(exclude_columns=["id,f"])

        def execute_sql(conn, sql):
            if "is_in_primary_key" in sql:
                return ([], 0)
            return ([("id", "Int32", 0, None, 0, 0), ("f", "Float64", 0, None, 0, 0)], 2)

        with mock.patch.object(ch, "execute_sql", side_effect=execute_sql):
            with self.assertRaises(ValueError) as context:
                ch.get_table_checksum_query(mock.Mock(), "t")
        self.assertIn("No comparable column in db1.t", str(context.exception))

    def test_clickhouse_key_column_that_is_excluded_does_not_raise_key_error(self):
        """The dead external_column_types loop indexed data_types[<key column>]."""
        ch.args = clickhouse_args(exclude_columns=["id"])

        def execute_sql(conn, sql):
            if "is_in_primary_key" in sql:
                return ([("id",)], 1)
            return ([("id", "Int32", 0, None, 0, 0), ("name", "String", 0, None, 0, 0)], 2)

        with mock.patch.object(ch, "execute_sql", side_effect=execute_sql):
            result = ch.get_table_checksum_query(mock.Mock(), "t")
        self.assertEqual(result[3], "")
        self.assertNotIn('"id"', result[1])


# ---------------------------------------------------------------------------
# CS-9: ClickHouse count
# ---------------------------------------------------------------------------
class TestClickHouseCount(unittest.TestCase):

    def test_setting_only_when_the_partition_key_is_within_the_sorting_key(self):
        within = ch_count.build_count_sql("db1", "t", None, "", True)
        self.assertTrue(within.endswith(" settings do_not_merge_across_partitions_select_final=1"))
        outside = ch_count.build_count_sql("db1", "t", None, "", False)
        self.assertNotIn("settings", outside)
        self.assertNotIn("do_not_merge_across_partitions_select_final", outside)

    def test_sign_filter_and_where(self):
        sql = ch_count.build_count_sql("db1", "t", "a = 1 or b = 2", "_sign", False)
        self.assertEqual(sql, 'select count(*) cnt from "db1"."t" final where 1=1 and (a = 1 or b = 2) and _sign > 0')

    def test_partition_value_is_a_quoted_literal(self):
        sql = ch_count.build_count_sql("db1", "t", None, "", False, "toYYYYMM(dt)", "2026'09")
        self.assertIn("toYYYYMM(dt) = '2026\\'09'", sql)

    def test_partition_key_check_over_catalog_rows(self):
        def run(rows):
            with mock.patch.object(ch_count, "execute_sql", return_value=(rows, len(rows))):
                return ch_count.final_per_partition_for_table(None, "db1", "t")

        self.assertTrue(run([("id", "Int32", 0, 0, 0, 1), ("dt", "Date", 0, 0, 1, 1)]))
        self.assertFalse(run([("id", "Int32", 0, 0, 0, 1), ("dt", "Date", 0, 0, 1, 0)]), "partition column outside the sorting key")
        self.assertFalse(run([("id", "Int32", 0, 0, 0, 1)]), "no partition key at all")

    def count(self, engine_full, columns, **overrides):
        values = dict(clickhouse_database="db1", ignore_tables_regex=None, include_partitions_regex=None, where=None,
                      sign_column="")
        values.update(overrides)
        ch_count.args = argparse.Namespace(**values)
        executed = []

        def execute_sql(conn, sql):
            executed.append(sql)
            if "engine_full" in sql:
                return ([(engine_full,)], 1)
            if "from system.columns" in sql:
                return (columns, len(columns))
            return ([(7,)], 1)

        with mock.patch.object(ch_count, "get_connection", return_value=mock.MagicMock()), \
                mock.patch.object(ch_count, "execute_sql", side_effect=execute_sql), \
                self.assertLogs(level="INFO"):
            ch_count.calculate_table_count("t", "", "u", "p")
        return [sql for sql in executed if sql.startswith("select count(*)")][0]

    def test_collapsing_engine_counts_live_rows_only(self):
        sql = self.count("CollapsingMergeTree(sign) ORDER BY id", [])
        self.assertIn("and sign > 0", sql)

    def test_replacing_engine_has_no_sign_filter_and_the_explicit_option_wins(self):
        self.assertNotIn("> 0", self.count("ReplacingMergeTree(_version, is_deleted) ORDER BY id", []))
        self.assertIn("and live > 0", self.count("MergeTree ORDER BY id", [], sign_column="live"))

    def test_setting_follows_the_catalog_in_the_full_run(self):
        columns_within = [("dt", "Date", 0, 0, 1, 1)]
        columns_outside = [("dt", "Date", 0, 0, 1, 0)]
        self.assertIn("do_not_merge_across_partitions_select_final=1", self.count("MergeTree ORDER BY dt", columns_within))
        self.assertNotIn("do_not_merge_across_partitions_select_final", self.count("MergeTree ORDER BY id", columns_outside))


# ---------------------------------------------------------------------------
# CS-10: pinned, not changed -- the driver relays side WARNING lines at INFO
# ---------------------------------------------------------------------------
class TestSideWarningRelayLevel(unittest.TestCase):
    """Spec 11.02 section 3.2 step 4: the scheduled job fails on any WARNING line, so a
    side WARNING is a side note at INFO and the driver log keeps WARNING for
    differences. A relay at WARNING was tried and removed (spec 11.02 section 3.9)."""

    def test_side_warning_is_relayed_at_info_and_error_at_error(self):
        line = ("2026-09-28 10:00:00,000 - WARNING - MainThread - Not compared in table db1.t: "
                "1 floating point column ['f'] (pass --include_floating_point_columns to compare them)")
        with self.assertLogs(level="INFO") as logs:
            failed = tl.relay_side_messages(line, "ch1", "t")
        self.assertFalse(failed)
        self.assertTrue(any(entry.startswith("INFO:root:") and "side note" in entry for entry in logs.output), logs.output)
        self.assertFalse(any(entry.startswith("WARNING:") for entry in logs.output), logs.output)
        with self.assertLogs(level="ERROR") as logs:
            failed = tl.relay_side_messages("2026-09-28 10:00:00,000 - ERROR - MainThread - boom", "ch1", "t")
        self.assertTrue(failed)
        self.assertTrue(logs.output[0].startswith("ERROR:"))

    def test_fail_on_different_exit_code_contract_is_unchanged(self):
        different = {"db1.t": tl.VERDICT_DIFFERENT}
        self.assertEqual(self.summary(different, fail_on_different=True), 1)
        self.assertEqual(self.summary(different, fail_on_different=False), 0)

    def summary(self, verdicts, fail_on_different):
        with self.assertLogs(level="INFO"):
            return tl.report_run_summary(verdicts, False, (), False, fail_on_different=fail_on_different,
                                         source_zone="UTC")


# ---------------------------------------------------------------------------
# CS-11: newline and hex case
# ---------------------------------------------------------------------------
class TestNewlineAndHexRendering(unittest.TestCase):

    def expression(self, data_type, encoding):
        my.args = mysql_args()
        return my.mysql_column_expression(mysql_column("c", data_type, data_type + "(16)"), my.args, encoding, True)

    def test_base64_newline_is_removed_by_code_point_not_by_a_backslash_escape(self):
        expression = self.expression("varbinary", "base64")
        self.assertIn("char(10 using ascii)", expression)
        self.assertNotIn("\\n", expression, "'\\n' is a backslash and an n under NO_BACKSLASH_ESCAPES")

    def test_hex_is_lower_case_on_both_sides(self):
        for encoding in ("hex", "raw"):
            self.assertEqual(self.expression("varbinary", encoding), "lower(hex(cast(`c` as binary)))")
        ch.args = clickhouse_args(binary_encoding="raw", hex_columns=["b"])
        (select, _) = clickhouse_row_expression([("b", "String", 0, None)], binary_encoding="raw", hex_columns=["b"])
        self.assertIn('lower(hex("b"))', select)


# ---------------------------------------------------------------------------
# CS-12: collation comparison counts distinct collations
# ---------------------------------------------------------------------------
class TestCollationComparison(unittest.TestCase):

    def select(self, columns):
        my.args = mysql_args()
        return my.build_mysql_row_expression(columns, my.args, "base64", [], False, False)[0]

    def test_many_text_columns_with_one_collation_need_no_conversion(self):
        columns = [mysql_column(name, "varchar", "varchar(8)", collation="utf8mb4_0900_ai_ci") for name in ("a", "b", "c")]
        self.assertNotIn("convert(", self.select(columns))

    def test_no_text_column_needs_no_conversion(self):
        self.assertNotIn("convert(", self.select([mysql_column("id", "int")]))

    def test_two_collations_are_converted(self):
        columns = [mysql_column("a", "varchar", "varchar(8)", collation="utf8mb4_0900_ai_ci"),
                   mysql_column("b", "varchar", "varchar(8)", collation="latin1_swedish_ci")]
        select = self.select(columns)
        self.assertIn("convert(`a` using utf8mb4)", select)
        self.assertIn("convert(`b` using utf8mb4)", select)


# ---------------------------------------------------------------------------
# Postgres: Tier-2 composite keys, chunk size, type checks
# ---------------------------------------------------------------------------
class TestPostgresTier2CompositeKey(unittest.TestCase):

    def test_single_key_text_is_unchanged(self):
        self.assertEqual(pg.pk_hash_input("id"), '"id"::text')
        self.assertEqual(pg.pk_hash_input(["id"]), '"id"::text')

    def test_composite_key_hashes_every_column_in_order(self):
        self.assertEqual(pg.pk_hash_input(["tenant", "id"]), 'concat_ws(chr(1), "tenant"::text, "id"::text)')
        with self.assertRaises(ValueError):
            pg.pk_hash_input([])

    def test_tier2_query_hashes_all_key_columns(self):
        sql = pg.build_tier2_chunk_query("t", "s", "id", 1, 10, hash_columns=["tenant", "id"])
        self.assertEqual(sql.count('concat_ws(chr(1), "tenant"::text, "id"::text)'), 1)
        self.assertIn('ORDER BY "tenant", "id"', sql)
        self.assertIn('"id" BETWEEN 1 AND 10', sql)
        debug = pg.build_tier2_chunk_query("t", "s", "id", None, None, debug_output=True, hash_columns=["tenant", "id"])
        self.assertIn('md5(concat_ws(chr(1), "tenant"::text, "id"::text)) AS pk_hash', debug)

    def test_tier2_default_is_the_single_key_query(self):
        sql = pg.build_tier2_chunk_query("t", "s", "id", 1, 10)
        self.assertIn('md5("id"::text)', sql)
        self.assertIn('ORDER BY "id"', sql)

    def test_driver_passes_every_key_column_to_the_query(self):
        captured = []
        meta = [{"column_name": "tenant", "pg_type": "text", "udt_name": "text", "nullable": False},
                {"column_name": "id", "pg_type": "integer", "udt_name": "int4", "nullable": False}]
        with mock.patch.object(pg, "divide_table_into_chunks", return_value=[{"min_pk": 1, "max_pk": 10}]), \
                mock.patch.object(pg, "compute_chunk_checksum", side_effect=lambda *a, **k: captured.append(a[7]) or (1, 1, 1, 1, 1)):
            pg.get_postgres_table_checksum(mock.Mock(), "t", meta, ["tenant", "id"], schema="s", tier=2,
                                           snapshot_conn=mock.Mock())
        self.assertEqual(len(captured), 1)
        self.assertIn('concat_ws(chr(1), "tenant"::text, "id"::text)', captured[0])
        self.assertIn('"id" BETWEEN 1 AND 10', captured[0])

    def test_clickhouse_tier2_matches_the_postgres_text(self):
        statements = []

        def execute_sql(conn, sql):
            statements.append(sql)
            return ([(0, 0, 0, 0, 0)], 1)

        with mock.patch.object(tlp, "execute_sql", side_effect=execute_sql):
            tlp.get_ch_checksum(mock.Mock(), "db1", "t", [], 2, pk_col="tenant", pk_cols=["tenant", "id"])
            tlp.get_ch_checksum(mock.Mock(), "db1", "t", [], 2, pk_col="id")
        self.assertIn('MD5(concat_ws(char(1), toString("tenant"), toString("id")))', statements[0])
        self.assertIn('MD5(toString("id"))', statements[1])


class TestPostgresChunkSize(unittest.TestCase):

    def divide(self, chunk_size, pk_col="id", rows=None):
        rows = rows if rows is not None else [{"min_pk": 1, "max_pk": 10}]
        with mock.patch.object(pg, "execute_pg", return_value=rows) as execute:
            return pg.divide_table_into_chunks(mock.Mock(), "t", "s", pk_col, chunk_size), execute

    def test_zero_is_one_chunk_without_a_range_query(self):
        (chunks, execute) = self.divide(0)
        self.assertEqual(chunks, [{"min_pk": None, "max_pk": None}])
        execute.assert_not_called()

    def test_negative_is_refused_instead_of_looping(self):
        for size in (-1, -100000):
            with self.subTest(size=size):
                with self.assertRaises(ValueError) as context:
                    self.divide(size)
                self.assertIn("--chunk_size", str(context.exception))

    def test_negative_is_refused_even_without_a_key(self):
        with self.assertRaises(ValueError):
            self.divide(-5, pk_col=None)

    def test_positive_chunks_cover_the_range(self):
        (chunks, _) = self.divide(4)
        self.assertEqual(chunks, [{"min_pk": 1, "max_pk": 4}, {"min_pk": 5, "max_pk": 8}, {"min_pk": 9, "max_pk": 10}])

    def test_no_key_and_empty_table_are_one_chunk(self):
        self.assertEqual(self.divide(4, pk_col=None)[0], [{"min_pk": None, "max_pk": None}])
        self.assertEqual(self.divide(4, rows=[{"min_pk": None, "max_pk": None}])[0], [{"min_pk": None, "max_pk": None}])


class TestPostgresTypeChecks(unittest.TestCase):

    def expression(self, pg_type, udt=""):
        return pg.build_pg_select_expression("c", pg_type, False, udt)[0]

    def test_integer_check_is_exact(self):
        for integer in ("integer", "bigint", "smallint", "int4", "int8", "serial", "bigserial", "INTEGER"):
            self.assertTrue(pg.is_integer_pg_type(integer), integer)
        for other in ("interval", "point", "text", "numeric", "timestamp without time zone", "inet", "", None):
            self.assertFalse(pg.is_integer_pg_type(other), other)

    def test_interval_key_is_not_chosen_for_range_chunking(self):
        captured = []
        meta = [{"column_name": "span", "pg_type": "interval", "udt_name": "interval", "nullable": False},
                {"column_name": "v", "pg_type": "text", "udt_name": "text", "nullable": False}]
        with mock.patch.object(pg, "divide_table_into_chunks", side_effect=lambda *a, **k: captured.append(a[3]) or [{"min_pk": None, "max_pk": None}]), \
                mock.patch.object(pg, "compute_chunk_checksum", return_value=(0, 0, 0, 0, 0)):
            pg.get_postgres_table_checksum(mock.Mock(), "t", meta, ["span"], schema="s", tier=1, snapshot_conn=mock.Mock())
        self.assertEqual(captured, [None])

    def test_time_with_time_zone_is_normalised_to_utc(self):
        self.assertEqual(self.expression("time with time zone"),
                         "to_char((\"c\" AT TIME ZONE 'UTC')::time, 'HH24:MI:SS.US')")

    def test_time_without_time_zone_is_unchanged(self):
        self.assertEqual(self.expression("time without time zone"), "to_char(\"c\", 'HH24:MI:SS.US')")

    def test_timestamp_without_time_zone_is_not_converted(self):
        """'timestamp without time zone' contains the words 'time zone' too."""
        self.assertEqual(self.expression("timestamp without time zone"), "to_char(\"c\", 'YYYY-MM-DD HH24:MI:SS.US')")
        self.assertEqual(self.expression("timestamp with time zone"),
                         "to_char(\"c\" AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')")

    def test_interval_falls_through_to_text(self):
        self.assertEqual(self.expression("interval"), '"c"::text')


# ---------------------------------------------------------------------------
# Review gates: the e2e support module finds the repository root
# ---------------------------------------------------------------------------
class TestE2eRepoRoot(unittest.TestCase):

    SUPPORT = PYTHON_ROOT / "tests_e2e" / "mysql" / "mysql_e2e_support.py"

    def test_repo_root_is_the_directory_that_holds_sink_connector_python(self):
        """mysql_e2e_support.py: HERE is already the file's directory (.../tests_e2e/mysql), so its
        parents are [0]=tests_e2e [1]=python [2]=sink-connector [3]=repository root. parents[4] would
        be the directory above the checkout."""
        source = self.SUPPORT.read_text(encoding="utf-8")
        self.assertIn("HERE = Path(__file__).resolve().parent", source)
        match = re.search(r"^REPO_ROOT = HERE\.parents\[(\d+)\]$", source, re.M)
        self.assertIsNotNone(match)
        here = self.SUPPORT.resolve().parent
        repo_root = here.parents[int(match.group(1))]
        self.assertTrue((repo_root / "sink-connector" / "python").is_dir(), repo_root)
        self.assertEqual(repo_root / "sink-connector" / "python", PYTHON_ROOT)


if __name__ == "__main__":
    unittest.main()
