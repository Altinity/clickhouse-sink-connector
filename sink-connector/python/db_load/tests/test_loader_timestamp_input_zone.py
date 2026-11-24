"""The MySQL TIMESTAMP text of a dump is parsed in the dump's own zone, not in the ambient zone of the load.

MySQL Shell writes TIMESTAMP values as text in the zone the dump recorded (``tzUtc: true`` -> UTC text). The loader
declares that text to ``input()``; a zone-less ``DateTime64(p)`` declaration parses it in the ambient zone of the
``clickhouse-client`` process (``--use_client_time_zone 1`` and ``export TZ=<zone>``), so every instant follows that
zone instead of the dump's. The declaration therefore names the zone the loader resolved -- from the dump metadata or
from ``--source_timezone`` -- and the stored instant no longer depends on the ambient zone (Spec 13.04 section 3.9).

The structure assertion runs the real ``load_data_mysqlshell`` with ``execute_load`` captured, so it fails on the
zone-less declaration. The value check drives the emitted structure through ``clickhouse local`` under three ambient
zones and is skipped when ``clickhouse`` is not installed.

Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_timestamp_input_zone.py
"""
import importlib
import json
import os
import shutil
import subprocess
import sys
from argparse import Namespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

loader = importlib.import_module("ch_sink_tools.db_load.clickhouse_loader")

# ts is TIMESTAMP(6) NULL: MySQL Shell writes '2018-04-05 20:44:35.772929' (UTC text). id is a plain column, so the
# emitted structure carries exactly one time-typed field.
DUMP_DDL = ("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  `ts` timestamp(6) NULL DEFAULT NULL,\n"
            "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;\n")


def loader_args(dump_dir, **overrides):
    values = dict(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                  clickhouse_database="db1_ch", clickhouse_config_file=None, clickhouse_user="loader",
                  clickhouse_password=None, mysql_source_database="db1", dump_dir=str(dump_dir), threads=1,
                  truncate_tables=False, dry_run=False, mysqlshell=True, rmt_delete_support=True,
                  use_regexp_parser=False, virtual_columns=['`_sign`', '`_version`', '`is_deleted`', '`_is_deleted`'],
                  binary_handling_mode="bytes", persist_raw_bytes=False, source_timezone=None)
    values.update(overrides)
    return Namespace(**values)


def write_dump(dump_dir):
    """A MySQL Shell dump of db1.t1 that records tzUtc: true, with one (unused) data chunk."""
    (dump_dir / "@.json").write_text(json.dumps({"dumper": "mysqlsh", "tzUtc": True}))
    (dump_dir / "db1@t1.sql").write_text(DUMP_DDL)
    (dump_dir / "db1@t1@@0.tsv.zst").write_bytes(b"not decompressed: execute_load is captured")


def emitted_command(dump_dir, resolved_zone, datetime_timezone=None):
    """The INSERT pipeline load_data_mysqlshell builds for the dump, with the zone the schema pass resolved."""
    (_, columns) = loader.convert_to_clickhouse_table("u", "t1", DUMP_DDL, True, False, datetime_timezone)
    commands = []
    args = loader_args(dump_dir)
    loader.args = args
    with mock.patch.object(loader, "execute_load", commands.append):
        loader.load_data_mysqlshell(args, resolved_zone, {"db1.t1": columns})
    assert len(commands) == 1, commands
    return commands[0]


def emitted_structure(command):
    """The input() declaration exactly as it stands in the --query text of the pipeline."""
    start = command.index("input('") + len("input('")
    return command[start:command.index("') FORMAT TSV", start)]


def received_structure(command):
    """The declaration as clickhouse-client parses it.

    The pipeline puts the declaration inside a shell double-quoted ``--query="..."``: the shell removes the backslash
    of an escaped backtick (``\\``` -> backtick) and leaves the backslash of ``\\'`` for clickhouse's string-literal
    parsing to remove.
    """
    return emitted_structure(command).replace("\\`", "`")


def declared_structure(command):
    """The declaration as a reader writes it: both escapes undone."""
    return received_structure(command).replace("\\'", "'")


# --------------------------------------------------------------------- the declaration of one TIMESTAMP field

class TestTimestampInputDatatype:
    def test_a_zone_less_declaration_takes_the_resolved_zone(self):
        assert loader.timestamp_input_datatype("DateTime64(6)", "UTC") == "DateTime64(6, 'UTC')"
        assert loader.timestamp_input_datatype("DateTime64(0)", "America/Chicago") == "DateTime64(0, 'America/Chicago')"

    def test_surrounding_whitespace_does_not_survive(self):
        assert loader.timestamp_input_datatype(" DateTime64(6) ", "UTC") == "DateTime64(6, 'UTC')"

    @pytest.mark.parametrize("declared", ["DateTime64(6, 'UTC')", "DateTime64(3,'America/Chicago')",
                                          "DateTime64(6, 'Etc/GMT-5')", "DateTime('UTC')"])
    def test_a_declaration_that_names_a_zone_keeps_it(self, declared):
        # --clickhouse_datetime_timezone decides that one; the resolved dump zone must not overwrite it
        assert loader.timestamp_input_datatype(declared, "America/Chicago") == declared
        assert loader.timestamp_input_datatype(declared, "UTC") == declared

    def test_no_resolved_zone_leaves_the_declaration_alone(self):
        assert loader.timestamp_input_datatype("DateTime64(6)", None) == "DateTime64(6)"

    def test_a_declaration_without_parentheses_still_gets_the_zone(self):
        assert loader.timestamp_input_datatype("", "UTC") == "DateTime64(0, 'UTC')"


# ------------------------------------------------------------- the structure the loader builds for a real dump

class TestStructurePinsTheDumpZone:
    def test_a_tz_utc_dump_declares_the_utc_zone(self, tmp_path):
        write_dump(tmp_path)
        structure = declared_structure(emitted_command(tmp_path, "UTC"))
        assert "DateTime64(6, 'UTC')" in structure
        assert "DateTime64(6)" not in structure

    def test_the_zone_the_flag_resolved_is_the_zone_declared(self, tmp_path):
        write_dump(tmp_path)
        structure = declared_structure(emitted_command(tmp_path, "America/Chicago"))
        assert "DateTime64(6, 'America/Chicago')" in structure
        assert "DateTime64(6)" not in structure

    def test_a_datetime_timezone_declaration_is_not_overwritten(self, tmp_path):
        write_dump(tmp_path)
        structure = declared_structure(emitted_command(tmp_path, "UTC", datetime_timezone="America/Chicago"))
        assert "DateTime64(6,'America/Chicago')" in structure
        assert "'UTC'" not in structure

    def test_a_column_that_is_not_a_timestamp_is_untouched(self, tmp_path):
        write_dump(tmp_path)
        structure = declared_structure(emitted_command(tmp_path, "UTC"))
        assert "`id`  String" in structure


# --------------------------------------- the parsed instant, measured by the engine the loader drives

TSV = "1\t2018-04-05 20:44:35.772929\n2\t2018-12-01 00:18:03.545683\n"
# MySQL's instants for that text when it is UTC: 2018-04-05 20:44:35.772929Z and 2018-12-01 00:18:03.545683Z
EXPECTED = ["1522961075772929", "1543623483545683"]


def parse_with_structure(structure, ambient_zone, tmp_path):
    """Load the TSV through the structure the loader emits and return the stored instants, under an ambient TZ.

    ``structure`` is :func:`received_structure` output: backticks plain, single quotes still escaped, exactly what
    clickhouse-client parses inside ``input('...')``.
    """
    data = tmp_path / "rows.tsv"
    data.write_text(TSV)
    query = [
        "CREATE TABLE t (id String, ts Nullable(DateTime64(6))) ENGINE = Memory",
        "INSERT INTO t SELECT id, ts FROM input('%s') FORMAT TSV" % structure,
        "SELECT toUnixTimestamp64Micro(ts) FROM t ORDER BY id FORMAT TSV",
    ]
    result = subprocess.run(["clickhouse", "local", "-n", "--query", query[0], "--query", query[1],
                             "--query", query[2]],
                            input=TSV, capture_output=True, text=True, check=False,
                            env=dict(os.environ, TZ=ambient_zone))
    assert result.returncode == 0, result.stderr
    return result.stdout.split()


@pytest.mark.skipif(shutil.which("clickhouse") is None, reason="clickhouse local not installed")
class TestParsedInstant:
    @pytest.mark.parametrize("ambient_zone", ["UTC", "America/Chicago", "Europe/London"])
    def test_the_zone_the_loader_declares_fixes_the_instant(self, tmp_path, ambient_zone):
        write_dump(tmp_path)
        structure = received_structure(emitted_command(tmp_path, "UTC"))
        assert parse_with_structure(structure, ambient_zone, tmp_path) == EXPECTED

    def test_the_same_structure_is_read_identically_under_every_ambient_zone(self, tmp_path):
        write_dump(tmp_path)
        structure = received_structure(emitted_command(tmp_path, "UTC"))
        parsed = {zone: parse_with_structure(structure, zone, tmp_path)
                  for zone in ("UTC", "America/Chicago", "Europe/London")}
        assert parsed == dict.fromkeys(parsed, EXPECTED), parsed

    def test_a_zone_less_declaration_is_what_makes_the_instant_ambient(self, tmp_path):
        # the defect this change removes: the same text, declared without a zone, stores a different instant per zone
        zone_less = "`id`  String,  `ts`  Nullable( DateTime64(6))"
        under_utc = parse_with_structure(zone_less, "UTC", tmp_path)
        assert under_utc == EXPECTED
        for zone in ("America/Chicago", "Europe/London"):
            assert parse_with_structure(zone_less, zone, tmp_path) != under_utc
