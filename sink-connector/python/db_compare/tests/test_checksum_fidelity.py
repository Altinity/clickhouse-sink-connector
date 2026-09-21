"""Offline fidelity tests for the db_compare checksum tools (spec 11.02).

Every test stubs the database layer (``execute_sql`` / ``execute_mysql`` and the
connection helpers). The stubs return the catalog rows a real engine would
return for a fixture table and the aggregate a real engine would compute over a
fixture of canonical row strings. No test opens a connection.
"""
import argparse
import hashlib
import os
import re
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import db_compare.clickhouse_table_checksum as ch  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402
import db_compare.top_level_table_checksum as tl  # noqa: E402
from db.checksum_common import (  # noqa: E402
    DATETIME_MAX, DATETIME_MIN, canonical_datetime_bound, checksum_from_aggregate,
    clamp_datetime_expression, clamped_datetime_flag, shift_datetime_bounds,
)

CHECKSUM_LINE_RE = re.compile(
    r"Checksum for table (?P<db>\S+?)\.(?P<table>\S+?) = (?P<md5>[0-9a-f]{32}) count (?P<count>\d+)"
)


def reference_aggregate(row_strings):
    """What both aggregate queries compute for a set of canonical row strings.

    Spec 11.02 section 3.5: per row md5 as 32 hex chars, split into four 32-bit
    words read as unsigned integers, summed over the rows; plus the row count.
    """
    a = b = c = d = 0
    for row in row_strings:
        digest = hashlib.md5(row.encode("utf-8")).hexdigest()
        a += int(digest[0:8], 16)
        b += int(digest[8:16], 16)
        c += int(digest[16:24], 16)
        d += int(digest[24:32], 16)
    return (len(row_strings), a, b, c, d)


def clickhouse_args(**overrides):
    """An ``args`` namespace with the parser defaults of clickhouse_table_checksum."""
    values = dict(
        clickhouse_host="clickhouse-host", clickhouse_database="db1",
        clickhouse_port=9000, secure=False, sign_column="", tables_regex=".",
        where=None, order_by=None, partition_key=None, ignore_tables_regex=None,
        no_wc=False, debug_output=False, debug_limit=None, hex_columns=[],
        debug=False, exclude_columns=["_sign,_version,is_deleted,_is_deleted"],
        threads=1, min_datetime_value=DATETIME_MIN,
        max_datetime_value=DATETIME_MAX, max_memory_usage=None,
        include_floating_point_columns=False, include_json_columns=True,
        source_timezone="UTC", timestamp_columns="",
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def mysql_args(**overrides):
    """An ``args`` namespace with the parser defaults of mysql_table_checksum."""
    values = dict(
        mysql_host="mysql-host", mysql_user="u", mysql_password="p",
        defaults_file=None, mysql_database="db1", mysql_port=3306,
        tables_regex=".", where=None, order_by=None, ignore_tables_regex=None,
        no_wc=False, debug_output=False, debug_limit=None, binary_encoding="hex",
        min_date_value="1900-01-01", max_date_value="2299-12-31",
        min_datetime_value=DATETIME_MIN,
        max_datetime_value=DATETIME_MAX, debug=False,
        exclude_columns=[], threads_per_table=1, chunk_size=10000, threads=1,
        include_floating_point_columns=False, include_json_columns=True,
        source_timezone="UTC",
    )
    values.update(overrides)
    return argparse.Namespace(**values)


class FakeMySQLRowset:
    """The subset of a SQLAlchemy CursorResult the MySQL script touches."""

    def __init__(self, rows=None, dict_rows=None, returns_rows=True):
        self.rows = rows or []
        self.dict_rows = dict_rows or []
        self.returns_rows = returns_rows

    def __iter__(self):
        return iter(self.rows)

    def mappings(self):
        return list(self.dict_rows)


# Fixture table: id INT NOT NULL, name VARCHAR NOT NULL, f DOUBLE NOT NULL.
# The last column is floating point and therefore skipped by default on both
# sides, which is exactly the shape that produced the dangling '#' (spec 11.02
# section 3.3).
CLICKHOUSE_COLUMNS = [("id", "Int32", 0, None), ("name", "String", 0, None), ("f", "Float64", 0, None)]


def mysql_column(name, data_type, column_type=None, nullable="NO", collation=None, precision=None):
    """One information_schema.columns row, keyed by the real catalog column
    names. The stub projects it through the select list of the query the script
    actually issues (``COLUMN_TYPE as data_type`` and the like), so a test sees
    exactly what the engine would have returned for that query."""
    return {
        "COLUMN_NAME": name, "DATA_TYPE": data_type,
        "COLUMN_TYPE": column_type if column_type is not None else data_type,
        "IS_NULLABLE": nullable, "COLLATION_NAME": collation, "DATETIME_PRECISION": precision,
    }


def project_information_schema(sql, truth_rows):
    """Apply the ``<CATALOG_COLUMN> as <alias>`` select list of ``sql`` to rows
    keyed by catalog column name, as MySQL would."""
    select_list = re.search(r"select\s+(.*?)\s+from\s+information_schema\.columns", sql, re.I | re.S).group(1)
    projection = []
    for item in select_list.split(","):
        m = re.match(r"\s*(\w+)\s+as\s+(\w+)\s*$", item, re.I)
        if not m:
            raise AssertionError("select item without alias in test stub: " + item)
        projection.append((m.group(1).upper(), m.group(2)))
    projected = []
    for truth in truth_rows:
        if any(source not in truth for source, alias in projection):
            raise AssertionError("query selects a catalog column the fixture does not carry: " + select_list)
        projected.append({alias: truth[source] for source, alias in projection})
    return projected


MYSQL_COLUMNS = [
    mysql_column("id", "int"),
    mysql_column("name", "varchar", "varchar(32)", collation="utf8mb4_0900_ai_ci"),
    mysql_column("f", "double"),
]
FIXTURE_ROWS = ["1#bob", "2#alice", "3#carol"]


def clickhouse_stub(columns, row_strings, clamped=0):
    """execute_sql replacement returning catalog rows and the fixture aggregate."""

    def execute_sql(conn, sql):
        lowered = sql.lower()
        if "is_in_primary_key" in lowered:
            rows = [(name,) for (name, *_) in columns if name == "id"]
        elif "from system.columns" in lowered:
            rows = list(columns)
        elif "partition_key" in lowered:
            rows = [("",)]
        elif 'count(*) as "cnt"' in lowered:
            rows = [reference_aggregate(row_strings) + (clamped,)]
        elif lowered.startswith("select count(*) cnt from"):
            rows = [(len(row_strings),)]
        else:
            raise AssertionError("unexpected ClickHouse statement in test: " + sql)
        return (rows, len(rows))

    return execute_sql


def mysql_stub(columns, row_strings, clamped=0):
    """execute_mysql replacement returning catalog rows and the fixture aggregate."""

    def execute_mysql(conn, sql):
        lowered = sql.strip().lower()
        if "information_schema.columns" in lowered:
            return (FakeMySQLRowset(dict_rows=project_information_schema(sql, columns)), -1)
        if lowered.startswith("set "):
            return (FakeMySQLRowset(returns_rows=False), -1)
        if 'count(*) as "cnt"' in lowered:
            return (FakeMySQLRowset(rows=[reference_aggregate(row_strings) + (clamped,)]), -1)
        raise AssertionError("unexpected MySQL statement in test: " + sql)

    return execute_mysql


def run_clickhouse_side(columns, row_strings, clamped=0, **arg_overrides):
    """Drive clickhouse_table_checksum.calculate_checksum with stubbed engine."""
    ch.args = clickhouse_args(**arg_overrides)
    with patch.object(ch, "get_connection", return_value=MagicMock()), \
            patch.object(ch, "execute_sql", side_effect=clickhouse_stub(columns, row_strings, clamped)), \
            unittest.TestCase().assertLogs(level="INFO") as logs:
        ch.calculate_checksum("t1", "user", "pw", None, None)
    return logs.output


def run_mysql_side(columns, row_strings, clamped=0, **arg_overrides):
    """Drive mysql_table_checksum.calculate_checksum with stubbed engine."""
    my.args = mysql_args(**arg_overrides)
    with patch.object(my, "get_mysql_connection", return_value=MagicMock()), \
            patch.object(my, "mysql_pk_columns", return_value=[]), \
            patch.object(my, "execute_mysql", side_effect=mysql_stub(columns, row_strings, clamped)), \
            unittest.TestCase().assertLogs(level="INFO") as logs:
        my.calculate_checksum("t1", "user", "pw", my.args.exclude_columns,
                              my.args.include_floating_point_columns,
                              my.args.include_json_columns)
    return logs.output


def parse_checksum_line(log_lines):
    matches = [CHECKSUM_LINE_RE.search(line) for line in log_lines]
    matches = [m for m in matches if m]
    if len(matches) != 1:
        raise AssertionError("expected exactly one checksum line, got: %r" % (log_lines,))
    return (matches[0].group("md5"), int(matches[0].group("count")))


class TestChecksumFromAggregate(unittest.TestCase):
    def test_is_md5_of_hash_separated_values(self):
        self.assertEqual(
            checksum_from_aggregate(2, 10, 20, 30, 40),
            hashlib.md5(b"2#10#20#30#40#").hexdigest(),
        )
        # An empty table is the same value on both sides.
        self.assertEqual(
            checksum_from_aggregate(0, 0, 0, 0, 0),
            hashlib.md5(b"0#0#0#0#0#").hexdigest(),
        )


class TestClickHouseRowExpression(unittest.TestCase):
    """The built ClickHouse expression, asserted through get_table_checksum_query
    with execute_sql stubbed (spec 11.02 section 3.3)."""

    def build(self, columns, **arg_overrides):
        ch.args = clickhouse_args(**arg_overrides)
        with patch.object(ch, "execute_sql", side_effect=clickhouse_stub(columns, [])):
            (query, select, order_by, external_types, clamped) = ch.get_table_checksum_query(MagicMock(), "t1")
        return select

    def test_trailing_float_column_leaves_no_dangling_separator(self):
        select = self.build(CLICKHOUSE_COLUMNS)
        self.assertEqual(select, """toString("id")||'#'||toString("name")""")

    def test_leading_and_middle_float_columns_leave_no_double_separator(self):
        columns = [("f0", "Float32", 0, None), ("id", "Int32", 0, None),
                   ("f1", "Float64", 0, None), ("name", "String", 0, None)]
        select = self.build(columns)
        self.assertEqual(select, """toString("id")||'#'||toString("name")""")

    def test_nullable_flags_are_one_trailing_element(self):
        columns = [("id", "Int32", 0, None), ("name", "Nullable(String)", 1, None),
                   ("f", "Float64", 0, None)]
        select = self.build(columns)
        self.assertEqual(
            select,
            'toString("id")'
            "||'#'||"
            """case when "name" is null then '' else toString("name") end"""
            "||'#'||"
            """case when "name" is null then '1' else '0' end""",
        )


def build_mysql_select(columns, excluded_columns=(), **arg_overrides):
    """The MySQL concat_ws argument list, through get_table_checksum_query with
    execute_mysql stubbed."""
    my.args = mysql_args(**arg_overrides)
    with patch.object(my, "execute_mysql", side_effect=mysql_stub(columns, [])):
        (query, select, order_by, external_types, clamped) = my.get_table_checksum_query(
            "t1", MagicMock(), my.args.binary_encoding, "1=1", list(excluded_columns),
            my.args.include_floating_point_columns, my.args.include_json_columns)
    return select


class TestMySQLColumnClassification(unittest.TestCase):
    """Columns are classified on information_schema DATA_TYPE, never by
    substring on COLUMN_TYPE (spec 11.02 section 3.3)."""

    def test_enum_labels_do_not_classify_the_column(self):
        columns = [
            mysql_column("id", "int"),
            mysql_column("kind", "enum", "enum('float','json','blob','bit','time')",
                         collation="utf8mb4_0900_ai_ci"),
        ]
        # An enum is a string column: not skipped as float, not JSON-normalised,
        # not hex-encoded, not cast as time.
        self.assertEqual(build_mysql_select(columns), "`id`,`kind`")

    def test_set_labels_do_not_classify_the_column(self):
        columns = [mysql_column("flags", "set", "set('double','binary')", collation="utf8mb4_0900_ai_ci")]
        self.assertEqual(build_mysql_select(columns), "`flags`")

    def test_real_types_are_still_classified(self):
        columns = [
            mysql_column("f", "double"),
            mysql_column("b", "blob"),
            mysql_column("j", "json"),
            mysql_column("t", "time", "time(3)", precision=3),
        ]
        select = build_mysql_select(columns)
        self.assertNotIn("`f`", select, "floating point stays skipped")
        self.assertIn("lower(hex(cast(`b` as binary)))", select)
        self.assertIn("json_pretty(`j`)", select)
        self.assertIn("cast(`t` as time(6))", select)


class TestMySQLTemporalRendering(unittest.TestCase):
    """Fixed-precision temporal text on the MySQL side (spec 11.02 section 3.3)."""

    def test_time_is_rendered_with_six_fraction_digits_for_every_precision(self):
        # The connector stores TIME as [-]HH:MM:SS.ffffff whatever the declared
        # precision (spec 07.03 section 3.2); the old substr(..., length(col))
        # truncated time(0) to '10:00:00' and reported DIFFERENT.
        for precision in (None, 0, 3, 6):
            column_type = "time" if not precision else f"time({precision})"
            select = build_mysql_select([mysql_column("t", "time", column_type, precision=precision)])
            self.assertEqual(select, "cast(`t` as time(6))", column_type)


MYSQL_DATETIME_RENDERING = "date_format(`d`, '%Y-%m-%d %H:%i:%s.%f')"
CLICKHOUSE_DATETIME_RENDERING = 'toString(toDateTime64("d", 6), \'UTC\')'
TOKYO_BOUNDS = ("1900-01-01 09:00:00.000000", "2300-01-01 08:59:59.000000")


class TestInstantComparison(unittest.TestCase):
    """TIMESTAMP compares as an instant in UTC; DATETIME as the wall clock of
    --source_timezone (spec 11.02 section 3.4)."""

    def test_mysql_session_renders_timestamps_in_utc(self):
        my.args = mysql_args()
        statements = my.select_table_statements("t1", "q", "`id`", "", "", "1=1", "0")
        self.assertIn("set time_zone = '+00:00'", statements)
        self.assertLess(statements.index("set time_zone = '+00:00'"), len(statements) - 1,
                        "the session zone must be set before the aggregate query")

    def test_bounds_shift_into_the_source_zone(self):
        self.assertEqual(shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "UTC"), (DATETIME_MIN, DATETIME_MAX))
        self.assertEqual(shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "Asia/Tokyo"), TOKYO_BOUNDS)
        with self.assertRaises(ValueError):
            shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "CST")

    def test_mysql_datetime_uses_shifted_bounds_and_timestamp_utc_bounds(self):
        datetime_select = build_mysql_select([mysql_column("d", "datetime", precision=0)], source_timezone="Asia/Tokyo")
        self.assertEqual(datetime_select,
                         clamp_datetime_expression(MYSQL_DATETIME_RENDERING, TOKYO_BOUNDS[0], TOKYO_BOUNDS[1], "mysql"))
        timestamp_select = build_mysql_select([mysql_column("d", "timestamp", precision=0)], source_timezone="Asia/Tokyo")
        self.assertEqual(timestamp_select,
                         clamp_datetime_expression(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "mysql"))

    def test_clickhouse_renders_timestamp_columns_in_utc_and_the_rest_in_the_source_zone(self):
        build = TestClickHouseRowExpression().build
        timestamp_select = build([("d", "DateTime64(6, 'UTC')", 0, None)],
                                 source_timezone="Asia/Tokyo", timestamp_columns="d,other")
        self.assertEqual(timestamp_select,
                         clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse"))
        datetime_select = build([("d", "DateTime64(3)", 0, None)], source_timezone="Asia/Tokyo", timestamp_columns="other")
        self.assertEqual(datetime_select,
                         clamp_datetime_expression('toString(toDateTime64("d", 6), \'Asia/Tokyo\')',
                                                   TOKYO_BOUNDS[0], TOKYO_BOUNDS[1], "clickhouse"))

    def test_clickhouse_default_zone_is_utc_for_every_column(self):
        select = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)])
        self.assertEqual(select, clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse"))

    def test_driver_passes_zone_and_timestamp_columns(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="Asia/Tokyo")
        mysql_cmd = tl.get_mysql_checksum_command("mysql-host", "db1", "t1", "id", 10, None)
        self.assertIn("--source_timezone Asia/Tokyo", mysql_cmd)
        clickhouse_cmd = tl.get_clickhouse_checksum_command("clickhouse-host", "db1", "t1", "id", 10,
                                                            timestamp_columns=["created_at", "updated_at"])
        self.assertIn("--source_timezone Asia/Tokyo", clickhouse_cmd)
        self.assertIn("--timestamp_columns created_at,updated_at", clickhouse_cmd)
        self.assertNotIn("--timestamp_columns", tl.get_clickhouse_checksum_command("clickhouse-host", "db1", "t1", "id", 10))

    def test_driver_resolves_the_source_zone_from_mysql(self):
        def zones(session_zone, system_zone):
            def execute_mysql(conn, sql):
                self.assertIn("@@session.time_zone", sql)
                return (FakeMySQLRowset(dict_rows=[{"session_time_zone": session_zone, "system_time_zone": system_zone}]), -1)
            return execute_mysql

        with patch.object(tl, "execute_mysql", side_effect=zones("SYSTEM", "UTC")):
            self.assertEqual(tl.resolve_source_timezone(MagicMock(), None), "UTC")
        with patch.object(tl, "execute_mysql", side_effect=zones("Asia/Tokyo", "UTC")):
            self.assertEqual(tl.resolve_source_timezone(MagicMock(), None), "Asia/Tokyo")
        with patch.object(tl, "execute_mysql", side_effect=zones("SYSTEM", "CST")):
            with self.assertRaises(ValueError):
                tl.resolve_source_timezone(MagicMock(), None)
        with patch.object(tl, "execute_mysql", side_effect=AssertionError("must not query when given")):
            self.assertEqual(tl.resolve_source_timezone(MagicMock(), "Europe/Berlin"), "Europe/Berlin")


class TestSharedDatetimeClamp(unittest.TestCase):
    """One clamp definition for both sides (spec 11.02 section 3.4)."""

    def test_bounds_are_the_connector_datetime64_range(self):
        # DataTypeRange.DATETIME64_MIN / DATETIME64_MAX in the canonical rendering.
        self.assertEqual(DATETIME_MIN, "1900-01-01 00:00:00.000000")
        self.assertEqual(DATETIME_MAX, "2299-12-31 23:59:59.000000")

    def test_user_bounds_are_canonicalised_and_confined(self):
        self.assertEqual(canonical_datetime_bound("1969-12-31 18:00:00", "--min_datetime_value"),
                         "1969-12-31 18:00:00.000000")
        self.assertEqual(canonical_datetime_bound("2299-12-31", "--max_datetime_value"),
                         "2299-12-31 00:00:00.000000")
        self.assertEqual(canonical_datetime_bound("2299-12-31 23:59:59.000000", "--max_datetime_value"),
                         DATETIME_MAX)
        # Bounds outside the ClickHouse range are pulled back to it.
        self.assertEqual(canonical_datetime_bound("1800-01-01 00:00:00", "--min_datetime_value"), DATETIME_MIN)
        self.assertEqual(canonical_datetime_bound("9999-12-31 23:59:59", "--max_datetime_value"), DATETIME_MAX)
        with self.assertRaises(ValueError):
            canonical_datetime_bound("yesterday", "--min_datetime_value")

    def test_both_dialects_clamp_with_the_same_comparisons(self):
        mysql = clamp_datetime_expression("r", "MIN", "MAX", "mysql")
        clickhouse = clamp_datetime_expression("r", "MIN", "MAX", "clickhouse")
        self.assertEqual(mysql, "case when r >= 'MAX' then 'MAX' when r < 'MIN' then 'MIN' else r end")
        self.assertEqual(clickhouse, "if(r >= 'MAX', 'MAX', if(r < 'MIN', 'MIN', r))")
        for expression in (mysql, clickhouse):
            self.assertIn("r >= 'MAX'", expression)
            self.assertIn("r < 'MIN'", expression)
            self.assertNotIn("<=", expression)
        # The flag counts values the clamp actually changed: strictly outside.
        self.assertEqual(clamped_datetime_flag("r", "MIN", "MAX"), "(r > 'MAX' or r < 'MIN')")


class TestDatetimeRendering(unittest.TestCase):
    """Fixed-precision rendering, no trailing-zero trimming (spec 11.02 section 3.4)."""

    def test_mysql_datetime_and_timestamp_render_six_digits_for_every_precision(self):
        for data_type in ("datetime", "timestamp"):
            for precision in (0, 3, 6):
                column_type = data_type if precision == 0 else f"{data_type}({precision})"
                select = build_mysql_select([mysql_column("d", data_type, column_type, precision=precision)])
                self.assertEqual(
                    select,
                    clamp_datetime_expression(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "mysql"),
                    column_type,
                )

    def test_clickhouse_datetime_types_render_six_digits(self):
        for data_type in ("DateTime", "DateTime64(3)", "DateTime64(6, 'UTC')", "DateTime64(0)"):
            select = TestClickHouseRowExpression().build([("d", data_type, 0, None)])
            self.assertEqual(
                select,
                clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse"),
                data_type,
            )

    def test_nullable_clickhouse_datetime_keeps_the_null_wrapper(self):
        select = TestClickHouseRowExpression().build([("d", "Nullable(DateTime64(3))", 1, None)])
        self.assertTrue(select.startswith('case when "d" is null then \'\' else '), select)
        self.assertIn(CLICKHOUSE_DATETIME_RENDERING, select)

    def test_no_trimming_anywhere(self):
        mysql = build_mysql_select([mysql_column("d", "datetime", precision=0),
                                    mysql_column("t", "timestamp", "timestamp(3)", precision=3)])
        clickhouse = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)])
        for expression in (mysql, clickhouse):
            self.assertNotIn("TRIM", expression.upper())
            self.assertNotIn("substr", expression)

    def test_user_bounds_apply_identically_on_both_sides(self):
        bound = "1969-12-31 18:00:00"
        mysql = build_mysql_select([mysql_column("d", "datetime", precision=0)], min_datetime_value=bound)
        clickhouse = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)], min_datetime_value=bound)
        self.assertIn("< '1969-12-31 18:00:00.000000'", mysql)
        self.assertIn("< '1969-12-31 18:00:00.000000'", clickhouse)


class TestClampedRowCounts(unittest.TestCase):
    """The aggregate carries how many values the clamp changed; it is printed
    but never hashed (spec 11.02 section 3.4)."""

    def test_aggregate_queries_sum_the_clamped_flags(self):
        ch.args = clickhouse_args()
        with patch.object(ch, "execute_sql", side_effect=clickhouse_stub([("d", "DateTime64(3)", 0, None)], [])):
            (query, select, order_by, external_types, clamped) = ch.get_table_checksum_query(MagicMock(), "t1")
        self.assertEqual(clamped, "coalesce(" + clamped_datetime_flag(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX) + ", 0)")
        statement = ch.select_table_statements("t1", query, select, order_by, external_types, None, clamped)[0]
        self.assertIn('coalesce(sum(clamped),0) as "clamped"', statement)
        self.assertIn(clamped + " as clamped", statement)

        my.args = mysql_args()
        with patch.object(my, "execute_mysql", side_effect=mysql_stub([mysql_column("d", "datetime", precision=0)], [])):
            (query, select, order_by, external_types, clamped) = my.get_table_checksum_query(
                "t1", MagicMock(), "hex", "1=1", [], False, True)
        self.assertEqual(clamped, "coalesce(" + clamped_datetime_flag(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX) + ", 0)")
        statement = my.select_table_statements("t1", query, select, order_by, external_types, "1=1", clamped)[-1]
        self.assertIn("coalesce(sum(clamped),0) as clamped", statement)
        self.assertIn(clamped + " as clamped", statement)

    def test_columns_without_datetime_contribute_zero(self):
        ch.args = clickhouse_args()
        with patch.object(ch, "execute_sql", side_effect=clickhouse_stub(CLICKHOUSE_COLUMNS, [])):
            clamped = ch.get_table_checksum_query(MagicMock(), "t1")[4]
        self.assertEqual(clamped, "0")

    def test_clamped_count_is_printed_and_not_hashed(self):
        plain_mysql = parse_checksum_line(run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS))
        mysql_lines = run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS, clamped=2)
        clickhouse_lines = run_clickhouse_side(CLICKHOUSE_COLUMNS, FIXTURE_ROWS, clamped=2)
        for lines in (mysql_lines, clickhouse_lines):
            self.assertEqual(parse_checksum_line(lines), plain_mysql)
            warnings = [line for line in lines if line.startswith("WARNING")]
            self.assertTrue(any("2 out-of-range datetime values" in line for line in warnings), lines)
            # The driver greps the child output for "checksum" and expects one line.
            self.assertEqual(sum(1 for line in lines if "checksum" in line.lower()), 1, lines)
        self.assertFalse(any(line.startswith("WARNING") for line in run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS)))


class TestEndToEndChecksum(unittest.TestCase):
    """Both scripts, driven through their real code paths over stubbed engines."""

    def test_equal_fixtures_report_equal(self):
        mysql_result = parse_checksum_line(run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS))
        clickhouse_result = parse_checksum_line(run_clickhouse_side(CLICKHOUSE_COLUMNS, FIXTURE_ROWS))
        self.assertEqual(mysql_result, clickhouse_result)
        self.assertEqual(mysql_result[1], len(FIXTURE_ROWS))
        self.assertEqual(mysql_result[0], checksum_from_aggregate(*reference_aggregate(FIXTURE_ROWS)))

    def test_flipped_clickhouse_value_reports_different(self):
        flipped = list(FIXTURE_ROWS)
        flipped[1] = "2#alicf"  # one character of one value on the replica side
        mysql_result = parse_checksum_line(run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS))
        clickhouse_result = parse_checksum_line(run_clickhouse_side(CLICKHOUSE_COLUMNS, flipped))
        self.assertEqual(mysql_result[1], clickhouse_result[1], "row counts stay equal")
        self.assertNotEqual(mysql_result[0], clickhouse_result[0])

        results = [("mysql-host", "t1") + mysql_result, ("clickhouse-host", "t1") + clickhouse_result]
        with self.assertLogs(level="WARNING") as logs:
            tl.analyze_differences(results, "mysql-host", ["clickhouse-host"])
        self.assertTrue(any("Checksum difference" in line for line in logs.output), logs.output)

    def test_missing_row_on_clickhouse_reports_different(self):
        mysql_result = parse_checksum_line(run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS))
        clickhouse_result = parse_checksum_line(run_clickhouse_side(CLICKHOUSE_COLUMNS, FIXTURE_ROWS[:-1]))
        self.assertNotEqual(mysql_result, clickhouse_result)


if __name__ == "__main__":
    unittest.main()
