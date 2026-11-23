"""ch-mysql-resync helpers: DDL parsing of ENUM/SET, the type suggestion for tinyint(1) unsigned and BIT(1), and the
canary's delete-marker handling (Spec 13.08 D-13.08-D2).

Offline: the ClickHouse client is replaced by a recording fake.

Run from sink-connector/python:  python -m pytest db_load/tests/test_mysql_resync_d2.py
"""
import os
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

from ch_sink_tools.db_load import mysql_resync as mr  # noqa: E402


def ddl(*column_lines):
    return "CREATE TABLE `t` (\n" + ",\n".join(f"  {line}" for line in column_lines) + "\n) ENGINE=InnoDB;\n"


# ------------------------------------------------------------------------------------------- (a) ENUM / SET parsing

class TestEnumAndSetColumns:
    @pytest.mark.parametrize("ctype", [
        "enum('a','b')",
        "enum('new order','in progress','done')",       # spaces inside values
        "enum('a,b','c')",                              # a comma inside a value
        "enum('x)y','z')",                              # a closing parenthesis inside a value
        "enum('it''s','plain')",                        # a doubled quote
        "enum('back\\\\slash','b')",
        "set('read','write access','x,y')",
        "enum('a b', 'c d')",
    ])
    def test_type_is_whole(self, ctype):
        cols = mr.parse_mysql_ddl(ddl(f"`kind` {ctype} NOT NULL DEFAULT 'a'", "`id` int NOT NULL"))
        assert [c[0] for c in cols] == ["kind", "id"]
        assert cols[0][1] == ctype
        assert cols[0][2] is False  # NOT NULL read from what follows the type

    def test_nullable_enum_with_spaces(self):
        (col,) = mr.parse_mysql_ddl(ddl("`kind` enum('a b','c') DEFAULT NULL"))
        assert col == ("kind", "enum('a b','c')", True, False)

    def test_columns_after_a_spaced_enum_are_still_parsed(self):
        cols = mr.parse_mysql_ddl(ddl("`e` enum('one two','three') NOT NULL", "`n` varchar(20) DEFAULT NULL",
                                      "`u` int unsigned NOT NULL"))
        assert [(c[0], c[1]) for c in cols] == [("e", "enum('one two','three')"), ("n", "varchar(20)"),
                                                ("u", "int unsigned")]

    @pytest.mark.parametrize("ctype", ["enum('a b','c')", "set('x y','z')"])
    def test_suggested_clickhouse_type_is_string(self, ctype):
        assert mr.mysql_to_ch(ctype, False) == "String"
        assert mr.mysql_to_ch(ctype, True) == "Nullable(String)"

    def test_other_types_unchanged(self):
        cols = mr.parse_mysql_ddl(ddl("`a` decimal(30,10) NOT NULL", "`b` bigint unsigned zerofill NOT NULL",
                                      "`c` datetime(6) DEFAULT NULL", "`d` double DEFAULT NULL", "`j` json DEFAULT NULL",
                                      "`s` int GENERATED ALWAYS AS (`a` + 1) VIRTUAL"))
        assert [c[1] for c in cols] == ["decimal(30,10)", "bigint unsigned zerofill", "datetime(6)", "double", "json",
                                        "int"]
        assert cols[-1][3] is True

    def test_drift_ddl_uses_the_whole_enum_type_as_a_comment(self):
        cols = mr.parse_mysql_ddl(ddl("`kind` enum('a b','c') NOT NULL"))
        (stmt,) = mr.drift_ddl("db", "t", cols, cols)
        assert "`kind` String" in stmt and "enum('a b','c') NOT NULL" in stmt


# ------------------------------------------------------------------------------------------------- (b) type map

class TestTinyintAndBit:
    @pytest.mark.parametrize("mysql_type, expected", [
        ("tinyint(1)", "Int8"),
        ("tinyint(1) unsigned", "UInt8"),
        ("tinyint(1) unsigned zerofill", "UInt8"),
        ("TINYINT(1) UNSIGNED", "UInt8"),
        ("tinyint unsigned", "UInt8"),
        ("tinyint(4)", "Int8"),
        ("tinyint", "Int8"),
        ("bit(1)", "Bool"),
        ("bit", "Bool"),
        ("BIT(1)", "Bool"),
        ("bit( 1 )", "Bool"),
        ("bit(8)", "String"),
        ("bit(64)", "String"),
        ("bit(10)", "String"),
    ])
    def test_suggested_type(self, mysql_type, expected):
        assert mr.mysql_to_ch(mysql_type, False) == expected

    def test_nullable_wrapping(self):
        assert mr.mysql_to_ch("tinyint(1) unsigned", True) == "Nullable(UInt8)"
        assert mr.mysql_to_ch("bit(1)", True) == "Nullable(Bool)"

    def test_column_parsed_from_ddl_maps_end_to_end(self):
        cols = mr.parse_mysql_ddl(ddl("`flag` tinyint(1) unsigned NOT NULL", "`b1` bit(1) DEFAULT NULL",
                                      "`b8` bit(8) DEFAULT NULL"))
        assert [mr.mysql_to_ch(c[1], c[2]) for c in cols] == ["UInt8", "Nullable(Bool)", "Nullable(String)"]


# -------------------------------------------------------------------------------------- (c) canary delete markers

class TestCanaryFilter:
    @pytest.mark.parametrize("marker", ["is_deleted", "_is_deleted", "__is_deleted"])
    def test_each_delete_marker_is_excluded_and_filtered(self, marker):
        exclude, where = mr.canary_filter({"id", "v", "_version", marker})
        assert exclude == ["_version", marker]
        assert where == f"WHERE `{marker}` = 0"

    def test_sign_is_excluded_and_filtered_to_live_rows(self):
        exclude, where = mr.canary_filter({"id", "_version", "_sign"})
        assert exclude == ["_version", "_sign"]
        assert where == "WHERE `_sign` > 0"

    def test_marker_and_sign_combine(self):
        exclude, where = mr.canary_filter({"_version", "_sign", "_is_deleted"})
        assert exclude == ["_version", "_is_deleted", "_sign"]
        assert where == "WHERE `_is_deleted` = 0 AND `_sign` > 0"

    def test_no_marker_no_where(self):
        assert mr.canary_filter({"id", "v"}) == (["_version"], "")

    def test_absent_columns_are_never_named(self):
        exclude, _ = mr.canary_filter({"id", "_is_deleted"})
        assert "is_deleted" not in exclude and "_sign" not in exclude

    def test_source_columns_are_not_excluded(self):
        exclude, where = mr.canary_filter({"id", "deleted", "sign"})
        assert exclude == ["_version"] and where == ""


class RecordingClickHouse:
    def __init__(self, columns):
        self.columns = columns
        self.queries = []

    def rows(self, sql, timeout=3600):
        if "FROM system.columns" in sql:
            return [[c] for c in self.columns]
        self.queries.append(sql)
        return [["7", "9"]]


class TestCanaryRatioQuery:
    def run(self, columns):
        ch = RecordingClickHouse(columns)
        result = mr.canary_ratio(ch, "s", "s_restore", "t", "id")
        assert result == (7, 9)
        (sql,) = ch.queries
        return sql

    @pytest.mark.parametrize("marker", ["is_deleted", "_is_deleted", "__is_deleted"])
    def test_query_excludes_marker_from_hash_and_drops_deleted_live_rows(self, marker):
        sql = self.run(["id", "v", "_version", marker])
        assert sql.count(f"* EXCEPT (`_version`, `{marker}`)") == 2
        assert f"FINAL WHERE `{marker}` = 0" in sql

    def test_query_with_sign(self):
        sql = self.run(["id", "v", "_version", "_sign"])
        assert sql.count("* EXCEPT (`_version`, `_sign`)") == 2
        assert "FINAL WHERE `_sign` > 0" in sql

    def test_query_without_markers_has_no_where(self):
        sql = self.run(["id", "v", "_version"])
        assert "WHERE" not in sql and sql.count("* EXCEPT (`_version`)") == 2

    def test_sorting_key_that_is_not_plain_columns_is_not_evaluated(self):
        assert mr.canary_ratio(RecordingClickHouse([]), "s", "s_restore", "t", "cityHash64(id)") is None


# -------------------------------------------------------------------------------------- (d) MySQL 8.4 binlog status

class TestBinlogStatusCapture:
    @pytest.mark.parametrize("version, expected", [
        ("5.7.44", "SHOW MASTER STATUS"),
        ("8.0.41-32", "SHOW MASTER STATUS"),
        ("8.1.0", "SHOW MASTER STATUS"),
        ("8.2.0", "SHOW BINARY LOG STATUS"),
        ("8.4.11-11", "SHOW BINARY LOG STATUS"),
        ("9.0.1", "SHOW BINARY LOG STATUS"),
        ("10.11.6-MariaDB", "SHOW MASTER STATUS"),
        ("11.4.2-MariaDB-ubu2404", "SHOW MASTER STATUS"),
        ("", "SHOW MASTER STATUS"),
    ])
    def test_statement_selection_from_version(self, version, expected):
        assert mr.binary_log_status_statement(version) == expected

    @pytest.mark.parametrize("version, expected_stmt", [
        ("8.0.41", "SHOW MASTER STATUS"),
        ("8.4.11-11", "SHOW BINARY LOG STATUS"),
    ])
    def test_capture_binlog_position_uses_version_appropriate_statement(self, monkeypatch, version, expected_stmt):
        calls = []

        def fake_mysqlsh_run(mysqlsh, uri, password, mode_args, stdin_extra="", timeout=None):
            calls.append(mode_args)
            sql = mode_args[-1]
            if "@@version" in sql:
                return (
                    0,
                    f'{{"version": "{version}", "taken_at": "2026-01-01 00:00:00", '
                    f'"source_host": "src-host", "ts_sec": 1700000000}}\n',
                    "",
                )
            assert sql == f"{expected_stmt};"
            return (
                0,
                '{"File": "mysql-bin.000042", "Position": 12345, "Executed_Gtid_Set": "uuid:1-9"}\n',
                "",
            )

        monkeypatch.setattr(mr, "mysqlsh_run", fake_mysqlsh_run)
        pos = mr.capture_binlog_position("mysqlsh", "u@h:3306", "pw")
        assert pos == {
            "taken_at": "2026-01-01 00:00:00",
            "source_host": "src-host",
            "ts_sec": 1700000000,
            "file": "mysql-bin.000042",
            "pos": 12345,
            "gtid_executed": "uuid:1-9",
        }
        assert len(calls) == 2

    def test_capture_binlog_position_raises_when_no_row(self, monkeypatch):
        def fake_mysqlsh_run(mysqlsh, uri, password, mode_args, stdin_extra="", timeout=None):
            if "@@version" in mode_args[-1]:
                return 0, '{"version": "8.4.11", "taken_at": "t", "source_host": "h", "ts_sec": 1}\n', ""
            return 0, "", ""

        monkeypatch.setattr(mr, "mysqlsh_run", fake_mysqlsh_run)
        with pytest.raises(RuntimeError, match="SHOW BINARY LOG STATUS returned no row"):
            mr.capture_binlog_position("mysqlsh", "u@h:3306", "pw")

    def test_capture_binlog_position_strips_mysqlsh_password_prompt_prefix(self, monkeypatch):
        """mysqlsh --passwords-from-stdin writes its ANSI password prompt to stdout without a newline before row 1."""
        prompt = "\x1b[1mPlease provide the password for 'root@127.0.0.1:3306': \x1b[0m"
        calls = []

        def fake_mysqlsh_run(mysqlsh, uri, password, mode_args, stdin_extra="", timeout=None):
            sql = mode_args[-1]
            calls.append(sql)
            if "@@version" in sql:
                return (
                    0,
                    prompt + '{"version":"8.4.11-11","taken_at":"2026-11-23 09:18:02",'
                    '"source_host":"src-host","ts_sec":1795447082}\n',
                    "",
                )
            assert sql == "SHOW BINARY LOG STATUS;"
            return (
                0,
                prompt + '{"File":"mysql-bin.000001","Position":1162,'
                '"Executed_Gtid_Set":"uuid:1-4"}\n',
                "",
            )

        monkeypatch.setattr(mr, "mysqlsh_run", fake_mysqlsh_run)
        pos = mr.capture_binlog_position("mysqlsh", "root@127.0.0.1:3306", "pw")
        assert pos == {
            "taken_at": "2026-11-23 09:18:02",
            "source_host": "src-host",
            "ts_sec": 1795447082,
            "file": "mysql-bin.000001",
            "pos": 1162,
            "gtid_executed": "uuid:1-4",
        }
        assert len(calls) == 2

    def test_parse_mysqlsh_json_rows_handles_prompts_braces_and_noise(self):
        out = (
            "non-json notice line\n"
            "\x1b[1mPlease provide the password for 'u@h': \x1b[0m{\"a\": 1}\n"
            "prefix {not json} {\"b\": 2}\n"
            "[1, 2, 3]\n"
        )
        assert mr.parse_mysqlsh_json_rows(out) == [{"a": 1}, {"b": 2}]


