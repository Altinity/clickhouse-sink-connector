"""The two load-path datetime clamps carry a bound the COLUMN can hold.

A DateTime64(9) stores the instant as an Int64 count of nanoseconds, so its last
representable instant is 2262-04-11 23:47:16.854775807.  The range text the
other scales use (2299-12-31 23:59:59) is past that, and parsing it at
precision 9 does not saturate, it fails:

    toDateTime64('2299-12-31 23:59:59', 9, 'UTC')
        -> Code: 407. DB::Exception: Decimal math overflow
    parseDateTime64BestEffortOrNull('2299-12-31 23:59:59', 9, 'UTC')
        -> NULL

Both measured on 24.8.14.10545.altinitystable.  The PostgreSQL loader passes
that NULL to its throwIf guard, so it refused rows the streaming connector
accepts; the MySQL loader's expression could not be built at all.  These tests
pin the bound TEXT each expression embeds, which is the thing that was wrong;
the expressions themselves were verified end to end against a live server.

Run from sink-connector/python:  python -m pytest tests/test_datetime_clamp.py
"""
import os
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # sink-connector/python
sys.path.insert(0, ROOT)

from ch_sink_tools.db.checksum_common import (  # noqa: E402
    DATETIME64_MAX_UTC,
    DATETIME64_MAX_UTC_SCALE9,
    datetime64_max_text,
)
from ch_sink_tools.db_load.clickhouse_loader import datetime_clamp_expression  # noqa: E402
from ch_sink_tools.db_load.postgres_type_mapper import _datetime64_expr  # noqa: E402


class TestMaxTextPerPrecision:
    """One bound text per precision, and only precision 9 differs."""

    def test_scales_zero_to_eight_keep_the_range_text(self):
        for precision in range(9):
            assert datetime64_max_text(precision) == DATETIME64_MAX_UTC

    def test_scale_nine_takes_the_int64_nanosecond_limit(self):
        assert datetime64_max_text(9) == DATETIME64_MAX_UTC_SCALE9

    def test_scale_nine_text_is_not_the_range_text(self):
        # The defect was one text used for both; the two must never become equal.
        assert DATETIME64_MAX_UTC_SCALE9 != DATETIME64_MAX_UTC

    def test_accepts_the_decimal_string_a_declared_type_yields(self):
        # Both callers read the scale out of a type string with a regex, so the
        # scale arrives as a str.
        assert datetime64_max_text("9") == DATETIME64_MAX_UTC_SCALE9
        assert datetime64_max_text("6") == DATETIME64_MAX_UTC

    def test_scale_nine_bound_has_nanosecond_digits(self):
        # 2262-04-11 23:47:16.854775807 is the last Int64 nanosecond instant:
        # dropping or rounding those digits moves the bound off the type's end.
        assert DATETIME64_MAX_UTC_SCALE9 == "2262-04-11 23:47:16.854775807"


class TestPostgresExpression:
    """_datetime64_expr embeds a text its own precision can parse."""

    def test_scale_nine_uses_the_nanosecond_bound(self):
        expr = _datetime64_expr("ts", 0, 9, "UTC", refuse_bc=False)
        assert DATETIME64_MAX_UTC_SCALE9 in expr
        assert DATETIME64_MAX_UTC not in expr

    @pytest.mark.parametrize("precision", [0, 1, 3, 6, 8])
    def test_other_scales_keep_the_range_bound(self, precision):
        expr = _datetime64_expr("ts", 0, precision, "UTC", refuse_bc=False)
        assert DATETIME64_MAX_UTC in expr
        assert DATETIME64_MAX_UTC_SCALE9 not in expr

    def test_scale_nine_saturates_values_inside_the_year_cutoff(self):
        # The year test is 2299, which cannot catch a 2263 value: the value
        # reaches the parse, comes back NULL and is refused. The extra
        # comparison is what makes 2263-06-01 saturate instead.
        expr = _datetime64_expr("ts", 0, 9, "UTC", refuse_bc=False)
        assert f'"ts" >= \'{DATETIME64_MAX_UTC_SCALE9}\'' in expr

    @pytest.mark.parametrize("precision", [0, 3, 6, 8])
    def test_other_scales_need_no_extra_comparison(self, precision):
        # For scales 0..8 the year cutoff (2299) is inside the type's range, so
        # it already covers every value the column cannot hold.
        expr = _datetime64_expr("ts", 0, precision, "UTC", refuse_bc=False)
        assert f'"ts" >= \'{DATETIME64_MAX_UTC}\'' not in expr

    def test_column_zone_still_reaches_the_bounds(self):
        expr = _datetime64_expr("ts", 0, 9, "America/Chicago", refuse_bc=False)
        assert "America/Chicago" in expr


class TestMysqlExpression:
    """datetime_clamp_expression takes its precision from the target column."""

    @staticmethod
    def _column(datatype="datetime"):
        return {"column_name": "ts", "datatype": datatype, "nullable": True}

    def test_target_column_precision_decides_the_high_bound(self):
        expr = datetime_clamp_expression(
            self._column(), "`ts`", {"ts": "DateTime64(9, 'UTC')"})
        assert DATETIME64_MAX_UTC_SCALE9 in expr
        assert DATETIME64_MAX_UTC not in expr

    def test_scale_six_target_keeps_the_range_bound(self):
        expr = datetime_clamp_expression(
            self._column(), "`ts`", {"ts": "DateTime64(6, 'America/Chicago')"})
        assert DATETIME64_MAX_UTC in expr
        assert DATETIME64_MAX_UTC_SCALE9 not in expr

    def test_unknown_target_falls_back_to_the_dump_type(self):
        # Without target_types the loader translates the source type itself, so
        # a DateTime64(9) declared in the dump must still take the scale-9 bound.
        expr = datetime_clamp_expression(self._column("DateTime64(9)"), "`ts`")
        assert DATETIME64_MAX_UTC_SCALE9 in expr

    def test_nullable_target_is_read_through_the_wrapper(self):
        expr = datetime_clamp_expression(
            self._column(), "`ts`", {"ts": "Nullable(DateTime64(9, 'UTC'))"})
        assert DATETIME64_MAX_UTC_SCALE9 in expr

    def test_both_bounds_are_instants_not_wall_clocks(self):
        # Each bound is cast from a UTC literal through the column type, so the
        # column zone cannot move the instant that is stored. The two bounds are
        # the only toDateTime64() calls; the third CAST passes the value through.
        expr = datetime_clamp_expression(
            self._column(), "`ts`", {"ts": "DateTime64(9, 'UTC')"})
        assert expr.count("CAST(toDateTime64(") == 2
        assert expr.count(", 9, 'UTC') AS DateTime64(9, 'UTC')") == 2
