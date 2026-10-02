"""MySQL TIME values are loaded in the text the streaming connector writes (Spec 13.04 D-13.04-35), run against BOTH
copies.

The connector writes a TIME into its String column as ``[-]HH:MM:SS.ffffff`` (MicroTimeConverter.convert: six
fraction digits always, sign kept, hours unbounded; Spec 07.03 section 3.2), and the checksum tools' MySQL side
renders TIME the same way. MySQL Shell dumps TIME(p) with p fraction digits ('01:15:00'), which the loader used to
store as is, so a snapshot-loaded table never verified equal and differed from the rows streamed after it.

The value checks build the INSERT the loader runs (load_data_mysqlshell, execute_load captured), feed the rows
through format(TSV, ...) instead of input() and execute it in ``clickhouse local``; skipped when it is not
installed. Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_time_text.py
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

DDL = ("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  `t0` time DEFAULT NULL,\n  `t3` time(3) DEFAULT NULL,\n"
       "  `t6` time(6) NOT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
# MySQL Shell TSV rows (id, t0, t3, t6): TIME(p) printed with p fraction digits, at least two hour digits
ROWS = [
    ("1", "01:15:00", "12:00:00.500", "01:15:00.000000"),
    ("2", "-838:59:59", "-00:00:01.250", "838:59:59.000000"),
    ("3", "\\N", "\\N", "100:00:00.123456"),
    ("4", "00:00:00", "23:59:59.999", "-01:00:00.000001"),
]


def connector_text(mysql_text):
    """MicroTimeConverter.convert of the value MySQL prints as ``mysql_text``: the signed microsecond total
    formatted '%s%02d:%02d:%02d.%06d'."""
    if mysql_text == "\\N":
        return "\\N"
    sign = "-" if mysql_text.startswith("-") else ""
    (hms, _, fraction) = mysql_text.lstrip("-").partition(".")
    (hours, minutes, seconds) = (int(part) for part in hms.split(":"))
    micros = ((hours * 60 + minutes) * 60 + seconds) * 1_000_000 + int((fraction + "000000")[:6])
    return "%s%02d:%02d:%02d.%06d" % (sign, micros // 3_600_000_000, micros // 60_000_000 % 60,
                                      micros // 1_000_000 % 60, micros % 1_000_000)


@pytest.fixture(params=sorted(COPIES))
def loader(request):
    return importlib.import_module(COPIES[request.param])


def loader_args(dump_dir):
    return Namespace(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                     clickhouse_database="db1_ch", clickhouse_config_file=None, clickhouse_user="loader",
                     clickhouse_password=None, mysql_source_database="db1", dump_dir=str(dump_dir), threads=1,
                     truncate_tables=False, dry_run=False, mysqlshell=True, rmt_delete_support=True,
                     use_regexp_parser=False, virtual_columns=['`_sign`', '`_version`', '`is_deleted`', '`_is_deleted`'],
                     binary_handling_mode="bytes", persist_raw_bytes=False)


def load_and_read(loader, tmp_path):
    """Run the loader's own CREATE TABLE and INSERT for t1 in clickhouse local; {id: (t0, t3, t6)}."""
    ddl, columns = loader.convert_to_clickhouse_table("u", "t1", DDL, True, False, "UTC")
    (tmp_path / "db1@t1.sql").write_text(DDL)
    (tmp_path / "db1@t1@@0.tsv.zst").write_bytes(b"")
    types = {c["column_name"].strip("`"): (f"Nullable({c['datatype'].strip()})" if c["nullable"] else
                                          c["datatype"].strip()) for c in columns}
    commands = []
    with mock.patch.object(loader, "execute_load", commands.append), \
            mock.patch.object(loader, "target_column_types", return_value=types):
        loader.load_data_mysqlshell(loader_args(tmp_path), "UTC", {"db1.t1": columns}, "loader", None)
    assert len(commands) == 1, commands
    query = re.search(r'--query="(.*?)" -u', commands[0], re.S).group(1).replace("\\`", "`")
    data = "\n".join("\t".join(row) for row in ROWS) + "\n"
    query = re.sub(r"FROM input\('(.*?)'\) FORMAT TSV", lambda m: f"FROM format(TSV, '{m.group(1)}', $${data}$$)",
                   query, flags=re.S)
    script = ("CREATE DATABASE db1_ch;\n" + ddl.replace("CREATE TABLE `t1`", "CREATE TABLE db1_ch.t1", 1) + ";\n" +
              query + ";\nSELECT id, t0, t3, t6 FROM db1_ch.t1 ORDER BY id FORMAT TSV")
    result = subprocess.run([CLICKHOUSE, "local", "--multiquery", "-q", script], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    return {line.split("\t")[0]: tuple(line.split("\t")[1:]) for line in result.stdout.splitlines()}


def test_time_column_expression(loader):
    column = {"column_name": "`t0`", "datatype": "String", "mysql_datatype": "time", "nullable": True}
    assert loader.mysqlshell_column_expression(column, "\\`t0\\`", None) == (
        "if(position(\\`t0\\`, '.') > 0, concat(substring(\\`t0\\`, 1, position(\\`t0\\`, '.')), "
        "rightPad(substring(\\`t0\\`, position(\\`t0\\`, '.') + 1), 6, '0')), concat(\\`t0\\`, '.000000'))")
    # a target column that is not a String is left to the INSERT conversion; TIMESTAMP is not TIME
    assert loader.mysqlshell_column_expression(column, "\\`t0\\`", None, target_types={"t0": "Int64"}) == "\\`t0\\`"
    stamp = {"column_name": "`ts`", "datatype": "DateTime64(6)", "mysql_datatype": "timestamp(6)", "nullable": True}
    assert loader.mysqlshell_column_expression(stamp, "\\`ts\\`", None) == "\\`ts\\`"


def test_model_matches_the_connector_examples():
    assert [connector_text(t) for t in ("01:15:00", "-838:59:59", "12:00:00.5", "-00:00:01.250")] == \
        ["01:15:00.000000", "-838:59:59.000000", "12:00:00.500000", "-00:00:01.250000"]


@pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse local not installed")
def test_loaded_text_equals_the_connector_text(loader, tmp_path):
    stored = load_and_read(loader, tmp_path)
    assert stored == {row[0]: tuple(connector_text(v) for v in row[1:]) for row in ROWS}, stored
