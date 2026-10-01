"""BIT(1) is loaded as the streaming connector stores it: a Bool column holding true/false (Spec 13.04 D-13.04-10,
BIT(1) part), run against BOTH loader copies.

Debezium emits BIT(1) as BOOLEAN and the streaming DDL path declares Bool. The loader declared String and loaded
MySQL Shell's byte as hex text '01'/'00', so the production checksum job reported every snapshot table with a BIT(1)
column DIFFERENT (found end to end: sink-connector/python/tests_e2e/mysql/test_mysql_03_snapshot.py). BIT(n>1)
stays hex text under every binary mode.

Offline: no database. The value check uses ``clickhouse local`` and is skipped when it is not installed.
Run from sink-connector/python:  python -m pytest db_load/tests/test_loader_bit1_bool.py
"""
import importlib
import os
import shutil
import subprocess
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

COPIES = {"legacy": "db_load.clickhouse_loader", "packaged": "ch_sink_tools.db_load.clickhouse_loader"}

DDL = ("CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `flag` bit(1) NOT NULL,\n  `maybe` bit(1) DEFAULT NULL,\n"
       "  `plain` bit DEFAULT NULL,\n  `mask` bit(16) DEFAULT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")


@pytest.fixture(params=sorted(COPIES))
def loader(request):
    return importlib.import_module(COPIES[request.param])


def translate(loader):
    return loader.convert_to_clickhouse_table("u", "t", DDL, True, False, None)


def select_list(loader, **kw):
    _, cols = translate(loader)
    listed = loader.get_column_list({"db1.t": cols}, "db1", "t", [], transform=True, mysqlshell=True, **kw)
    parts, depth, current = [], 0, ""
    for ch in listed:
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
            continue
        depth += {"(": 1, ")": -1}.get(ch, 0)
        current += ch
    return parts + [current]


def test_bit1_columns_are_declared_bool(loader):
    ddl, cols = translate(loader)
    types = {c["column_name"].strip("`"): c["datatype"].strip() for c in cols}
    assert types["flag"] == "Bool" and types["maybe"] == "Bool" and types["plain"] == "Bool", types
    assert types["mask"] == "String", types
    assert "`flag` Bool NOT NULL" in ddl and "`maybe` Bool" in ddl, ddl


@pytest.mark.parametrize("mode", ["bytes", "base64", "hex"])
def test_bit1_values_are_true_false_under_every_binary_mode(loader, mode):
    cols = select_list(loader, binary_handling_mode=mode)
    b64 = "base64Decode(replaceAll(\\`{0}\\`, char(10), ''))"
    assert cols[1] == f"({b64.format('flag')}) != char(0)", cols
    assert cols[2] == f"({b64.format('maybe')}) != char(0)", cols
    assert cols[4] == f"lower(hex({b64.format('mask')}))", cols


def test_bit1_with_persist_raw_bytes_is_still_bool(loader):
    assert select_list(loader, persist_raw_bytes=True)[1].endswith("!= char(0)")


@pytest.mark.skipif(shutil.which("clickhouse") is None, reason="clickhouse local not installed")
def test_bit1_values_load_into_bool(loader, tmp_path):
    # MySQL Shell TSV text (TO_BASE64): b'1' -> AQ==, b'0' -> AA==, NULL -> \N; mask b'1010101111001101' -> q80=
    (tmp_path / "in.tsv").write_text("1\tAQ==\tAA==\tAQ==\tq80=\n2\tAA==\t\\N\t\\N\t\\N\n")
    exprs = [c.replace("\\`", "`") for c in select_list(loader, binary_handling_mode="base64")]
    structure = "`id` String, `flag` String, `maybe` Nullable(String), `plain` Nullable(String), `mask` Nullable(String)"
    query = ("CREATE TABLE t (id Int32, flag Bool, maybe Nullable(Bool), plain Nullable(Bool), mask Nullable(String)) "
             f"ENGINE = Memory; INSERT INTO t SELECT {', '.join(exprs)} FROM file('{tmp_path / 'in.tsv'}', TSV, "
             f"'{structure}'); SELECT * FROM t ORDER BY id FORMAT TSV")
    out = subprocess.run(["clickhouse", "local", "-n", "-q", query], capture_output=True, text=True, check=True).stdout
    assert [line.split("\t") for line in out.splitlines()] == [
        ["1", "true", "false", "true", "abcd"], ["2", "false", "\\N", "\\N", "\\N"]]
