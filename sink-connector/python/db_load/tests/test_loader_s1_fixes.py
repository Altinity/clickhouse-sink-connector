"""Regression tests for the S1 loader defects of Spec 13.04 (D-13.04-1..7, D-13.04-28), run against BOTH copies.

Offline: no database, no network. Driver connections are mocked; data pipelines run with a stand-in
``clickhouse-client`` script on PATH. The value checks of the binary expressions use ``clickhouse local`` (an
embedded engine, no server) and are skipped when it is not installed.

Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_s1_fixes.py
"""
import gzip
import importlib
import json
import logging
import os
import shutil
import stat
import subprocess
import sys
from argparse import Namespace
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

COPIES = {
    "legacy": ("db_load.clickhouse_loader", "db_load.mysql_parser.mysql_parser"),
    "packaged": ("ch_sink_tools.db_load.clickhouse_loader", "ch_sink_tools.db_load.mysql_parser.mysql_parser"),
}


@pytest.fixture(params=sorted(COPIES))
def loader(request):
    return importlib.import_module(COPIES[request.param][0])


def translate(loader, ddl, rmt=True):
    return loader.convert_to_clickhouse_table("u", "t", ddl, rmt, False, None)


def loader_args(dump_dir, **overrides):
    values = dict(clickhouse_host="ch-host", clickhouse_port=9000, clickhouse_secure=False,
                  clickhouse_database="db1_ch", clickhouse_config_file=None, clickhouse_user="loader",
                  clickhouse_password=None, mysql_source_database="db1", dump_dir=str(dump_dir), threads=2,
                  truncate_tables=False, dry_run=False, mysqlshell=True, rmt_delete_support=True,
                  use_regexp_parser=False, virtual_columns=['`_sign`', '`_version`', '`is_deleted`', '`_is_deleted`'],
                  binary_handling_mode="bytes", persist_raw_bytes=False)
    values.update(overrides)
    return Namespace(**values)


# --------------------------------------------------------------------------------------------- D-13.04-2 time zone

class TestDumpTimezoneMapping:
    def test_utc_offset_is_utc(self, loader):
        assert loader.get_unix_timezone_from_mysql_timezone("+00:00") == "UTC"
        assert loader.get_unix_timezone_from_mysql_timezone("-00:00") == "UTC"

    def test_whole_hour_offset_is_a_fixed_offset_zone(self, loader):
        # POSIX sign convention: Etc/GMT-5 is UTC+05:00. A regional zone (with DST) is never chosen.
        assert loader.get_unix_timezone_from_mysql_timezone("+05:00") == "Etc/GMT-5"
        assert loader.get_unix_timezone_from_mysql_timezone("-03:00") == "Etc/GMT+3"

    def test_named_zone_is_kept(self, loader):
        assert loader.get_unix_timezone_from_mysql_timezone("Europe/Paris") == "Europe/Paris"

    @pytest.mark.parametrize("value", [None, "", "SYSTEM", "+99:00", "not-a-zone"])
    def test_undeterminable_zone_is_utc_with_a_warning(self, loader, value, caplog):
        with caplog.at_level(logging.WARNING):
            assert loader.get_unix_timezone_from_mysql_timezone(value) == "UTC"
        assert any(r.levelno == logging.WARNING and "cannot be determined" in r.getMessage() for r in caplog.records)

    def test_offset_without_a_fixed_iana_zone_is_refused(self, loader):
        with pytest.raises(ValueError, match=r"\+05:30"):
            loader.get_unix_timezone_from_mysql_timezone("+05:30")

    def test_deterministic_across_hash_seeds(self, loader):
        code = ("import sys; sys.path.insert(0, %r); import importlib; m = importlib.import_module(%r); "
                "print(m.get_unix_timezone_from_mysql_timezone('+00:00'), m.get_unix_timezone_from_mysql_timezone(None))"
                % (ROOT, loader.__name__))
        seen = set()
        for seed in range(6):
            out = subprocess.run([sys.executable, "-c", code], env=dict(os.environ, PYTHONHASHSEED=str(seed)),
                                 capture_output=True, text=True, check=True).stdout.split()
            seen.add(tuple(out))
        assert seen == {("UTC", "UTC")}


class TestMysqlShellDumpTimezone:
    def write_dump(self, d, tz_utc):
        meta = {"dumper": "mysqlsh", "version": "2.0.1"}
        if tz_utc is not None:
            meta["tzUtc"] = tz_utc
        (d / "@.json").write_text(json.dumps(meta))
        (d / "db1@t1.sql").write_text("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  `ts` timestamp NULL DEFAULT NULL,"
                                      "\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;\n")

    def load(self, loader, d):
        with mock.patch.object(loader, "get_connection", mock.MagicMock()), \
                mock.patch.object(loader, "clickhouse_execute_conn", mock.MagicMock()):
            return loader.load_schema(loader_args(d), dry_run=True)

    def test_tz_utc_dump_is_loaded_as_utc(self, loader, tmp_path):
        self.write_dump(tmp_path, True)
        (tz, schema_map) = self.load(loader, tmp_path)
        assert tz == "UTC" and "db1.t1" in schema_map

    def test_tz_utc_false_is_refused(self, loader, tmp_path):
        self.write_dump(tmp_path, False)
        with pytest.raises(ValueError, match="tzUtc: false"):
            self.load(loader, tmp_path)

    def test_missing_metadata_is_utc_with_a_warning(self, loader, tmp_path, caplog):
        self.write_dump(tmp_path, None)
        os.remove(tmp_path / "@.json")
        with caplog.at_level(logging.WARNING):
            (tz, _) = self.load(loader, tmp_path)
        assert tz == "UTC"
        assert any(r.levelno == logging.WARNING and "@.json not found" in r.getMessage() for r in caplog.records)


# --------------------------------------------------------------------------------------------- D-13.04-1 keyless

class TestKeylessSortingKey:
    def test_no_key_uses_every_stored_column_and_allows_nullable_key(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int DEFAULT NULL,\n  `b` varchar(10) NOT NULL,\n"
                                   "  `g` int GENERATED ALWAYS AS ((`a` * 2)) VIRTUAL\n) ENGINE=InnoDB;")
        assert "tuple()" not in ddl
        assert ddl.rstrip().endswith("order by (`a`,`b`) SETTINGS allow_nullable_key=1")

    def test_not_null_columns_need_no_setting(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int NOT NULL,\n  `b` int NOT NULL\n) ENGINE=InnoDB;")
        assert ddl.rstrip().endswith("order by (`a`,`b`)")

    def test_not_null_unique_key_is_the_sorting_key(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int NOT NULL,\n  `msg` varchar(20) NOT NULL,\n"
                                   "  `n` int DEFAULT NULL,\n  UNIQUE KEY `u` (`msg`(10),`a`)\n) ENGINE=InnoDB;")
        assert ddl.rstrip().endswith("order by (`msg`,`a`)")

    def test_column_level_unique_key_is_the_sorting_key(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int NOT NULL UNIQUE,\n  `b` int\n) ENGINE=InnoDB;")
        assert ddl.rstrip().endswith("order by (`a`)")

    def test_nullable_unique_key_falls_back_to_all_columns(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int NOT NULL,\n  `msg` varchar(20) DEFAULT NULL,\n"
                                   "  UNIQUE KEY `u` (`msg`)\n) ENGINE=InnoDB;")
        assert ddl.rstrip().endswith("order by (`a`,`msg`) SETTINGS allow_nullable_key=1")

    def test_primary_key_unchanged(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `a` int NOT NULL,\n  `b` int NOT NULL,\n"
                                   "  UNIQUE KEY `u` (`b`),\n  PRIMARY KEY (`a`)\n) ENGINE=InnoDB;")
        assert ddl.rstrip().endswith("order by (`a`)")

    def test_no_stored_column_is_refused_without_regexp_fallback(self, loader):
        with pytest.raises(loader.UnsafeTableDefinitionError, match="tuple"):
            translate(loader, "CREATE TABLE `t` (\n  `g` int GENERATED ALWAYS AS (1) VIRTUAL\n) ENGINE=InnoDB;")

    def test_regexp_translator_refuses_a_keyless_table(self, loader):
        with pytest.raises(loader.UnsafeTableDefinitionError):
            loader.convert_to_clickhouse_table_regexp("u", "t", "CREATE TABLE `t` (\n  `a` int\n) ENGINE=InnoDB;",
                                                      True, None)


# --------------------------------------------------------------------------------------------- D-13.04-6 names

class TestSourceColumnsNamedLikeBookkeeping:
    def test_source_sign_and_is_deleted_lookalikes_are_loaded(self, loader):
        _, cols = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `_sign` int DEFAULT NULL,\n"
                                    "  `_is_deleted` tinyint DEFAULT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        listed = loader.get_column_list({"db1.t": cols}, "db1", "t", loader_args(".").virtual_columns)
        assert listed == "\\`id\\`,\\`_sign\\`,\\`_is_deleted\\`"

    def test_source_is_deleted_still_loaded(self, loader):
        _, cols = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `is_deleted` tinyint DEFAULT NULL,\n"
                                    "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        listed = loader.get_column_list({"db1.t": cols}, "db1", "t", loader_args(".").virtual_columns)
        assert listed == "\\`id\\`,\\`is_deleted\\`"

    @pytest.mark.parametrize("column,rmt", [("_sign", False), ("_version", True), ("_version", False)])
    def test_collision_with_an_appended_column_is_refused(self, loader, column, rmt):
        with pytest.raises(loader.UnsafeTableDefinitionError, match=column):
            translate(loader, f"CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `{column}` int DEFAULT NULL,\n"
                              "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;", rmt=rmt)

    def test_collision_with_renamed_is_deleted_is_refused(self, loader):
        with pytest.raises(loader.UnsafeTableDefinitionError, match="_is_deleted"):
            translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `is_deleted` int,\n  `_is_deleted` int,\n"
                              "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")


# --------------------------------------------------------------------------------------------- D-13.04-7 / -28

class TestColumnModifiers:
    def test_lower_case_null_is_nullable(self, loader):
        _, cols = translate(loader, "CREATE TABLE `t` (\n  `id` int not null,\n  `v` varchar(10) null,\n"
                                    "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        assert [(c["column_name"], c["nullable"]) for c in cols] == [("`id`", False), ("`v`", True)]

    def test_lower_case_charset_is_stripped(self, loader):
        ddl, cols = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `w` varchar(5) charset latin1,\n"
                                      "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        assert "charset" not in ddl.lower() and cols[1]["datatype"].strip() == "varchar(5)"


# --------------------------------------------------------------------------------------------- D-13.04-3 binary

BINARY_DDL = ("CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `vb` varbinary(16) DEFAULT NULL,\n  `bl` blob,\n"
              "  `b8` bit(16) DEFAULT NULL,\n  `p` point DEFAULT NULL,\n  `s` varchar(10) DEFAULT NULL,\n"
              "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")


def split_top_level(column_list):
    """Split a SELECT list on the commas that are not inside parentheses."""
    parts, depth, current = [], 0, ""
    for ch in column_list:
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
            continue
        depth += {"(": 1, ")": -1}.get(ch, 0)
        current += ch
    return parts + [current]


class TestBinaryRepresentation:
    def columns(self, loader, **kw):
        _, cols = translate(loader, BINARY_DDL)
        listed = loader.get_column_list({"db1.t": cols}, "db1", "t", [], transform=True, mysqlshell=True, **kw)
        return split_top_level(listed)

    def test_default_is_lower_hex_of_the_decoded_base64(self, loader):
        b64 = "base64Decode(replaceAll(\\`{0}\\`, char(10), ''))"
        assert self.columns(loader) == [
            "\\`id\\`",
            "lower(hex(" + b64.format("vb") + "))",
            "lower(hex(" + b64.format("bl") + "))",
            "lower(hex(" + b64.format("b8") + "))",
            "lower(hex(substring(" + b64.format("p") + ", 5)))",
            "\\`s\\`"]

    def test_dump_metadata_decides_the_decoding(self, loader):
        cols = self.columns(loader, decode_columns={"vb": "UNHEX", "bl": "FROM_BASE64", "b8": "FROM_BASE64",
                                                    "p": "FROM_BASE64"})
        assert cols[1] == "lower(hex(unhex(\\`vb\\`)))"

    def test_base64_mode_keeps_bit_and_spatial_hex(self, loader):
        cols = self.columns(loader, binary_handling_mode="base64")
        assert cols[1].startswith("base64Encode(base64Decode(") and cols[3].startswith("lower(hex(")
        assert cols[4].startswith("lower(hex(substring(")

    def test_hex_mode_is_upper_hex_for_binary_types(self, loader):
        assert self.columns(loader, binary_handling_mode="hex")[1].startswith("hex(base64Decode(")

    def test_persist_raw_bytes_loads_the_bytes(self, loader):
        cols = self.columns(loader, persist_raw_bytes=True)
        assert cols[1].startswith("base64Decode(") and cols[4].startswith("substring(base64Decode(")

    def test_plain_column_list_is_unchanged(self, loader):
        _, cols = translate(loader, BINARY_DDL)
        assert loader.get_column_list({"db1.t": cols}, "db1", "t", []) == \
            ",".join("\\`%s\\`" % n for n in ("id", "vb", "bl", "b8", "p", "s"))

    @pytest.mark.skipif(shutil.which("clickhouse") is None, reason="clickhouse local not installed")
    def test_values_match_the_connector_rendering(self, loader, tmp_path):
        # MySQL Shell TSV text (TO_BASE64; MySQL breaks base64 lines at 76 characters, written as \n in TSV):
        #   vb = 0xdeadbeef, bl = 60 bytes 0x00..0x3b, b8 = b'0000000100000010', p = POINT(1 2) with SRID 0.
        blob = bytes(range(60))
        import base64
        b64_blob = base64.b64encode(blob).decode()
        b64_blob = b64_blob[:76] + "\\n" + b64_blob[76:]
        tsv = "1\t3q2+7w==\t%s\tAQI=\tAAAAAAEBAAAAAAAAAAAA8D8AAAAAAAAAQA==\tx\n2\t\\N\t\\N\t\\N\t\\N\t\\N\n" % b64_blob
        (tmp_path / "in.tsv").write_text(tsv)
        exprs = [c.replace("\\`", "`") for c in self.columns(loader)]
        structure = ("`id` String, `vb` Nullable(String), `bl` Nullable(String), `b8` Nullable(String), "
                     "`p` Nullable(String), `s` Nullable(String)")
        query = "SELECT %s FROM file('%s', TSV, '%s') ORDER BY id FORMAT TSV" % (
            ", ".join(exprs), tmp_path / "in.tsv", structure)
        out = subprocess.run(["clickhouse", "local", "-q", query], capture_output=True, text=True, check=True).stdout
        rows = [line.split("\t") for line in out.splitlines()]
        assert rows[0] == ["1", "deadbeef", blob.hex(), "0102", "0101000000000000000000f03f0000000000000040", "x"]
        assert rows[1] == ["2", "\\N", "\\N", "\\N", "\\N", "\\N"]


# --------------------------------------------------------------------------------------------- D-13.04-4 pipefail

def fake_client(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    client = bin_dir / "clickhouse-client"
    client.write_text("#!/bin/sh\ncat > /dev/null\nexit 0\n")
    client.chmod(client.stat().st_mode | stat.S_IEXEC)
    return dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")


class TestPipelineFailure:
    def test_failing_first_stage_fails_the_command(self, loader):
        (rc, _) = loader.run_quick_command("false | cat")
        assert rc != "0"

    @pytest.mark.skipif(shutil.which("zstd") is None, reason="zstd not installed")
    def test_truncated_mysqlshell_chunk_fails_the_load(self, loader, tmp_path):
        (tmp_path / "db1@t1.sql").write_text("CREATE TABLE `t1` (\n  `id` int NOT NULL,\n  PRIMARY KEY (`id`)\n);\n")
        raw = tmp_path / "rows.tsv"
        raw.write_text("".join(f"{i}\n" for i in range(200000)))
        chunk = tmp_path / "db1@t1@@0.tsv.zst"
        subprocess.run(["zstd", "-q", "-f", str(raw), "-o", str(chunk)], check=True)
        chunk.write_bytes(chunk.read_bytes()[:len(chunk.read_bytes()) // 2])
        args = loader_args(tmp_path)
        _, cols = translate(loader, (tmp_path / "db1@t1.sql").read_text())
        loader.args = args
        with mock.patch.dict(os.environ, fake_client(tmp_path)), pytest.raises(AssertionError):
            loader.load_data_mysqlshell(args, "UTC", {"db1.t1": cols})

    def test_truncated_mydumper_chunk_fails_the_load(self, loader, tmp_path):
        with gzip.open(tmp_path / "db1.t1-schema.sql.gz", "wt") as f:
            f.write("CREATE TABLE `t1` (`id` int NOT NULL, PRIMARY KEY (`id`));")
        data = gzip.compress("".join(f"{i}\n" for i in range(100000)).encode())
        (tmp_path / "db1.t1.00000.dat.gz").write_bytes(data[:len(data) // 2])
        args = loader_args(tmp_path, mysqlshell=False)
        loader.args = args
        _, cols = translate(loader, "CREATE TABLE `t1` (`id` int NOT NULL, PRIMARY KEY (`id`));")
        with mock.patch.dict(os.environ, fake_client(tmp_path)), pytest.raises(AssertionError):
            loader.load_data(args, "UTC", {"db1.t1": cols})


# --------------------------------------------------------------------------------------------- D-13.04-5 '-' in path

class TestMydumperDataFileDiscovery:
    @pytest.mark.parametrize("dirname", ["dump_mydumper", "dump-mydumper"])
    def test_dash_in_dump_dir_still_finds_data(self, loader, tmp_path, dirname):
        d = tmp_path / dirname
        d.mkdir()
        with gzip.open(d / "db1.t1-schema.sql.gz", "wt") as f:
            f.write("CREATE TABLE `t1` (`id` int NOT NULL, PRIMARY KEY (`id`));")
        (d / "db1.t1.00000.dat.gz").write_bytes(gzip.compress(b"1\n"))
        _, cols = translate(loader, "CREATE TABLE `t1` (`id` int NOT NULL, PRIMARY KEY (`id`));")
        commands = []
        with mock.patch.object(loader, "execute_load", commands.append):
            loader.load_data(loader_args(d, mysqlshell=False), "UTC", {"db1.t1": cols})
        assert len(commands) == 1 and str(d / "db1.t1.00000.dat.gz") in commands[0]


# --------------------------------------------------------------------------------------------- resync hand-off

def test_resync_isolated_table_dir_carries_the_dump_metadata(tmp_path):
    from ch_sink_tools.db_load import mysql_resync as mr
    for name in ("@.json", "s@t.sql", "s@t.json", "s@t@@0.tsv.zst"):
        (tmp_path / name).write_text("{}")
    iso = mr.isolate_table_dir(str(tmp_path), "s", "t")
    assert "@.json" in os.listdir(iso)
