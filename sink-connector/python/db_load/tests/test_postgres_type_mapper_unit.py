"""Unit coverage for the PostgreSQL->ClickHouse type mapper and table filter.

Spec 11.05: the Postgres snapshot/load tooling shares the same untested-logic
risk as the MySQL side — a type-mapping miss makes every load of that column
fail or silently narrow the value. These are pure, DB-free functions.
"""
import pytest

from ch_sink_tools.db_load.postgres_type_mapper import map_pg_type
from ch_sink_tools.db_dump.postgres_dumper import filter_tables_by_regex


class TestMapPgType:
    def test_integer_family(self):
        assert map_pg_type("smallint") == "Int16"
        assert map_pg_type("integer") == "Int32"
        assert map_pg_type("bigint") == "Int64"

    def test_boolean_and_float(self):
        assert map_pg_type("boolean") == "UInt8"
        assert map_pg_type("real") == "Float32"
        assert map_pg_type("double precision") == "Float64"

    def test_numeric_with_precision_scale(self):
        assert map_pg_type("numeric", numeric_precision=10, numeric_scale=2) == "Decimal(10, 2)"

    def test_numeric_precision_embedded_in_text(self):
        assert map_pg_type("numeric(30,15)") == "Decimal(30, 15)"

    def test_bare_numeric_safe_default(self):
        assert map_pg_type("numeric") == "Decimal(18, 6)"

    def test_timestamp_maps_to_datetime64_utc(self):
        assert map_pg_type("timestamp with time zone") == "DateTime64(6, 'UTC')"

    def test_time_and_interval_are_strings(self):
        assert map_pg_type("time without time zone") == "String"
        assert map_pg_type("interval") == "String"

    def test_varchar_and_text_are_strings(self):
        assert map_pg_type("character varying") == "String"
        assert map_pg_type("varchar(255)") == "String"
        assert map_pg_type("text") == "String"

    def test_array_is_string(self):
        assert map_pg_type("integer[]") == "String"

    def test_uuid_is_string(self):
        assert map_pg_type("uuid") == "String"

    def test_nullable_wrapping(self):
        assert map_pg_type("integer", nullable=True) == "Nullable(Int32)"
        assert map_pg_type("numeric", numeric_precision=10, numeric_scale=2,
                           nullable=True) == "Nullable(Decimal(10, 2))"

    def test_unknown_type_falls_back_to_string(self):
        assert map_pg_type("some_custom_enum_xyz") == "String"


class TestFilterTablesByRegex:
    TABLES = ["trade", "trade_hist", "position", "audit_log"]

    def test_include_only(self):
        assert filter_tables_by_regex(self.TABLES, include_pattern="^trade") == ["trade", "trade_hist"]

    def test_exclude_only(self):
        assert filter_tables_by_regex(self.TABLES, exclude_pattern="_hist$|_log$") == ["trade", "position"]

    def test_include_and_exclude(self):
        assert filter_tables_by_regex(self.TABLES, include_pattern="^trade",
                                      exclude_pattern="_hist$") == ["trade"]

    def test_no_patterns_returns_all(self):
        assert filter_tables_by_regex(self.TABLES) == self.TABLES
