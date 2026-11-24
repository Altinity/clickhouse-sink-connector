"""Checksum identity means source-named values and the configured endpoints."""
import argparse
import json
import os
import sqlite3
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
from db_compare import clickhouse_table_checksum as ch
from db_compare import mysql_table_checksum as my
from db_compare import top_level_table_checksum as tl
from db_compare.tests.test_checksum_fidelity import (
    clickhouse_args, clickhouse_stub, mysql_args, mysql_column,
    project_information_schema,
)


class SourceColumnOrderTest(unittest.TestCase):
    def row_texts(self, replica_values):
        columns = [mysql_column("a", "int"), mysql_column("b", "int")]
        catalog = project_information_schema(
            "select COLUMN_NAME as column_name, DATA_TYPE as data_type, "
            "COLUMN_TYPE as column_type, IS_NULLABLE as is_nullable, "
            "COLLATION_NAME as collation, DATETIME_PRECISION as datetime_precision "
            "from information_schema.columns", columns)
        source_expression = my.build_mysql_row_expression(
            catalog, mysql_args(), "base64", [], False, False)[0]
        metadata = [("b", "Int32", 0, None), ("a", "Int32", 0, None)]
        with patch.object(ch, "args", clickhouse_args(source_columns='["a", "b"]'), create=True), \
                patch.object(ch, "execute_sql", clickhouse_stub(metadata, [])):
            replica_expression = ch.get_table_checksum_query(None, "t")[1]
        with sqlite3.connect(":memory:") as db:
            db.create_function("concat", -1, lambda *values: "".join(map(str, values)))
            db.create_function("concat_ws", -1, lambda separator, *values: separator.join(map(str, values)))
            db.create_function("is_null", 1, lambda value: int(value is None))
            db.create_function("toString", 1, str)
            db.create_function("replaceAll", 3, lambda value, old, new: value.replace(old, new))
            # Evaluate generated expressions with equivalent SQLite string functions.
            source_expression = source_expression.replace(
                "CHAR(92 USING utf8mb4)", "char(92)").replace("ISNULL(", "is_null(")
            source = db.execute("select concat_ws('#'," + source_expression +
                                ") from (select 1 as a, 2 as b)").fetchone()[0]
            replica = db.execute("select " + replica_expression +
                                 " from (select ? as a, ? as b)", replica_values).fetchone()[0]
        return source, replica

    def test_equal_named_values_match_despite_replica_physical_order(self):
        source, replica = self.row_texts((1, 2))
        self.assertEqual(source, replica)

    def test_swapped_named_values_cannot_hide_behind_replica_physical_order(self):
        source, replica = self.row_texts((2, 1))
        self.assertNotEqual(source, replica)

    def test_source_order_preserves_special_names_and_case_insensitive_membership(self):
        names = ["z, column", "a column"]
        rows = [("A COLUMN", "String", 0, None), ("extra", "String", 0, None),
                ("Z, COLUMN", "String", 0, None)]
        kept = ch.restrict_to_source_columns("db", "t", rows, json.dumps(names), [], set())
        self.assertEqual(["Z, COLUMN", "A COLUMN"], [row[0] for row in kept])

    def test_existing_source_column_parser_keeps_set_api(self):
        self.assertEqual({"b", "a"}, ch.parse_source_columns('["b", "a"]'))
        self.assertEqual({"b", "a"}, ch.parse_source_columns("b, a"))
        self.assertEqual(set(), ch.parse_source_columns(None))

    def test_unknown_source_order_keeps_standalone_catalog_order(self):
        rows = [("b", "Int32", 0, None), ("a", "Int32", 0, None)]
        self.assertEqual(rows, ch.restrict_to_source_columns("db", "t", rows, "", [], set()))


class SideEndpointIdentityTest(unittest.TestCase):
    def driver_args(self, **overrides):
        values = dict(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC")
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_mysql_side_uses_configured_source_port_and_defaults_file(self):
        options = self.driver_args(mysql_port="3307")
        config_file = "source client.cnf"
        with patch.object(tl, "args", options, create=True):
            command = tl.get_mysql_checksum_command(
                "source", "db", "t", "id", 10, None, defaults_file=config_file)
        parsed = my.build_argument_parser().parse_args(command[3:])
        self.assertEqual(3307, int(parsed.mysql_port))
        self.assertEqual(config_file, parsed.defaults_file)
        self.assertFalse(any("password" in word.lower() for word in command))

    def test_clickhouse_side_uses_configured_endpoint_tls_and_config_path(self):
        config_file = "replica client.xml"
        options = self.driver_args(clickhouse_port="9440", secure=True,
                                   clickhouse_config_file=config_file)
        with patch.object(tl, "args", options, create=True):
            command = tl.get_clickhouse_checksum_command("replica", "db", "t", "id", 10)
        parsed = ch.build_argument_parser().parse_args(command[3:])
        self.assertEqual(9440, int(parsed.clickhouse_port))
        self.assertEqual("True", parsed.secure)
        self.assertEqual(config_file, parsed.clickhouse_config_file)
        self.assertFalse(any("password" in word.lower() for word in command))

    def test_old_namespace_fixtures_keep_side_connection_defaults(self):
        with patch.object(tl, "args", self.driver_args(), create=True):
            source = tl.get_mysql_checksum_command("source", "db", "t", "id", 10, None)
            replica = tl.get_clickhouse_checksum_command("replica", "db", "t", "id", 10)
        source_options = my.build_argument_parser().parse_args(source[3:])
        replica_options = ch.build_argument_parser().parse_args(replica[3:])
        self.assertEqual(3306, int(source_options.mysql_port))
        self.assertEqual(9000, int(replica_options.clickhouse_port))
        self.assertIs(False, replica_options.secure)
        self.assertEqual("./clickhouse-client.xml", replica_options.clickhouse_config_file)

    def test_false_tls_option_does_not_enable_tls(self):
        with patch.object(tl, "args", self.driver_args(secure=False), create=True):
            command = tl.get_clickhouse_checksum_command("replica", "db", "t", "id", 10)
        self.assertIs(False, ch.build_argument_parser().parse_args(command[3:]).secure)


if __name__ == "__main__":
    unittest.main()
