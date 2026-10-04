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
import sqlite3
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
    clamp_datetime_expression, clamped_datetime_flag, saturation_floor, shift_datetime_bounds,
)

def mysql_command_line(*args, **kwargs):
    """The driver's MySQL side command (an argv list) as one line of text."""
    return " ".join(tl.get_mysql_checksum_command(*args, **kwargs))


def clickhouse_command_line(*args, **kwargs):
    """The driver's ClickHouse side command (an argv list) as one line of text."""
    return " ".join(tl.get_clickhouse_checksum_command(*args, **kwargs))


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
        include_floating_point_columns=False, include_json_columns=False,
        source_timezone="UTC", timestamp_columns="", binary_encoding="hex", json_columns="",
        source_columns="",
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
        include_floating_point_columns=False, include_json_columns=False,
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


REPLACING_ENGINE = "ReplacingMergeTree(_version, is_deleted) ORDER BY id SETTINGS index_granularity = 8192"


def clickhouse_stub(columns, row_strings, clamped=0, engine_full=REPLACING_ENGINE):
    """execute_sql replacement returning catalog rows and the fixture aggregate.
    Every statement it receives is recorded in ``execute_sql.executed``."""

    def execute_sql(conn, sql):
        execute_sql.executed.append(sql)
        lowered = sql.lower()
        if "engine_full" in lowered:
            rows = [(engine_full,)]
        elif "is_in_primary_key" in lowered:
            rows = [(name,) for (name, *_) in columns if name == "id"]
        elif "from system.columns" in lowered:
            # (name, type, is_nullable, numeric_scale[, is_in_partition_key, is_in_sorting_key])
            rows = [tuple(column) + (0, 0) * ("is_in_partition_key" in lowered and len(column) == 4)
                    for column in columns]
        elif "partition_key" in lowered:
            rows = [("",)]
        elif 'count(*) as "cnt"' in lowered:
            rows = [reference_aggregate(row_strings) + (clamped,)]
        else:
            raise AssertionError("unexpected ClickHouse statement in test: " + sql)
        return (rows, len(rows))

    execute_sql.executed = []
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
            (query, select, order_by, external_types, clamped, final_per_partition) = ch.get_table_checksum_query(MagicMock(), "t1")
        return select

    def test_trailing_float_column_leaves_no_dangling_separator(self):
        select = self.build(CLICKHOUSE_COLUMNS)
        self.assertEqual(select, """toString("id")||'#'||toString("name")""" + clickhouse_flags("id", "name"))

    def test_leading_and_middle_float_columns_leave_no_double_separator(self):
        columns = [("f0", "Float32", 0, None), ("id", "Int32", 0, None),
                   ("f1", "Float64", 0, None), ("name", "String", 0, None)]
        select = self.build(columns)
        self.assertEqual(select, """toString("id")||'#'||toString("name")""" + clickhouse_flags("id", "name"))

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
            """case when "id" is null then '1' else '0' end || """
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


def mysql_flags(*columns):
    """The MySQL trailing null-flags element over the compared ``columns``
    (spec 13.06 D-13.06-40), preceded by its ',' separator."""
    return ",concat(" + ",".join("ISNULL(`" + column + "`)" for column in columns) + ")"


def clickhouse_flags(*columns):
    """The ClickHouse trailing null-flags element over the compared
    ``columns`` (spec 13.06 D-13.06-40), preceded by its ||'#'|| separator."""
    return "||'#'||" + " || ".join(
        'case when "' + column + "\" is null then '1' else '0' end" for column in columns)


CLICKHOUSE_FLAG_RE = re.compile(r"""^case when "(\w+)" is null then '1' else '0' end$""")
MYSQL_FLAGS_RE = re.compile(r"concat\(((?:ISNULL\(`\w+`\),?)+)\)$")


def clickhouse_flag_columns(select):
    """The columns of the trailing null-flags element of a ClickHouse row
    expression, in order; [] when the expression has no flags element."""
    terms = select.split("||'#'||")[-1].split(" || ")
    matches = [CLICKHOUSE_FLAG_RE.match(term) for term in terms]
    return [m.group(1) for m in matches] if all(matches) else []


def mysql_flag_columns(select):
    """The columns of the trailing null-flags element of a MySQL row
    expression, in order; [] when the expression has no flags element."""
    m = MYSQL_FLAGS_RE.search(select)
    return re.findall(r"ISNULL\(`(\w+)`\)", m.group(1)) if m else []


class TestNullFlagsOverEveryComparedColumn(unittest.TestCase):
    """The trailing null-flags element holds one value-based flag per compared
    column, the same columns on both sides, whatever each catalog declares
    nullable (spec 13.06 D-13.06-40, spec 11.02 section 3.3)."""

    def clickhouse_select(self, columns, **arg_overrides):
        ch.args = clickhouse_args(**arg_overrides)
        with patch.object(ch, "execute_sql", side_effect=clickhouse_stub(columns, [])):
            (query, select, order_by, external_types, clamped, final_per_partition) = ch.get_table_checksum_query(MagicMock(), "t1")
        return select

    def test_nullability_mismatch_gives_the_same_flags_on_both_sides(self):
        # A generated MySQL column is declared nullable but never holds NULL;
        # the replica declares its twin non-Nullable. Equal values must give
        # the same flags element: one flag per compared column on each side,
        # so a row without NULLs gives '000' on both.
        mysql_columns = [
            mysql_column("id", "int"),
            mysql_column("name", "varchar", "varchar(32)", nullable="YES", collation="utf8mb4_0900_ai_ci"),
            mysql_column("is_valid", "tinyint", "tinyint(1)", nullable="YES"),
        ]
        clickhouse_columns = [("id", "Int32", 0, None), ("name", "Nullable(String)", 1, None),
                              ("is_valid", "Int8", 0, None)]
        mysql_select = build_mysql_select(mysql_columns)
        clickhouse_select = self.clickhouse_select(clickhouse_columns)
        self.assertEqual(mysql_flag_columns(mysql_select), ["id", "name", "is_valid"])
        self.assertEqual(clickhouse_flag_columns(clickhouse_select), ["id", "name", "is_valid"])
        self.assertEqual(
            mysql_select,
            "`id`,ifnull(`name`,''),ifnull(`is_valid`,''),"
            "concat(ISNULL(`id`),ISNULL(`name`),ISNULL(`is_valid`))")
        self.assertEqual(
            clickhouse_select,
            'toString("id")'
            "||'#'||"
            """case when "name" is null then '' else toString("name") end"""
            "||'#'||"
            'toString("is_valid")'
            "||'#'||"
            """case when "id" is null then '1' else '0' end || """
            """case when "name" is null then '1' else '0' end || """
            """case when "is_valid" is null then '1' else '0' end""")

    def test_returned_nullables_are_still_the_declared_nullable_columns(self):
        my.args = mysql_args()
        rows = [{"column_name": "id", "data_type": "int", "column_type": "int", "is_nullable": "NO",
                 "collation": None, "datetime_precision": None},
                {"column_name": "is_valid", "data_type": "tinyint", "column_type": "tinyint(1)",
                 "is_nullable": "YES", "collation": None, "datetime_precision": None}]
        (select, nullables, data_types, clamped, skipped) = my.build_mysql_row_expression(
            rows, my.args, "hex", [], False, False)
        self.assertEqual(nullables, ["`is_valid`"])
        ch.args = clickhouse_args()
        (select, nullables, columns, data_types, clamped, skipped) = ch.build_clickhouse_row_expression(
            [("id", "Int32", 0, None), ("name", "Nullable(String)", 1, None)], ch.args)
        self.assertEqual(nullables, ['"name"'])

    def test_excluded_and_skipped_columns_contribute_no_flag(self):
        mysql_columns = [
            mysql_column("id", "int"),
            mysql_column("f", "double", nullable="YES"),
            mysql_column("j", "json", nullable="YES"),
            mysql_column("secret", "varchar", "varchar(8)", nullable="YES", collation="utf8mb4_0900_ai_ci"),
            mysql_column("name", "varchar", "varchar(32)", collation="utf8mb4_0900_ai_ci"),
        ]
        clickhouse_columns = [("id", "Int32", 0, None), ("f", "Nullable(Float64)", 1, None),
                              ("j", "Nullable(String)", 1, None), ("secret", "Nullable(String)", 1, None),
                              ("name", "String", 0, None)]
        mysql_select = build_mysql_select(mysql_columns, excluded_columns=["secret"])
        clickhouse_select = self.clickhouse_select(clickhouse_columns, exclude_columns=["secret"], json_columns="j")
        self.assertEqual(mysql_flag_columns(mysql_select), ["id", "name"])
        self.assertEqual(clickhouse_flag_columns(clickhouse_select), ["id", "name"])
        for skipped in ("f", "j", "secret"):
            self.assertNotIn("`" + skipped + "`", mysql_select)
            self.assertNotIn('"' + skipped + '"', clickhouse_select)

    def test_table_without_nullable_columns_gets_one_flag_per_column(self):
        mysql_select = build_mysql_select([
            mysql_column("id", "int"),
            mysql_column("name", "varchar", "varchar(32)", collation="utf8mb4_0900_ai_ci"),
        ])
        clickhouse_select = self.clickhouse_select([("id", "Int32", 0, None), ("name", "String", 0, None)])
        self.assertEqual(mysql_select, "`id`,`name`,concat(ISNULL(`id`),ISNULL(`name`))")
        self.assertEqual(
            clickhouse_select,
            """toString("id")||'#'||toString("name")||'#'||"""
            """case when "id" is null then '1' else '0' end || case when "name" is null then '1' else '0' end""")


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
        self.assertEqual(build_mysql_select(columns), "`id`,`kind`" + mysql_flags("id", "kind"))

    def test_set_labels_do_not_classify_the_column(self):
        columns = [mysql_column("flags", "set", "set('double','binary')", collation="utf8mb4_0900_ai_ci")]
        self.assertEqual(build_mysql_select(columns), "`flags`" + mysql_flags("flags"))

    def test_real_types_are_still_classified(self):
        columns = [
            mysql_column("f", "double"),
            mysql_column("b", "blob"),
            mysql_column("j", "json"),
            mysql_column("t", "time", "time(3)", precision=3),
        ]
        select = build_mysql_select(columns, include_json_columns=True)
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
            self.assertEqual(select, "cast(`t` as time(6))" + mysql_flags("t"), column_type)


MYSQL_DATETIME_RENDERING = "date_format(`d`, '%Y-%m-%d %H:%i:%s.%f')"
CLICKHOUSE_DATETIME_RENDERING = 'toString(toDateTime64("d", 6), \'UTC\')'
TOKYO_BOUNDS = ("1900-01-01 09:00:00.000000", "2300-01-01 08:59:59.000000")
# Start of the saturated last day (2299-12-31 00:00:00 UTC) in UTC and Asia/Tokyo.
UTC_FLOOR = "2299-12-31 00:00:00.000000"
TOKYO_FLOOR = "2299-12-31 09:00:00.000000"


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
                         clamp_datetime_expression(MYSQL_DATETIME_RENDERING, TOKYO_BOUNDS[0], TOKYO_BOUNDS[1], "mysql", TOKYO_FLOOR)
                         + mysql_flags("d"))
        timestamp_select = build_mysql_select([mysql_column("d", "timestamp", precision=0)], source_timezone="Asia/Tokyo")
        self.assertEqual(timestamp_select,
                         clamp_datetime_expression(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "mysql", UTC_FLOOR)
                         + mysql_flags("d"))

    def test_clickhouse_renders_timestamp_columns_in_utc_and_the_rest_in_the_source_zone(self):
        build = TestClickHouseRowExpression().build
        timestamp_select = build([("d", "DateTime64(6, 'UTC')", 0, None)],
                                 source_timezone="Asia/Tokyo", timestamp_columns="d,other")
        self.assertEqual(timestamp_select,
                         clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse", UTC_FLOOR)
                         + clickhouse_flags("d"))
        datetime_select = build([("d", "DateTime64(3)", 0, None)], source_timezone="Asia/Tokyo", timestamp_columns="other")
        self.assertEqual(datetime_select,
                         clamp_datetime_expression('toString(toDateTime64("d", 6), \'Asia/Tokyo\')',
                                                   TOKYO_BOUNDS[0], TOKYO_BOUNDS[1], "clickhouse", TOKYO_FLOOR)
                         + clickhouse_flags("d"))

    def test_clickhouse_default_zone_is_utc_for_every_column(self):
        select = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)])
        self.assertEqual(select, clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse", UTC_FLOOR)
                         + clickhouse_flags("d"))

    def test_driver_passes_zone_and_timestamp_columns(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="Asia/Tokyo",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        mysql_cmd = mysql_command_line("mysql-host", "db1", "t1", "id", 10, None)
        self.assertIn("--source_timezone Asia/Tokyo", mysql_cmd)
        clickhouse_cmd = clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10,
                                                            timestamp_columns=["created_at", "updated_at"])
        self.assertIn("--source_timezone Asia/Tokyo", clickhouse_cmd)
        self.assertIn("--timestamp_columns created_at,updated_at", clickhouse_cmd)
        self.assertNotIn("--timestamp_columns", clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10))

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


class TestSaturatedLastDay(unittest.TestCase):
    """The last day of the range is one value on both sides (spec 11.02
    section 3.4): what the connector stores for an out-of-range source value
    (2299-12-31 23:59:59) and what a bulk load stores (the source's own time of
    day on 2299-12-31) compare equal to every out-of-range MySQL value."""

    MYSQL_VALUES = ("9999-12-31 00:00:00.000000", "9999-12-31 23:59:59.000000", "9999-07-31 23:59:59.000000",
                    "2299-12-31 10:59:00.000000")
    CLICKHOUSE_VALUES = ("2299-12-31 00:00:00.000000", "2299-12-31 05:59:00.000000",
                         "2299-12-31 23:59:59.000000", "2299-12-31 23:59:59.999999")
    DAY_BEFORE = "2299-12-30 23:59:59.999999"

    def render(self, value, dialect, saturate_from=UTC_FLOOR):
        connection = sqlite3.connect(":memory:")
        connection.create_function("if", 3, lambda condition, then, otherwise: then if condition else otherwise)
        try:
            connection.execute("create table t (r text)")
            connection.execute("insert into t values (?)", (value,))
            expression = clamp_datetime_expression("r", DATETIME_MIN, DATETIME_MAX, dialect, saturate_from)
            flag = clamped_datetime_flag("r", DATETIME_MIN, DATETIME_MAX, saturate_from)
            return connection.execute(f"select {expression}, coalesce({flag}, 0) from t").fetchone()
        finally:
            connection.close()

    def test_floor_is_the_first_instant_of_the_last_utc_day_in_the_zone(self):
        bounds = (DATETIME_MIN, DATETIME_MAX)
        self.assertEqual(saturation_floor(bounds, "UTC"), UTC_FLOOR)
        self.assertEqual(saturation_floor(bounds, "Asia/Tokyo"), TOKYO_FLOOR)
        self.assertEqual(saturation_floor(bounds, "America/Chicago"), "2299-12-30 18:00:00.000000")
        # Never above the upper bound in the zone: a user bound at midnight UTC
        # shifted west is earlier than the start of its UTC day there.
        user_bounds = (DATETIME_MIN, canonical_datetime_bound("2299-12-31 00:00:00", "--max_datetime_value"))
        self.assertEqual(saturation_floor(user_bounds, "America/Chicago"),
                         shift_datetime_bounds(user_bounds, "America/Chicago")[1])

    def test_every_last_day_representation_renders_as_the_upper_bound(self):
        for dialect in ("mysql", "clickhouse"):
            for value in self.MYSQL_VALUES + self.CLICKHOUSE_VALUES:
                self.assertEqual(self.render(value, dialect)[0], DATETIME_MAX, (dialect, value))

    def test_the_day_before_is_not_saturated(self):
        for dialect in ("mysql", "clickhouse"):
            self.assertEqual(self.render(self.DAY_BEFORE, dialect), (self.DAY_BEFORE, 0), dialect)

    def test_the_flag_counts_every_value_the_saturation_changed(self):
        for dialect in ("mysql", "clickhouse"):
            self.assertEqual(self.render(DATETIME_MAX, dialect)[1], 0, dialect)
            for value in self.MYSQL_VALUES + self.CLICKHOUSE_VALUES:
                if value != DATETIME_MAX:
                    self.assertEqual(self.render(value, dialect)[1], 1, (dialect, value))

    def test_without_a_floor_only_values_past_the_bound_are_clamped(self):
        # The pre-saturation behaviour, still what a caller gets without a floor:
        # the bulk-loaded 00:00:00 stays itself and hashes differently.
        self.assertEqual(self.render("2299-12-31 00:00:00.000000", "clickhouse", None),
                         ("2299-12-31 00:00:00.000000", 0))
        self.assertEqual(self.render("9999-12-31 00:00:00.000000", "mysql", None), (DATETIME_MAX, 1))

    def test_row_builders_use_the_same_floor_on_both_sides(self):
        mysql = build_mysql_select([mysql_column("d", "datetime", precision=0)])
        clickhouse = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)])
        for expression in (mysql, clickhouse):
            self.assertIn(f">= '{UTC_FLOOR}'", expression)
            self.assertIn(f"'{DATETIME_MAX}'", expression)
        tokyo_mysql = build_mysql_select([mysql_column("d", "datetime", precision=0)], source_timezone="Asia/Tokyo")
        tokyo_clickhouse = TestClickHouseRowExpression().build([("d", "DateTime64(3)", 0, None)],
                                                               source_timezone="Asia/Tokyo")
        for expression in (tokyo_mysql, tokyo_clickhouse):
            self.assertIn(f">= '{TOKYO_FLOOR}'", expression)


class TestDatetimeRendering(unittest.TestCase):
    """Fixed-precision rendering, no trailing-zero trimming (spec 11.02 section 3.4)."""

    def test_mysql_datetime_and_timestamp_render_six_digits_for_every_precision(self):
        for data_type in ("datetime", "timestamp"):
            for precision in (0, 3, 6):
                column_type = data_type if precision == 0 else f"{data_type}({precision})"
                select = build_mysql_select([mysql_column("d", data_type, column_type, precision=precision)])
                self.assertEqual(
                    select,
                    clamp_datetime_expression(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "mysql", UTC_FLOOR)
                    + mysql_flags("d"),
                    column_type,
                )

    def test_clickhouse_datetime_types_render_six_digits(self):
        for data_type in ("DateTime", "DateTime64(3)", "DateTime64(6, 'UTC')", "DateTime64(0)"):
            select = TestClickHouseRowExpression().build([("d", data_type, 0, None)])
            self.assertEqual(
                select,
                clamp_datetime_expression(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, "clickhouse", UTC_FLOOR)
                + clickhouse_flags("d"),
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
            (query, select, order_by, external_types, clamped, final_per_partition) = ch.get_table_checksum_query(MagicMock(), "t1")
        self.assertEqual(clamped, "coalesce(" + clamped_datetime_flag(CLICKHOUSE_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, UTC_FLOOR) + ", 0)")
        statement = ch.select_table_statements("t1", query, select, order_by, external_types, None, clamped)[0]
        self.assertIn('coalesce(sum(clamped),0) as "clamped"', statement)
        self.assertIn(clamped + " as clamped", statement)

        my.args = mysql_args()
        with patch.object(my, "execute_mysql", side_effect=mysql_stub([mysql_column("d", "datetime", precision=0)], [])):
            (query, select, order_by, external_types, clamped) = my.get_table_checksum_query(
                "t1", MagicMock(), "hex", "1=1", [], False, True)
        self.assertEqual(clamped, "coalesce(" + clamped_datetime_flag(MYSQL_DATETIME_RENDERING, DATETIME_MIN, DATETIME_MAX, UTC_FLOOR) + ", 0)")
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
        self.assertFalse(any("out-of-range" in line for line in run_mysql_side(MYSQL_COLUMNS, FIXTURE_ROWS)))


class TestFloatAndJsonCoverage(unittest.TestCase):
    """Floats and JSON are excluded unless asked, and each table says so once
    (spec 11.02 section 3.9)."""

    MYSQL = [mysql_column("id", "int"), mysql_column("f", "float"), mysql_column("j", "json"),
             mysql_column("g", "double")]
    CLICKHOUSE = [("id", "Int32", 0, None), ("f", "Float32", 0, None), ("j", "String", 0, None),
                  ("g", "Float64", 0, None), ("o", "JSON", 0, None)]

    def mysql_select_and_warnings(self, **arg_overrides):
        my.warned_tables.clear()
        # DEBUG: the MySQL builder emits no INFO line of its own any more, and
        # assertLogs needs at least one record to capture anything.
        with self.assertLogs(level="DEBUG") as logs:
            select = build_mysql_select(self.MYSQL, **arg_overrides)
        return select, [line for line in logs.output if line.startswith("WARNING")]

    def clickhouse_select_and_warnings(self, **arg_overrides):
        ch.warned_tables.clear()
        with self.assertLogs(level="INFO") as logs:
            select = TestClickHouseRowExpression().build(self.CLICKHOUSE, **arg_overrides)
        return select, [line for line in logs.output if line.startswith("WARNING")]

    def test_mysql_skips_floats_and_json_by_default_and_warns_once_per_table(self):
        select, warnings = self.mysql_select_and_warnings()
        self.assertEqual(select, "`id`" + mysql_flags("id"))
        self.assertEqual(len(warnings), 2, warnings)
        self.assertTrue(any("Not compared in table db1.t1: floating point columns ['f', 'g']" in w for w in warnings), warnings)
        self.assertTrue(any("Not compared in table db1.t1: JSON columns ['j']" in w for w in warnings), warnings)
        # The only "checksum" is the standalone hint naming clickhouse_table_checksum.py (spec 13.06
        # D-13.06-41), which the driver drops when it relays the note; no warning reads as a result line.
        hint = "; pass --json_columns j to clickhouse_table_checksum.py so both row strings skip them"
        self.assertFalse(any("checksum" in w.replace(hint, "").lower() for w in warnings), warnings)
        self.assertFalse(any(CHECKSUM_LINE_RE.search(w) for w in warnings), warnings)
        # A second chunk of the same table does not repeat the warning.
        with self.assertLogs(level="DEBUG") as logs:
            build_mysql_select(self.MYSQL)
        self.assertFalse(any(line.startswith("WARNING") for line in logs.output), logs.output)

    def test_parser_defaults_exclude_json_on_both_sides(self):
        self.assertFalse(my.build_argument_parser().get_default("include_json_columns"))
        self.assertFalse(ch.build_argument_parser().get_default("include_json_columns"))
        self.assertFalse(my.build_argument_parser().get_default("include_floating_point_columns"))
        self.assertFalse(ch.build_argument_parser().get_default("include_floating_point_columns"))

    def test_mysql_opt_in_includes_them(self):
        select, warnings = self.mysql_select_and_warnings(include_floating_point_columns=True, include_json_columns=True)
        self.assertEqual(warnings, [])
        self.assertIn("`f`", select)
        self.assertIn("`g`", select)
        self.assertIn("json_pretty(`j`)", select)

    def test_mysql_json_normalisation_keeps_its_regex_escaping(self):
        # The SQL text must carry doubled backslashes: MySQL unescapes the string
        # literal before the regex engine sees it, so '\\.0\\b' in the SQL is the
        # regex \.0\b. Raw strings below show the SQL text as MySQL receives it.
        select = build_mysql_select([mysql_column("j", "json")], include_json_columns=True)
        self.assertIn(r"'\\.0\\b'", select)
        self.assertIn(r"'\":\\s(-*\\d|\\[|\\{|true|false)'".replace('\\"', '"'), select)
        self.assertIn(r"'\\s+(\".*?)\\s*'".replace('\\"', '"'), select)
        self.assertIn(r"'\\s*\\n\\s*'", select)
        self.assertIn(r"'\\\\u([0-9A-F]{3})a', '\\\\u$1A'", select)
        self.assertNotIn(r"'\.0\b'", select)

    def test_clickhouse_skips_floats_native_json_and_listed_json_strings_and_warns(self):
        select, warnings = self.clickhouse_select_and_warnings(json_columns="j")
        self.assertEqual(select, 'toString("id")' + clickhouse_flags("id"))
        self.assertEqual(len(warnings), 2, warnings)
        self.assertTrue(any("Not compared in table db1.t1: floating point columns ['f', 'g']" in w for w in warnings), warnings)
        self.assertTrue(any("Not compared in table db1.t1: JSON columns ['j', 'o']" in w for w in warnings), warnings)
        self.assertFalse(any("checksum" in w.lower() for w in warnings), warnings)

    def test_clickhouse_opt_in_includes_them(self):
        select, warnings = self.clickhouse_select_and_warnings(
            json_columns="j", include_floating_point_columns=True, include_json_columns=True)
        self.assertEqual(warnings, [])
        self.assertEqual(select, 'toString("id")||\'#\'||toString("f")||\'#\'||toString("j")||\'#\'||toString("g")||\'#\'||toString("o")'
                         + clickhouse_flags("id", "f", "j", "g", "o"))

    def test_driver_passes_json_columns_and_the_include_flags(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        mysql_cmd = mysql_command_line("mysql-host", "db1", "t1", "id", 10, None)
        clickhouse_cmd = clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10, json_columns=["j"])
        self.assertNotIn("--include_", mysql_cmd)
        self.assertNotIn("--include_", clickhouse_cmd)
        self.assertIn("--json_columns j", clickhouse_cmd)
        tl.args.include_floating_point_columns = True
        tl.args.include_json_columns = True
        mysql_cmd = mysql_command_line("mysql-host", "db1", "t1", "id", 10, None)
        clickhouse_cmd = clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10, json_columns=["j"])
        for cmd in (mysql_cmd, clickhouse_cmd):
            self.assertIn("--include_floating_point_columns", cmd)
            self.assertIn("--include_json_columns", cmd)


class TestReplicaOnlyColumns(unittest.TestCase):
    """A replica column the source table does not have is not compared, and
    each table says so once (spec 11.02 sections 3.3 and 3.9)."""

    # The source table: id, user. The replica added `name` on its own (a
    # DEFAULT expression over another column), added on ClickHouse only.
    CLICKHOUSE = [("id", "Int64", 0, None), ("user", "Nullable(String)", 1, None),
                  ("name", "Nullable(String)", 1, None)]

    def build_with_warnings(self, columns, **arg_overrides):
        ch.warned_tables.clear()
        with self.assertLogs(level="INFO") as logs:
            select = TestClickHouseRowExpression().build(columns, **arg_overrides)
        return select, [line for line in logs.output if line.startswith("WARNING")]

    def test_replica_only_column_is_left_out_and_named_once(self):
        shared, _ = self.build_with_warnings(self.CLICKHOUSE[:2])
        select, warnings = self.build_with_warnings(self.CLICKHOUSE, source_columns="id,user")
        self.assertEqual(select, shared)
        self.assertNotIn('"name"', select)
        self.assertEqual(len(warnings), 1, warnings)
        self.assertIn("Replica-only columns in table db1.t1: ['name']", warnings[0])
        # The driver's parser must recognise exactly this line (spec 11.02 section 3.9).
        match = tl.REPLICA_ONLY_RE.search(warnings[0])
        self.assertEqual((match.group("table"), match.group("columns")), ("db1.t1", "['name']"))
        # Relayed by the driver as a side note: it must not read as a result line.
        self.assertNotIn("checksum", warnings[0].lower())
        # A second chunk of the same table does not repeat the warning.
        with self.assertLogs(level="INFO") as logs:
            TestClickHouseRowExpression().build(self.CLICKHOUSE, source_columns="id,user")
        self.assertFalse(any(line.startswith("WARNING") for line in logs.output), logs.output)

    def test_without_source_columns_every_replica_column_is_compared(self):
        select, warnings = self.build_with_warnings(self.CLICKHOUSE)
        self.assertIn('"name"', select)
        self.assertEqual(warnings, [])

    def test_names_match_without_regard_to_case(self):
        select, warnings = self.build_with_warnings(self.CLICKHOUSE, source_columns="ID,User,Name")
        self.assertIn('"name"', select)
        self.assertIn('"user"', select)
        self.assertEqual(warnings, [])

    def test_source_column_missing_on_the_replica_is_named(self):
        _, warnings = self.build_with_warnings(self.CLICKHOUSE, source_columns="id,user,name,state")
        self.assertEqual(len(warnings), 1, warnings)
        self.assertIn("Source columns missing in table db1.t1 on the replica: ['state']", warnings[0])

    def test_excluded_and_connector_columns_are_neither_compared_nor_reported(self):
        columns = self.CLICKHOUSE + [("_version", "UInt64", 0, None), ("is_deleted", "UInt8", 0, None)]
        select, warnings = self.build_with_warnings(
            columns, source_columns="id,user,name,payload",
            exclude_columns=["_sign,_version,is_deleted,_is_deleted,payload"])
        self.assertEqual(warnings, [])
        self.assertNotIn('"_version"', select)
        self.assertNotIn('"is_deleted"', select)

    def test_parser_default_is_empty(self):
        self.assertEqual(ch.build_argument_parser().get_default("source_columns"), "")

    def test_driver_passes_source_columns_to_the_replica_side_only(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        cmd = tl.get_clickhouse_checksum_command("clickhouse-host", "db1", "t1", "id", 10,
                                                 source_columns=["id", "user"])
        self.assertEqual(cmd[cmd.index("--source_columns") + 1], '["id", "user"]')
        self.assertNotIn("--source_columns", clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10))
        self.assertNotIn("--source_columns", mysql_command_line("mysql-host", "db1", "t1", "id", 10, None))

    def test_a_name_with_a_comma_or_a_space_survives_the_driver_to_side_trip(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        cmd = tl.get_clickhouse_checksum_command("clickhouse-host", "db1", "t1", "id", 10,
                                                 source_columns=["id", "a,b", "order id"])
        passed = cmd[cmd.index("--source_columns") + 1]
        columns = [("id", "Int64", 0, None), ("a,b", "String", 0, None), ("order id", "String", 0, None),
                   ("name", "String", 0, None)]
        select, warnings = self.build_with_warnings(columns, source_columns=passed)
        self.assertIn('"a,b"', select)
        self.assertIn('"order id"', select)
        self.assertNotIn('"name"', select)
        self.assertEqual(len(warnings), 1, warnings)
        self.assertIn("Replica-only columns in table db1.t1: ['name']", warnings[0])

    def test_a_malformed_json_list_fails_loudly(self):
        with self.assertRaises(ValueError):
            ch.parse_source_columns('["id", 3]')
        with self.assertRaises(ValueError):
            ch.parse_source_columns('["id",')
        self.assertEqual(ch.parse_source_columns("id, user"), {"id", "user"})
        self.assertEqual(ch.parse_source_columns(""), set())


class TestRemovedDeadPaths(unittest.TestCase):
    """Spec 11.02 section 3.10."""

    def test_no_create_function_statement(self):
        import inspect
        self.assertFalse(hasattr(ch, "create_function_format_decimal"))
        self.assertNotIn("CREATE FUNCTION", inspect.getsource(ch))

    def test_no_count_precheck_before_the_aggregate(self):
        ch.args = clickhouse_args()
        stub = clickhouse_stub(CLICKHOUSE_COLUMNS, FIXTURE_ROWS)
        with patch.object(ch, "get_connection", return_value=MagicMock()), \
                patch.object(ch, "execute_sql", side_effect=stub), \
                self.assertLogs(level="INFO"):
            ch.calculate_checksum("t1", "user", "pw", None, None)
        self.assertFalse(any(sql.lower().startswith("select count(*) cnt from") for sql in stub.executed), stub.executed)

    def test_exclude_columns_nargs_match_on_both_sides(self):
        def nargs(parser):
            return [action for action in parser._actions if action.dest == "exclude_columns"][0].nargs
        self.assertEqual(nargs(ch.build_argument_parser()), "+")
        self.assertEqual(nargs(my.build_argument_parser()), "+")


class TestSignColumn(unittest.TestCase):
    """--sign_column defaults to '' and the engine decides the row filter
    (spec 11.02 section 3.8)."""

    def test_parser_default_is_empty(self):
        self.assertEqual(ch.build_argument_parser().get_default("sign_column"), "")

    def test_sign_column_from_engine(self):
        cases = {
            REPLACING_ENGINE: "",
            "ReplacingMergeTree(_version) PARTITION BY toYYYYMM(d) ORDER BY id": "",
            "CollapsingMergeTree(sign) ORDER BY id": "sign",
            "VersionedCollapsingMergeTree(sign, version) ORDER BY id": "sign",
            "ReplicatedCollapsingMergeTree('/clickhouse/tables/{shard}/db1/t1', '{replica}', _sign) ORDER BY id": "_sign",
            "ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/db1/t1', '{replica}', _version, is_deleted) ORDER BY id": "",
            "MergeTree ORDER BY id": "",
        }
        for engine_full, expected in cases.items():
            self.assertEqual(ch.sign_column_from_engine(engine_full), expected, engine_full)

    def executed_statements(self, engine_full, **arg_overrides):
        ch.args = clickhouse_args(**arg_overrides)
        stub = clickhouse_stub(CLICKHOUSE_COLUMNS, FIXTURE_ROWS, engine_full=engine_full)
        with patch.object(ch, "get_connection", return_value=MagicMock()), \
                patch.object(ch, "execute_sql", side_effect=stub), \
                self.assertLogs(level="INFO"):
            ch.calculate_checksum("t1", "user", "pw", None, None)
        return stub.executed

    def aggregate_statement(self, statements):
        return [sql for sql in statements if 'count(*) as "cnt"' in sql.lower()][0]

    def test_default_adds_no_filter_on_a_replacing_table(self):
        aggregate = self.aggregate_statement(self.executed_statements(REPLACING_ENGINE))
        self.assertIn("final where 1=1 ", aggregate)
        self.assertNotIn("_sign", aggregate)
        self.assertNotIn("> 0", aggregate)

    def test_default_filters_on_the_sign_of_a_collapsing_table(self):
        aggregate = self.aggregate_statement(self.executed_statements("CollapsingMergeTree(sign) ORDER BY id"))
        self.assertIn("final where 1=1 and sign > 0 ", aggregate)

    def test_explicit_sign_column_is_used_without_reading_the_engine(self):
        statements = self.executed_statements(REPLACING_ENGINE, sign_column="is_live")
        self.assertIn("final where 1=1 and is_live > 0 ", self.aggregate_statement(statements))
        self.assertFalse(any("engine_full" in sql.lower() for sql in statements))


class TestFinalAcrossPartitions(unittest.TestCase):
    """do_not_merge_across_partitions_select_final only when the partition key
    is a function of the sorting key (spec 11.02 section 3.7)."""

    SETTING = "do_not_merge_across_partitions_select_final=1"

    def statement(self, columns, **arg_overrides):
        ch.args = clickhouse_args(**arg_overrides)
        with patch.object(ch, "execute_sql", side_effect=clickhouse_stub(columns, [])):
            (query, select, order_by, external_types, clamped, final_per_partition) = ch.get_table_checksum_query(MagicMock(), "t1")
        return ch.select_table_statements("t1", query, select, order_by, external_types, None, clamped, final_per_partition)[0]

    def test_partition_key_inside_the_sorting_key_keeps_per_partition_final(self):
        # (name, type, is_nullable, numeric_scale, is_in_partition_key, is_in_sorting_key)
        columns = [("id", "Int32", 0, None, 0, 1), ("d", "Date32", 0, None, 1, 1), ("v", "String", 0, None, 0, 0)]
        statement = self.statement(columns)
        self.assertIn(self.SETTING, statement)
        self.assertIn(" final ", statement)

    def test_partition_key_outside_the_sorting_key_merges_across_partitions(self):
        columns = [("id", "Int32", 0, None, 0, 1), ("d", "Date32", 0, None, 1, 0), ("v", "String", 0, None, 0, 0)]
        statement = self.statement(columns)
        self.assertNotIn(self.SETTING, statement)
        self.assertIn(" final ", statement)

    def test_mixed_partition_columns_need_every_one_in_the_sorting_key(self):
        columns = [("id", "Int32", 0, None, 1, 1), ("d", "Date32", 0, None, 1, 0)]
        self.assertNotIn(self.SETTING, self.statement(columns))

    def test_unpartitioned_table_has_no_setting(self):
        self.assertNotIn(self.SETTING, self.statement(CLICKHOUSE_COLUMNS))

    def test_memory_setting_forms_a_well_formed_settings_clause(self):
        statement = self.statement(CLICKHOUSE_COLUMNS, max_memory_usage="123")
        self.assertIn(" settings max_memory_usage = 123", statement)
        self.assertNotIn("settings ,", statement)
        both = self.statement([("d", "Date32", 0, None, 1, 1)], max_memory_usage="123")
        self.assertIn(" settings " + self.SETTING + ", max_memory_usage = 123", both)


class TestBinaryEncoding(unittest.TestCase):
    """One --binary_encoding on both sides (spec 11.02 section 3.6)."""

    def test_mysql_renders_hex_by_default_base64_on_request_and_hex_in_raw_mode(self):
        column = [mysql_column("b", "varbinary", "varbinary(16)")]
        self.assertEqual(build_mysql_select(column), "lower(hex(cast(`b` as binary)))" + mysql_flags("b"))
        self.assertEqual(build_mysql_select(column, binary_encoding="raw"),
                         "lower(hex(cast(`b` as binary)))" + mysql_flags("b"))
        self.assertEqual(build_mysql_select(column, binary_encoding="base64"),
                         "replace(to_base64(cast(`b` as binary)),'\\n','')" + mysql_flags("b"))

    def test_clickhouse_hexes_listed_string_columns_only_in_raw_mode(self):
        build = TestClickHouseRowExpression().build
        columns = [("b", "String", 0, None), ("flag", "Bool", 0, None), ("name", "String", 0, None)]
        flags = clickhouse_flags("b", "flag", "name")
        self.assertEqual(build(columns, binary_encoding="raw", hex_columns=["b,flag"]),
                         'lower(hex("b"))' "||'#'||" 'toString(toUInt8("flag"))' "||'#'||" 'toString("name")' + flags)
        self.assertEqual(build(columns),
                         'toString("b")' "||'#'||" 'toString(toUInt8("flag"))' "||'#'||" 'toString("name")' + flags)
        self.assertEqual(build(columns, binary_encoding="base64"),
                         'toString("b")' "||'#'||" 'toString(toUInt8("flag"))' "||'#'||" 'toString("name")' + flags)
        with self.assertRaises(ValueError):
            build(columns, binary_encoding="hex", hex_columns=["b"])

    def test_driver_passes_the_encoding_to_both_sides_and_raw_columns_only_in_raw_mode(self):
        tl.args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC",
                                     binary_encoding="hex", include_floating_point_columns=False,
                                     include_json_columns=False)
        mysql_cmd = mysql_command_line("mysql-host", "db1", "t1", "id", 10, None)
        self.assertIn("--binary_encoding hex", mysql_cmd)
        self.assertNotIn("base64", mysql_cmd)
        clickhouse_cmd = clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10, binary_columns=["b1", "b2"])
        self.assertIn("--binary_encoding hex", clickhouse_cmd)
        self.assertNotIn("--hex_columns", clickhouse_cmd)
        tl.args.binary_encoding = "raw"
        clickhouse_cmd = clickhouse_command_line("clickhouse-host", "db1", "t1", "id", 10, binary_columns=["b1", "b2"])
        self.assertIn("--binary_encoding raw --hex_columns b1,b2", clickhouse_cmd)
        self.assertIn("--binary_encoding raw", mysql_command_line("mysql-host", "db1", "t1", "id", 10, None))


class TestBooleanAndBit(unittest.TestCase):
    """Bool / Nullable(Bool) and MySQL bit(1) both render '1' / '0' (spec 11.02 section 3.3)."""

    def test_nullable_bool_renders_through_touint8(self):
        build = TestClickHouseRowExpression().build
        self.assertEqual(build([("b", "Bool", 0, None)]), 'toString(toUInt8("b"))' + clickhouse_flags("b"))
        self.assertEqual(
            build([("b", "Nullable(Bool)", 1, None)]),
            'case when "b" is null then \'\' else toString(toUInt8("b")) end'
            "||'#'||"
            'case when "b" is null then \'1\' else \'0\' end',
        )

    def test_mysql_bit1_renders_as_integer_and_wider_bits_stay_hex(self):
        self.assertEqual(build_mysql_select([mysql_column("b", "bit", "bit(1)")]), "`b`+0" + mysql_flags("b"))
        self.assertEqual(build_mysql_select([mysql_column("b", "bit", "bit(8)")]),
                         "lower(hex(cast(`b` as binary)))" + mysql_flags("b"))


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
