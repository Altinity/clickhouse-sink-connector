"""Unit coverage for the pure-logic helpers in db_load/clickhouse_loader.py.

Spec 11.05 (python-tooling test contract): the loader's DDL/schema/timezone
parsing helpers are deterministic and must be covered without a live database.
These were previously untested (the loader had zero unit tests). No ClickHouse
or MySQL connection is used here; the integrated dump->load->checksum path is
covered separately by the e2e suite.
"""
import re

from db_load import clickhouse_loader as cl
from db_load.mysql_parser.mysql_parser import convert_to_clickhouse_table_antlr


class TestSchemaPathParsing:
    def test_parse_schema_path_mydumper(self):
        assert cl.parse_schema_path("/dumps/shop_staging.trade-schema.sql.gz") == (
            "shop_staging", "trade")

    def test_parse_schema_path_mysqlshell(self):
        assert cl.parse_schema_path_mysqlshell("/dumps/fees_staging@txn_fee.sql") == (
            "fees_staging", "txn_fee")

    def test_mysqlshell_table_with_special_chars(self):
        # mysqlsh encodes db@table; the table segment is everything after '@'
        assert cl.parse_schema_path_mysqlshell("/d/trade_uat@enriched_trade.sql") == (
            "trade_uat", "enriched_trade")


class TestDumpTimezone:
    def test_finds_set_time_zone(self):
        src = "-- dump\n/*!40103 SET TIME_ZONE='+00:00' */;\nCREATE TABLE ..."
        assert cl.find_dump_timezone(src) == "+00:00"

    def test_case_insensitive(self):
        assert cl.find_dump_timezone("set time_zone='America/Chicago';") == "America/Chicago"

    def test_absent_returns_none(self):
        assert cl.find_dump_timezone("CREATE TABLE t (id INT);") is None


class TestUnixTimezoneFromMysqlTimezone:
    def test_utc_offset_resolves_to_a_zero_offset_zone(self):
        import datetime
        import zoneinfo
        tz = cl.get_unix_timezone_from_mysql_timezone("+00:00")
        # The function returns the first IANA zone whose current offset matches;
        # for '+00:00' that zone must genuinely be at UTC offset 0.
        off = datetime.datetime.now(zoneinfo.ZoneInfo(tz)).utcoffset().total_seconds()
        assert off == 0

    def test_unknown_offset_falls_back_to_utc(self):
        # No real zone is at +99:00, so the documented default is returned.
        assert cl.get_unix_timezone_from_mysql_timezone("+99:00") == "UTC"


class TestSourceIntrospection:
    def test_find_create_table_true(self):
        assert cl.find_create_table("CREATE TABLE `t` (id INT)") is True

    def test_find_create_table_false(self):
        assert cl.find_create_table("INSERT INTO t VALUES (1)") is False

    def test_find_primary_key(self):
        pk = cl.find_primary_key("CREATE TABLE t (id INT, PRIMARY KEY (`id`))")
        assert pk is not None and "id" in pk

    def test_find_primary_key_absent(self):
        assert cl.find_primary_key("CREATE TABLE t (id INT)") is None

    def test_find_partitioning_options_range_columns(self):
        opts = cl.find_partitioning_options(
            "CREATE TABLE t (d DATE) PARTITION BY RANGE COLUMNS(d) (PARTITION p VALUES LESS THAN ('2026-01-01'))")
        assert opts == "PARTITION BY d"

    def test_find_partitioning_options_absent(self):
        assert cl.find_partitioning_options("CREATE TABLE t (id INT)") == ""


class TestDdlConversionAntlr:
    """The antlr DDL translator turns a MySQL CREATE TABLE into a ClickHouse one.
    These assert the structural contract, not byte-exact output."""

    SRC = (
        "CREATE TABLE `orders` (\n"
        "  `id` bigint NOT NULL,\n"
        "  `created` datetime(6) DEFAULT NULL,\n"
        "  `ts` timestamp NULL DEFAULT NULL,\n"
        "  `qty` decimal(30,15) DEFAULT NULL,\n"
        "  `note` varchar(64) DEFAULT NULL,\n"
        "  PRIMARY KEY (`id`)\n"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
    )

    def test_returns_ddl_and_columns(self):
        ddl, columns = convert_to_clickhouse_table_antlr(self.SRC, rmt_delete_support=True)
        assert isinstance(ddl, str) and ddl.strip()
        assert isinstance(columns, list) and columns

    def test_datetime_maps_to_datetime64(self):
        ddl, _ = convert_to_clickhouse_table_antlr(self.SRC, rmt_delete_support=True)
        assert "DateTime64" in ddl

    def test_decimal_precision_preserved(self):
        ddl, _ = convert_to_clickhouse_table_antlr(self.SRC, rmt_delete_support=True)
        # the translator passes DECIMAL(p,s) through (ClickHouse accepts the
        # lowercase alias); precision/scale must survive verbatim
        assert re.search(r"decimal\(\s*30\s*,\s*15\s*\)", ddl, re.IGNORECASE)

    def test_replacing_merge_tree_engine(self):
        ddl, _ = convert_to_clickhouse_table_antlr(self.SRC, rmt_delete_support=True)
        assert "ReplacingMergeTree" in ddl

    def test_order_by_primary_key(self):
        ddl, _ = convert_to_clickhouse_table_antlr(self.SRC, rmt_delete_support=True)
        assert re.search(r"order by\s*\(?\s*`?id`?", ddl, re.IGNORECASE)

    def test_datetime_timezone_applied_when_configured(self):
        ddl, _ = convert_to_clickhouse_table_antlr(
            self.SRC, rmt_delete_support=True, datetime_timezone="America/Chicago")
        # a configured session zone is stamped onto the DateTime64 columns
        assert "America/Chicago" in ddl

    def test_no_create_table_returns_empty(self):
        ddl, cols = cl.convert_to_clickhouse_table(
            "u", "t", "INSERT INTO t VALUES (1)", True, False, None)
        assert ddl == "" and cols == []
