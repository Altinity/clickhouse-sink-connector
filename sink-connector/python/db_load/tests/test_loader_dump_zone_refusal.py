"""Refusal of an undeterminable dump time zone (Spec 13.04 section 3.9, FM-13.04-27).

A dump whose TIMESTAMP zone cannot be read is loaded only when --source_timezone names the zone. A flag that
contradicts the zone the dump records is refused, and so is a recorded zone the loader cannot map. Nothing falls back
to UTC: UTC is returned only when the dump records +00:00 or the flag names UTC. Both dump layouts are covered, and a
refusal happens before any ClickHouse statement is sent.

The tests call the real loader functions of ch_sink_tools.db_load.clickhouse_loader. Offline: driver connections are
mocked, no database and no network.

Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_dump_zone_refusal.py
"""
import gzip
import importlib
import json
import os
import subprocess
import sys
from argparse import Namespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

loader = importlib.import_module("ch_sink_tools.db_load.clickhouse_loader")

MYSQLSHELL_TABLE_DDL = ("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  `ts` timestamp NULL DEFAULT NULL,"
                        "\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;\n")


def loader_args(dump_dir, **overrides):
    values = dict(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                  clickhouse_database="db1_ch", clickhouse_config_file=None, clickhouse_user="loader",
                  clickhouse_password=None, mysql_source_database="db1", dump_dir=str(dump_dir), threads=2,
                  truncate_tables=False, dry_run=False, mysqlshell=True, rmt_delete_support=True,
                  use_regexp_parser=False, source_timezone=None)
    values.update(overrides)
    return Namespace(**values)


def write_mysqlshell_dump(d, metadata):
    """A MySQL Shell dump: @.json holds ``metadata`` (None writes no @.json at all)."""
    if metadata is not None:
        (d / "@.json").write_text(json.dumps(metadata))
    (d / "db1@t1.sql").write_text(MYSQLSHELL_TABLE_DDL)


def write_mydumper_dump(d, zones):
    """A mydumper dump with one schema file per entry of ``zones``; a None entry writes no SET TIME_ZONE."""
    with gzip.open(d / "db1-schema-create.sql.gz", "wt") as create_file:
        create_file.write("CREATE DATABASE `db1`;\n")
    for index, zone in enumerate(zones):
        set_zone = f"SET TIME_ZONE='{zone}';\n" if zone is not None else ""
        body = (f"/*!40101 SET NAMES utf8mb4 */;\n{set_zone}"
                f"CREATE TABLE `t{index}` (\n  `id` int NOT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;\n")
        with gzip.open(d / f"db1.t{index}-schema.sql.gz", "wt") as schema_file:
            schema_file.write(body)


@pytest.fixture
def driver():
    """The ClickHouse statements sent through the mocked driver connection, one mock call each."""
    execute = mock.MagicMock(name="clickhouse_execute_conn")
    with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
            mock.patch.object(loader, "clickhouse_execute_conn", execute):
        yield execute


# ----------------------------------------------------------------------- the zone rule, called directly

class TestUndeterminableZone:
    @pytest.mark.parametrize("recorded", [None, "", "SYSTEM"])
    def test_no_zone_and_no_flag_is_refused(self, recorded):
        with pytest.raises(ValueError, match="--source_timezone"):
            loader.get_unix_timezone_from_mysql_timezone(recorded)

    @pytest.mark.parametrize("recorded", [None, "", "SYSTEM"])
    def test_flag_supplies_the_zone_when_none_is_recorded(self, recorded):
        assert loader.get_unix_timezone_from_mysql_timezone(recorded, "America/Chicago") == "America/Chicago"

    def test_flag_utc_is_returned_when_none_is_recorded(self):
        assert loader.get_unix_timezone_from_mysql_timezone(None, "UTC") == "UTC"

    def test_recorded_zones_need_no_flag(self):
        assert loader.get_unix_timezone_from_mysql_timezone("+00:00") == "UTC"
        assert loader.get_unix_timezone_from_mysql_timezone("+05:00") == "Etc/GMT-5"
        assert loader.get_unix_timezone_from_mysql_timezone("America/Chicago") == "America/Chicago"


class TestFlagMustMatchTheRecordedZone:
    def test_matching_flag_is_accepted(self):
        assert loader.get_unix_timezone_from_mysql_timezone("+00:00", "UTC") == "UTC"
        assert loader.get_unix_timezone_from_mysql_timezone("America/Chicago", "America/Chicago") == "America/Chicago"

    @pytest.mark.parametrize("recorded, flag", [("+00:00", "America/Chicago"), ("America/Chicago", "Europe/Paris")])
    def test_mismatching_flag_is_refused_naming_both_values(self, recorded, flag):
        with pytest.raises(ValueError) as refusal:
            loader.get_unix_timezone_from_mysql_timezone(recorded, flag)
        message = str(refusal.value)
        assert flag in message
        assert loader.get_unix_timezone_from_mysql_timezone(recorded) in message


class TestInvalidValuesAreRefusedWhateverTheFlag:
    @pytest.mark.parametrize("recorded", ["+99:00", "+05:30", "not-a-zone"])
    @pytest.mark.parametrize("flag", [None, "America/Chicago"])
    def test_invalid_recorded_zone_is_refused(self, recorded, flag):
        with pytest.raises(ValueError):
            loader.get_unix_timezone_from_mysql_timezone(recorded, flag)

    @pytest.mark.parametrize("flag", ["", "SYSTEM", "CST", "+05:00", "Etc/GMT-5", "not-a-zone"])
    @pytest.mark.parametrize("recorded", [None, "+00:00"])
    def test_invalid_flag_is_refused(self, recorded, flag):
        with pytest.raises(ValueError, match="--source_timezone"):
            loader.get_unix_timezone_from_mysql_timezone(recorded, flag)


# ------------------------------------------------------------------------------ MySQL Shell layout (load_schema)

class TestMysqlShellLayout:
    def test_missing_metadata_without_flag_is_refused_before_any_statement(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, None)
        with pytest.raises(ValueError, match="--source_timezone"):
            loader.load_schema(loader_args(tmp_path), dry_run=False)
        driver.assert_not_called()

    def test_tz_utc_missing_without_flag_is_refused_before_any_statement(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, {"dumper": "mysqlsh"})
        with pytest.raises(ValueError, match="--source_timezone"):
            loader.load_schema(loader_args(tmp_path), dry_run=False)
        driver.assert_not_called()

    def test_missing_metadata_with_flag_loads_in_the_flag_zone(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, None)
        (tz, schema_map) = loader.load_schema(loader_args(tmp_path, source_timezone="America/Chicago"), dry_run=False)
        assert tz == "America/Chicago" and "db1.t1" in schema_map
        assert driver.called

    def test_tz_utc_dump_with_matching_flag_is_utc(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, {"tzUtc": True})
        (tz, _) = loader.load_schema(loader_args(tmp_path, source_timezone="UTC"), dry_run=False)
        assert tz == "UTC"

    def test_tz_utc_dump_with_mismatching_flag_is_refused_before_any_statement(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, {"tzUtc": True})
        with pytest.raises(ValueError, match="America/Chicago"):
            loader.load_schema(loader_args(tmp_path, source_timezone="America/Chicago"), dry_run=False)
        driver.assert_not_called()

    def test_tz_utc_false_is_refused_even_with_a_flag(self, tmp_path, driver):
        write_mysqlshell_dump(tmp_path, {"tzUtc": False})
        with pytest.raises(ValueError, match="tzUtc: false"):
            loader.load_schema(loader_args(tmp_path, source_timezone="America/Chicago"), dry_run=False)
        driver.assert_not_called()


# -------------------------------------------------------------------------------- mydumper layout (load_schema)

class TestMydumperLayout:
    def test_no_zone_without_flag_is_refused_before_any_statement(self, tmp_path, driver):
        write_mydumper_dump(tmp_path, [None])
        with pytest.raises(ValueError, match="--source_timezone"):
            loader.load_schema(loader_args(tmp_path, mysqlshell=False), dry_run=False)
        driver.assert_not_called()

    def test_recorded_zone_is_used(self, tmp_path, driver):
        write_mydumper_dump(tmp_path, ["America/Chicago"])
        (tz, schema_map) = loader.load_schema(loader_args(tmp_path, mysqlshell=False), dry_run=False)
        assert tz == "America/Chicago" and "db1.t0" in schema_map
        assert driver.called

    def test_flag_supplies_the_zone_when_none_is_recorded(self, tmp_path, driver):
        write_mydumper_dump(tmp_path, [None])
        (tz, _) = loader.load_schema(loader_args(tmp_path, mysqlshell=False, source_timezone="America/Chicago"),
                                     dry_run=False)
        assert tz == "America/Chicago"

    def test_mismatching_flag_is_refused_before_any_statement(self, tmp_path, driver):
        write_mydumper_dump(tmp_path, ["America/Chicago"])
        with pytest.raises(ValueError, match="Europe/Paris"):
            loader.load_schema(loader_args(tmp_path, mysqlshell=False, source_timezone="Europe/Paris"), dry_run=False)
        driver.assert_not_called()

    def test_conflicting_schema_files_are_refused_before_any_statement(self, tmp_path, driver):
        write_mydumper_dump(tmp_path, ["America/Chicago", "Europe/Paris"])
        with pytest.raises(ValueError, match="ambiguous"):
            loader.load_schema(loader_args(tmp_path, mysqlshell=False), dry_run=False)
        driver.assert_not_called()


# ------------------------------------------------------------------------------------------- command line

def test_command_line_offers_source_timezone():
    result = subprocess.run([sys.executable, "-m", "ch_sink_tools.db_load.clickhouse_loader", "--help"],
                            cwd=ROOT, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    assert "--source_timezone" in result.stdout
