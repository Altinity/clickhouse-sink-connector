"""DATETIME values outside the ClickHouse DateTime64 range are loaded as the streaming connector stores them
(Spec 13.04 D-13.04-34), run against BOTH copies.

MySQL DATETIME spans 1000-01-01 .. 9999-12-31; open-ended rows commonly hold '9999-12-31 23:59:59'. The connector
saturates such values to its DateTime64 bounds, the instants 1900-01-01 00:00:00 and 2299-12-31 23:59:59 UTC
(DataTypeRange.DATETIME64_MIN/MAX, clamp.out.of.range), and the checksum tools clamp to the same bounds. The
loader used to hand the text to ClickHouse, which saturates on its own (to 2299-12-31 23:59:59.999999 or, parsed
in another zone, to another instant), so a loaded table never verified equal to MySQL for those rows.

The value checks build the INSERT the loader runs (load_data_mysqlshell, execute_load captured), feed the rows
through format(TSV, ...) instead of input() and execute it in ``clickhouse local`` under the server zones UTC and
America/Chicago; skipped when it is not installed. Run from sink-connector/python:
    python -m pytest db_load/tests/test_loader_datetime_clamp.py
"""
import importlib
import os
import re
import shutil
import subprocess
import sys
from argparse import Namespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

COPIES = {"legacy": "db_load.clickhouse_loader", "packaged": "ch_sink_tools.db_load.clickhouse_loader"}
CLICKHOUSE = shutil.which("clickhouse")

DDL = ("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  `valid_to` datetime(6) DEFAULT NULL,\n"
       "  `created` datetime NOT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
# MySQL Shell TSV rows (id, valid_to, created)
ROWS = [
    ("1", "9999-12-31 23:59:59.999999", "9999-12-31 23:59:59"),
    ("2", "2299-12-31 23:59:59.000000", "2299-12-31 23:59:59"),
    ("3", "1000-01-01 00:00:00.000000", "1000-01-01 00:00:00"),
    ("4", "\\N", "1899-12-31 23:59:59"),
    ("5", "2024-05-06 07:08:09.123456", "2024-05-06 07:08:09"),
    ("6", "2299-12-31 23:59:59.000001", "1900-01-01 00:00:00"),
    ("7", "1900-01-01 00:00:00.000000", "0000-00-00 00:00:00"),
]
# What the connector stores (UTC instants): above 2299-12-31 23:59:59 -> that bound, below 1900-01-01 00:00:00
# (MySQL's zero date included) -> that bound, a bound itself -> that bound whatever the zone; in range unchanged.
CONNECTOR = {
    "1": ("2299-12-31 23:59:59.000000", "2299-12-31 23:59:59"),
    "2": ("2299-12-31 23:59:59.000000", "2299-12-31 23:59:59"),
    "3": ("1900-01-01 00:00:00.000000", "1900-01-01 00:00:00"),
    "4": ("\\N", "1900-01-01 00:00:00"),
    "6": ("2299-12-31 23:59:59.000000", "1900-01-01 00:00:00"),
    "7": ("1900-01-01 00:00:00.000000", "1900-01-01 00:00:00"),
}


@pytest.fixture(params=sorted(COPIES))
def loader(request):
    return importlib.import_module(COPIES[request.param])


def loader_args(dump_dir):
    return Namespace(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                     clickhouse_database="db1_ch", clickhouse_config_file=None, clickhouse_user="loader",
                     clickhouse_password=None, mysql_source_database="db1",
                     dump_dir=str(dump_dir), threads=1, truncate_tables=False, dry_run=False, mysqlshell=True,
                     rmt_delete_support=True, use_regexp_parser=False,
                     virtual_columns=['`_sign`', '`_version`', '`is_deleted`', '`_is_deleted`'],
                     binary_handling_mode="bytes", persist_raw_bytes=False)


# A target created by someone else than this loader run: the connector (its zone UTC) or ch-mysql-resync's scratch
# table, CREATE TABLE ... AS the live table, loaded with --data_only and no --clickhouse_datetime_timezone.
CONNECTOR_TARGET = ("CREATE TABLE db1_ch.t1 (`id` Int32, `valid_to` Nullable(DateTime64(6, 'UTC')), "
                    "`created` DateTime64(0, 'UTC'), `_version` UInt64 DEFAULT 0, `is_deleted` UInt8 DEFAULT 0) "
                    "ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id")


def own_types(columns):
    """system.columns of the table the loader creates from its own translation."""
    return {c["column_name"].strip("`"): (f"Nullable({c['datatype'].strip()})" if c["nullable"] else
                                         c["datatype"].strip()) for c in columns}


def schema_and_insert(loader, tmp_path, datetime_timezone, target_types=None):
    """The CREATE TABLE and the INSERT ... SELECT ... FROM input(...) the loader would run for t1; the target
    table's system.columns are ``target_types`` (default: the loader's own translation)."""
    ddl, columns = loader.convert_to_clickhouse_table("u", "t1", DDL, True, False, datetime_timezone)
    (tmp_path / "db1@t1.sql").write_text(DDL)
    (tmp_path / "db1@t1@@0.tsv.zst").write_bytes(b"")
    commands = []
    types = target_types if target_types is not None else own_types(columns)
    with mock.patch.object(loader, "execute_load", commands.append), \
            mock.patch.object(loader, "target_column_types", return_value=types) as lookup:
        loader.load_data_mysqlshell(loader_args(tmp_path), "UTC", {"db1.t1": columns}, "loader", None)
    assert len(commands) == 1, commands
    assert lookup.call_args.args[3:] == ("db1_ch", "t1"), lookup.call_args
    query = re.search(r'--query="(.*?)" -u', commands[0], re.S).group(1).replace("\\`", "`")
    return ddl, query


def run(loader, tmp_path, datetime_timezone, server_zone, target_ddl=None, target_types=None):
    ddl, query = schema_and_insert(loader, tmp_path, datetime_timezone, target_types)
    data = "\n".join("\t".join(row) for row in ROWS) + "\n"
    query = re.sub(r"FROM input\('(.*?)'\) FORMAT TSV", lambda m: f"FROM format(TSV, '{m.group(1)}', $${data}$$)",
                   query, flags=re.S)
    create = target_ddl or ddl.replace("CREATE TABLE `t1`", "CREATE TABLE db1_ch.t1", 1)
    script = ("CREATE DATABASE db1_ch;\n" + create + ";\n" + query +
              ";\nSELECT id, toString(valid_to, 'UTC'), toString(created, 'UTC') FROM db1_ch.t1 ORDER BY id FORMAT TSV")
    result = subprocess.run([CLICKHOUSE, "local", "--multiquery", "-q", script], capture_output=True, text=True,
                            env=dict(os.environ, TZ=server_zone))
    assert result.returncode == 0, result.stderr
    return {line.split("\t")[0]: tuple(line.split("\t")[1:]) for line in result.stdout.splitlines()}


def test_datetime_column_is_clamped_to_the_connector_bounds(loader):
    column = {"column_name": "`valid_to`", "datatype": "DateTime64(6)", "mysql_datatype": "datetime(6)",
              "nullable": True}
    # the target table's own type wins over the loader's translation
    expression = loader.mysqlshell_column_expression(column, "\\`valid_to\\`", None,
                                                     target_types={"valid_to": "Nullable(DateTime64(6, 'UTC'))"})
    assert expression == (
        "multiIf(\\`valid_to\\` >= '2299-12-31 23:59:59', "
        "CAST(toDateTime64('2299-12-31 23:59:59', 6, 'UTC') AS Nullable(DateTime64(6, 'UTC'))), "
        "\\`valid_to\\` < '1900-01-01 00:00:00.000001', "
        "CAST(toDateTime64('1900-01-01 00:00:00', 6, 'UTC') AS Nullable(DateTime64(6, 'UTC'))), "
        "CAST(\\`valid_to\\` AS Nullable(DateTime64(6, 'UTC'))))")
    # without a known target type, the translation (Nullable when the column is)
    assert loader.mysqlshell_column_expression(column, "\\`valid_to\\`", None).endswith(
        "CAST(\\`valid_to\\` AS Nullable(DateTime64(6))))")


@pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse local not installed")
@pytest.mark.parametrize("server_zone", ["UTC", "America/Chicago"])
def test_data_only_load_into_a_table_created_elsewhere(loader, tmp_path, server_zone):
    """The target's columns are DateTime64(.., 'UTC') while the loader's translation (no
    --clickhouse_datetime_timezone) has no zone, as for the ch-mysql-resync scratch table: in-range values are
    still converted in the target's zone (an end-to-end resync stored 03:04:05 as 09:04:05 under America/Chicago
    when the cast used the translation), out-of-range ones take the bound instants."""
    target_types = {"id": "Int32", "valid_to": "Nullable(DateTime64(6, 'UTC'))", "created": "DateTime64(0, 'UTC')"}
    stored = run(loader, tmp_path, None, server_zone, target_ddl=CONNECTOR_TARGET, target_types=target_types)
    for row_id, expected in CONNECTOR.items():
        assert stored[row_id] == expected, (row_id, stored[row_id], server_zone)
    assert stored["5"] == ("2024-05-06 07:08:09.123456", "2024-05-06 07:08:09"), stored["5"]


@pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse local not installed")
@pytest.mark.parametrize("datetime_timezone", ["UTC", None])
@pytest.mark.parametrize("server_zone", ["UTC", "America/Chicago"])
def test_loaded_values_equal_the_connector_clamp(loader, tmp_path, datetime_timezone, server_zone):
    stored = run(loader, tmp_path, datetime_timezone, server_zone)
    for row_id, expected in CONNECTOR.items():
        assert stored[row_id] == expected, (row_id, stored[row_id], datetime_timezone, server_zone)
    # an in-range value is converted exactly as before the clamp (the column zone, else the server zone)
    in_utc = server_zone == "UTC" or datetime_timezone == "UTC"
    assert stored["5"] == (("2024-05-06 07:08:09.123456", "2024-05-06 07:08:09") if in_utc else
                           ("2024-05-06 12:08:09.123456", "2024-05-06 12:08:09")), stored["5"]


@pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse local not installed")
def test_clickhouse_alone_saturates_elsewhere():
    # Without the clamp ClickHouse keeps the fraction (and, parsed in another zone, moves the instant).
    script = ("SELECT toString(CAST('9999-12-31 23:59:59.999999' AS DateTime64(6, 'UTC')), 'UTC'), "
              "toString(CAST('1000-01-01 00:00:00' AS DateTime64(0, 'America/Chicago')), 'UTC') FORMAT TSV")
    result = subprocess.run([CLICKHOUSE, "local", "-q", script], capture_output=True, text=True, check=True)
    assert result.stdout.split() != ["2299-12-31", "23:59:59.000000", "1900-01-01", "00:00:00"], result.stdout
