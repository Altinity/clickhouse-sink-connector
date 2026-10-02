"""Unit coverage for the PostgreSQL snapshot dumper (Spec 13.05).

Every test is offline: PostgreSQL, ClickHouse and the shell pipe are mocked,
except where a real /bin/bash runs fake ``psql`` / ``clickhouse-client``
scripts (exit-status semantics), and the optional ``clickhouse local``
round trip (skipped when the binary is absent).
"""
import logging
import os
import re
import shutil
import stat
import subprocess
import sys
from unittest.mock import MagicMock

import pytest

import ch_sink_tools.db.postgres as pgmod
import ch_sink_tools.db_dump.postgres_dumper as pd
from ch_sink_tools.config.column_type_overrides import ColumnTypeOverrideConfig
from ch_sink_tools.db_load.postgres_type_mapper import (
    build_create_table,
    build_insert_structure,
    build_select_columns,
)

SNAPSHOT_ID = "00000003-0000001B-1"
LSN_STR = "9C9/21AE7C20"
LSN_INT = 0x9C9 * 2 ** 32 + 0x21AE7C20


def _col(name, pg_type, ch_type):
    return {"column_name": name, "pg_type": pg_type, "ch_type": ch_type,
            "udt_name": pg_type, "nullable": ch_type.startswith("Nullable"),
            "ordinal_position": 1}


def _ok_slot(**over):
    row = {"slot_name": "debezium", "slot_type": "logical", "database": "app",
           "active": False, "restart_lsn": "9C9/10000000",
           "confirmed_flush_lsn": "9C9/20000000"}
    row.update(over)
    return [row]


# ---------------------------------------------------------------------------
# main() harness: every PostgreSQL / ClickHouse / shell call is mocked
# ---------------------------------------------------------------------------

class _Exit(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@pytest.fixture
def harness(monkeypatch):
    st = {
        "tables": {"public": ["t1"]},
        "ch_sql": [], "pg_sql": [], "cmds": [], "copy_files": [],
        "slot_rows": _ok_slot(),
        "ch_counts": {},      # (db, table) -> (count(), count() FINAL)
        "src_counts": {},     # "schema.table" -> count(*)
        "run_rc": "0",
    }
    monkeypatch.setattr(pd, "check_program_exists", lambda n: True)
    monkeypatch.setattr(pd, "get_postgres_connection", lambda *a, **k: MagicMock())
    monkeypatch.setattr(pd, "get_standby_lsn", lambda c: (LSN_STR, LSN_INT))
    monkeypatch.setattr(pd, "get_server_timezone", lambda c: "UTC")
    monkeypatch.setattr(pd, "get_schemas", lambda c, **k: sorted(st["tables"]))
    monkeypatch.setattr(
        pd, "get_tables",
        lambda c, schema, include_regex=None, exclude_regex=None:
            list(st["tables"].get(schema, [])))
    monkeypatch.setattr(
        pd, "get_table_columns",
        lambda *a, **k: [_col("id", "integer", "Int32"), _col("v", "text", "Nullable(String)")])
    monkeypatch.setattr(pd, "get_table_pk", lambda *a: ["id"])
    monkeypatch.setattr(pd, "get_table_row_count", lambda *a: 10)
    monkeypatch.setattr(pd, "get_pg_approx_row_count", lambda *a: 10)
    monkeypatch.setattr(pd, "validate_postgres_privileges", lambda *a, **k: True)
    monkeypatch.setattr(pd, "ensure_heartbeat_table", lambda *a: None)
    monkeypatch.setattr(pd, "clickhouse_connection", lambda *a, **k: MagicMock())

    def ch_exec(conn, sql, *a, **k):
        st["ch_sql"].append(sql)
        m = re.search(r"SELECT count\(\) FROM `([^`]*)`\.`([^`]*)`( FINAL)?", sql)
        if m:
            plain, final = st["ch_counts"].get((m.group(1), m.group(2)), (0, 0))
            return [[final if m.group(3) else plain]]
        return []
    monkeypatch.setattr(pd, "clickhouse_execute_conn", ch_exec)
    monkeypatch.setattr(pd, "ch_table_exists",
                        lambda conn, db, t: (db, t) in st["ch_counts"])

    def pg_exec(conn, sql, params=None):
        st["pg_sql"].append(sql)
        if "pg_export_snapshot" in sql:
            return [{"snapshot_id": SNAPSHOT_ID}]
        if "pg_replication_slots" in sql:
            return st["slot_rows"]
        m = re.search(r'count\(\*\) AS n FROM "([^"]*)"\."([^"]*)"', sql)
        if m:
            return [{"n": st["src_counts"][f"{m.group(1)}.{m.group(2)}"]}]
        return []
    monkeypatch.setattr(pd, "execute_pg", pg_exec)

    def run_command(cmd):
        st["cmds"].append(cmd)
        m = re.search(r"-f (\S+pg_copy_\S+\.sql)", cmd)
        if m:
            with open(m.group(1)) as f:
                st["copy_files"].append(f.read())
        return st["run_rc"]
    monkeypatch.setattr(pd, "run_command", run_command)

    def fake_exit(code):
        raise _Exit(code)
    monkeypatch.setattr(os, "_exit", fake_exit)

    root = logging.getLogger()
    handlers = list(root.handlers)
    level = root.level

    def run(*extra):
        argv = ["ch-pg-dump", "--pg_host", "db1", "--pg_database", "app",
                "--pg_user", "u", "--pg_password", "p", "--ch_host", "ch-host",
                "--threads", "1",
                "--offset_table", "offsets.replica_source_info_app", *extra]
        monkeypatch.setattr(sys, "argv", argv)
        try:
            pd.main()
        except _Exit as e:
            return e.code
        except SystemExit as e:
            return e.code
        finally:
            root.handlers[:] = handlers
            root.setLevel(level)
        return None

    st["run"] = run
    st["offset_written"] = lambda: any(
        s.startswith("INSERT INTO offsets.") for s in st["ch_sql"])
    st["ddl"] = lambda: [s for s in st["ch_sql"] if s.startswith("CREATE TABLE")]
    return st


# ---------------------------------------------------------------------------
# D-13.05-1 / D-13.05-2 / D-13.05-17: exit status of every load decides success
# ---------------------------------------------------------------------------

def _write_script(path, body):
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(path.stat().st_mode | stat.S_IEXEC)


@pytest.mark.skipif(not os.path.exists("/bin/bash"), reason="needs /bin/bash")
class TestLoadExitStatus:
    """Real bash, fake psql / clickhouse-client first on PATH."""

    def _load(self, monkeypatch, tmp_path, psql_body, ch_body):
        _write_script(tmp_path / "psql", psql_body)
        _write_script(tmp_path / "clickhouse-client", ch_body)
        monkeypatch.setattr(pd, "PG_BIN_DIR", str(tmp_path))
        monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
        monkeypatch.setattr(pd, "get_postgres_connection", lambda *a, **k: MagicMock())
        monkeypatch.setattr(pd, "get_table_columns",
                            lambda *a, **k: [_col("id", "integer", "Int32")])
        monkeypatch.setattr(pd, "get_table_pk", lambda *a: ["id"])
        monkeypatch.setattr(pd, "get_table_row_count", lambda *a: 10)
        seen = []
        real_run = pd.run_command

        def spy(cmd):
            seen.extend(re.findall(r"/tmp/(?:pg_copy|ch_insert)_\S+\.sql", cmd))
            return real_run(cmd)
        monkeypatch.setattr(pd, "run_command", spy)
        result = pd.load_table(
            "t1", "db1", 5432, "u", "p", "app", "public",
            "ch-host", 9000, "default", None, "app", snapshot_id=SNAPSHOT_ID)
        return result, seen

    def test_failing_psql_is_a_failed_load(self, monkeypatch, tmp_path):
        result, seen = self._load(monkeypatch, tmp_path,
                                  "echo '\"1\"'\nexit 3\n", "cat >/dev/null\nexit 0\n")
        assert result[3] is False
        assert seen and not any(os.path.exists(p) for p in seen)

    def test_failing_clickhouse_client_is_a_failed_load(self, monkeypatch, tmp_path):
        result, _ = self._load(monkeypatch, tmp_path,
                               "echo '\"1\"'\nexit 0\n", "cat >/dev/null\nexit 27\n")
        assert result[3] is False

    def test_clean_pipe_is_a_successful_load(self, monkeypatch, tmp_path):
        result, seen = self._load(monkeypatch, tmp_path,
                                  "echo '\"1\"'\nexit 0\n", "cat >/dev/null\nexit 0\n")
        assert result[3] is True
        assert seen and not any(os.path.exists(p) for p in seen)

    def test_psqlcopy_dump_fails_when_psql_fails(self, monkeypatch, tmp_path):
        _write_script(tmp_path / "psql", "echo '\"1\"'\nexit 3\n")
        monkeypatch.setattr(pd, "PG_BIN_DIR", str(tmp_path))
        with pytest.raises(RuntimeError):
            pd.psqlcopy_dump_table("t1", ["id"], "db1", 5432, "u", "p", "app",
                                   "public", str(tmp_path / "dump"))


class TestCommandBuilders:
    def test_insert_cmd_ends_with_the_client_not_a_cleanup(self):
        cmd, path = pd.build_ch_insert_cmd(
            "ch-host", 9000, "default", None, "app", "t1", ["id"],
            [_col("id", "integer", "Int32")])
        try:
            assert "rm -f" not in cmd and ";" not in cmd
            assert cmd.rstrip().endswith(f"--queries-file {path}")
        finally:
            os.unlink(path)

    def test_psql_stops_on_error_and_is_quiet(self):
        cmd, path = pd.build_psql_copy_cmd(
            "db1", 5432, "u", "p", "app", "public", "t1", ["id"])
        try:
            assert "-v ON_ERROR_STOP=1" in cmd
            assert " -q" in cmd and " -X" in cmd
        finally:
            os.unlink(path)

    def test_main_exits_1_and_writes_no_offset_when_a_pipe_fails(self, harness):
        harness["run_rc"] = "1"
        assert harness["run"]() == 1
        assert harness["cmds"], "the load must have been attempted"
        assert not harness["offset_written"]()


# ---------------------------------------------------------------------------
# D-13.05-10: session settings pinned on every dump session
# ---------------------------------------------------------------------------

class TestSessionSettings:
    def test_settings_precede_the_copy(self):
        sql = pd.build_copy_session_sql("COPY (SELECT 1) TO STDOUT")
        for line in ("SET TimeZone = 'UTC';", "SET DateStyle = 'ISO, YMD';",
                     "SET IntervalStyle = 'iso_8601';",
                     "SET extra_float_digits = '3';",
                     "SET bytea_output = 'hex';",
                     "SET statement_timeout = '0';"):
            assert line in sql
            assert sql.index(line) < sql.index("COPY (")

    def test_psqlcopy_dump_file_is_pinned_too(self, monkeypatch, tmp_path):
        captured = {}

        class _P:
            stdout = []
            returncode = 0

            def wait(self):
                return 0

        def fake_popen(cmd, **kw):
            captured["cmd"] = cmd
            m = re.search(r"-f (\S+\.sql)", cmd)
            with open(m.group(1)) as f:
                captured["sql"] = f.read()
            return _P()
        monkeypatch.setattr(pd.subprocess, "Popen", fake_popen)
        pd.psqlcopy_dump_table("t1", ["id"], "db1", 5432, "u", "p", "app",
                               "public", str(tmp_path))
        assert captured["cmd"].startswith("set -o pipefail; ")
        assert "-v ON_ERROR_STOP=1" in captured["cmd"]
        assert "SET DateStyle = 'ISO, YMD';" in captured["sql"]


# ---------------------------------------------------------------------------
# D-13.05-3: exported snapshot, LSN consistent with it, slot verified first
# ---------------------------------------------------------------------------

class TestSnapshotAndSlot:
    def test_reader_session_imports_the_snapshot(self):
        sql = pd.build_copy_session_sql("COPY (SELECT 1) TO STDOUT", snapshot_id=SNAPSHOT_ID)
        begin = sql.index("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;")
        imp = sql.index(f"SET TRANSACTION SNAPSHOT '{SNAPSHOT_ID}';")
        assert begin < imp < sql.index("COPY (") < sql.index("COMMIT;")

    def test_bad_snapshot_id_is_rejected(self):
        with pytest.raises(ValueError):
            pd.build_copy_session_sql("COPY x", snapshot_id="x'; DROP")

    def test_lsn_is_read_before_the_snapshot_is_exported(self, monkeypatch):
        calls = []
        monkeypatch.setattr(pd, "get_standby_lsn",
                            lambda c: calls.append("lsn") or (LSN_STR, LSN_INT))

        def pg_exec(conn, sql, params=None):
            calls.append(sql)
            if "pg_export_snapshot" in sql:
                return [{"snapshot_id": SNAPSHOT_ID}]
            return []
        monkeypatch.setattr(pd, "execute_pg", pg_exec)
        assert pd.open_snapshot_coordinator(MagicMock()) == (LSN_STR, LSN_INT, SNAPSHOT_ID)
        assert calls[0] == "lsn"
        assert calls[1] == "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY"
        assert "pg_export_snapshot" in calls[2]

    def _verify(self, monkeypatch, rows):
        monkeypatch.setattr(pd, "execute_pg", lambda c, s, p=None: rows)
        pd.verify_replication_slot(MagicMock(), "debezium", "app", LSN_STR, LSN_INT)

    def test_missing_slot_fails_loudly(self, monkeypatch):
        with pytest.raises(RuntimeError, match="must create its logical replication slot"):
            self._verify(monkeypatch, [])

    def test_slot_beyond_snapshot_lsn_fails(self, monkeypatch):
        with pytest.raises(RuntimeError, match="beyond the snapshot LSN"):
            self._verify(monkeypatch, _ok_slot(confirmed_flush_lsn="9CA/0"))

    def test_restart_lsn_beyond_snapshot_lsn_fails(self, monkeypatch):
        with pytest.raises(RuntimeError, match="restart_lsn"):
            self._verify(monkeypatch, _ok_slot(restart_lsn="9CA/0", confirmed_flush_lsn=None))

    def test_invalidated_slot_fails(self, monkeypatch):
        with pytest.raises(RuntimeError, match="no restart_lsn"):
            self._verify(monkeypatch, _ok_slot(restart_lsn=None))

    def test_slot_of_another_database_fails(self, monkeypatch):
        with pytest.raises(RuntimeError, match="belongs to database"):
            self._verify(monkeypatch, _ok_slot(database="other"))

    def test_physical_slot_fails(self, monkeypatch):
        with pytest.raises(RuntimeError, match="logical"):
            self._verify(monkeypatch, _ok_slot(slot_type="physical"))

    def test_slot_at_or_before_snapshot_lsn_passes(self, monkeypatch):
        self._verify(monkeypatch, _ok_slot(confirmed_flush_lsn=LSN_STR))

    def test_main_refuses_to_dump_without_the_slot(self, harness):
        harness["slot_rows"] = []
        assert harness["run"]() == 1
        assert harness["ddl"]() == [] and harness["cmds"] == []
        assert not harness["offset_written"]()

    def test_main_reads_every_table_in_the_exported_snapshot(self, harness):
        harness["tables"] = {"public": ["t1", "t2"]}
        assert harness["run"]() == 0
        assert len(harness["copy_files"]) == 2
        for sql in harness["copy_files"]:
            assert f"SET TRANSACTION SNAPSHOT '{SNAPSHOT_ID}';" in sql
        offset = [s for s in harness["ch_sql"] if s.startswith("INSERT INTO offsets.")]
        assert len(offset) == 1 and f'"lsn":{LSN_INT}' in offset[0]

    def test_slot_name_comes_from_the_cli(self, harness):
        harness["slot_rows"] = _ok_slot(slot_name="conn2")
        assert harness["run"]("--replication_slot", "conn2") == 0


# ---------------------------------------------------------------------------
# D-13.05-4: --skip_existing skips only proven-complete tables, never an LSN
# ---------------------------------------------------------------------------

class TestSkipExisting:
    def test_partly_loaded_table_fails_loudly(self, harness):
        # t1 is partial (5 of 7 rows); t2 is empty.  The run must stop before
        # loading anything, not skip t1 and carry on.
        harness["tables"] = {"public": ["t1", "t2"]}
        harness["ch_counts"] = {("app", "t1"): (5, 5)}
        harness["src_counts"] = {"public.t1": 7}
        assert harness["run"]("--skip_existing", "--data_only") == 1
        assert harness["cmds"] == []
        assert not harness["offset_written"]()

    def test_complete_table_is_skipped_but_no_offset_is_written(self, harness):
        harness["tables"] = {"public": ["t1", "t2"]}
        harness["ch_counts"] = {("app", "t1"): (5, 5)}
        harness["src_counts"] = {"public.t1": 5}
        assert harness["run"]("--skip_existing", "--data_only") == 1
        assert len(harness["copy_files"]) == 1 and '"public"."t2"' in harness["copy_files"][0]
        assert not harness["offset_written"]()


# ---------------------------------------------------------------------------
# D-13.05-5: keyless tables are never ORDER BY tuple()
# ---------------------------------------------------------------------------

class TestKeylessSortingKey:
    def test_all_columns_key(self):
        ddl = build_create_table("app", "t", [_col("a", "integer", "Int32"),
                                              _col("b", "text", "String")], [])
        assert "tuple()" not in ddl
        assert "ORDER BY (`a`, `b`)" in ddl
        assert "allow_nullable_key" not in ddl

    def test_nullable_column_enables_nullable_key(self):
        ddl = build_create_table("app", "t", [_col("a", "integer", "Int32"),
                                              _col("b", "text", "Nullable(String)")], [])
        assert "ORDER BY (`a`, `b`)" in ddl
        assert "SETTINGS index_granularity = 8192, allow_nullable_key = 1" in ddl

    def test_keyed_table_unchanged(self):
        ddl = build_create_table("app", "t", [_col("a", "integer", "Int32")], ["a"])
        assert "ORDER BY (`a`)" in ddl and "allow_nullable_key" not in ddl

    def test_unused_legacy_ddl_builder_follows_the_same_rule(self):
        ddl = pgmod.build_ch_create_table_ddl(
            "public", "t", [_col("a", "integer", "Nullable(Int32)")], [], "app")
        assert "tuple()" not in ddl and "ORDER BY (`a`)" in ddl
        assert "allow_nullable_key = 1" in ddl


# ---------------------------------------------------------------------------
# D-13.05-6 / D-13.05-7: connector table lists and target collisions
# ---------------------------------------------------------------------------

TABLES = ["users", "users_archive", "orders", "audit_log", "login_events", "catalog", "log"]


class TestConnectorLists:
    def test_exclude_keeps_schema_and_is_anchored(self):
        rx = pd._debezium_list_to_regex("public.log")
        kept = pd.filter_tables_by_qualified_regex("public", TABLES, exclude_pattern=rx)
        assert kept == [t for t in TABLES if t != "log"]
        assert pd.filter_tables_by_qualified_regex("sales", ["log"], exclude_pattern=rx) == ["log"]

    def test_include_is_anchored(self):
        rx = pd._debezium_list_to_regex("public.users")
        assert pd.filter_tables_by_qualified_regex("public", TABLES, include_pattern=rx) == ["users"]

    def test_same_table_in_two_schemas_stays_distinct(self):
        rx = pd._debezium_list_to_regex("sales.items, hr.items")
        assert pd.filter_tables_by_qualified_regex("sales", ["items"], include_pattern=rx) == ["items"]
        assert pd.filter_tables_by_qualified_regex("public", ["items"], include_pattern=rx) == []

    def test_regex_entries_match_the_whole_name(self):
        rx = pd._debezium_list_to_regex(r"public\.user.*")
        assert pd.filter_tables_by_qualified_regex("public", TABLES, include_pattern=rx) == \
            ["users", "users_archive"]

    def test_connector_config_translation(self):
        m = pd.parse_sink_connector_config({
            "table.include.list": "public.users,sales.items",
            "table.exclude.list": "public.log",
            "schema.include.list": "public",
            "database.include.list": "app",
            "slot.name": "conn2",
        })
        assert m["table_include_list"] == "^(?:public.users|sales.items)$"
        assert m["table_exclude_list"] == "^(?:public.log)$"
        assert m["pg_schema_include"] == "^(?:public)$"
        assert m["database_include_list"] == "^(?:app)$"
        assert m["replication_slot"] == "conn2"
        assert "pg_table_include" not in m and "pg_table_exclude" not in m

    def test_database_outside_the_include_list_stops_the_run(self, harness):
        assert harness["run"]("--database_include_list", "^(?:other)$") == 1
        assert harness["pg_sql"] == []

    def test_main_applies_the_qualified_lists(self, harness):
        harness["tables"] = {"public": ["users", "users_archive"]}
        assert harness["run"]("--table_include_list", "^(?:public.users)$") == 0
        assert len(harness["copy_files"]) == 1 and '"public"."users"' in harness["copy_files"][0]


class TestTargetCollisions:
    def test_find_target_collisions(self):
        items = [("public", "items", "app", "items"), ("sales", "items", "app", "items"),
                 ("public", "users", "app", "users")]
        assert pd.find_target_collisions(items) == {("app", "items"): ["public.items", "sales.items"]}

    def test_main_refuses_colliding_targets(self, harness):
        harness["tables"] = {"public": ["items"], "sales": ["items"]}
        assert harness["run"]("--pg_schema", "public", "sales", "--ch_database", "app") == 1
        assert harness["ch_sql"] == [] and harness["cmds"] == []

    def test_schema_aware_template_avoids_the_collision(self, harness):
        harness["tables"] = {"public": ["items"], "sales": ["items"]}
        assert harness["run"]("--pg_schema", "public", "sales",
                              "--ch_table_template", "{{ schema }}___{{ table }}") == 0


# ---------------------------------------------------------------------------
# D-13.05-8 / D-13.05-9: temporal conversion
# ---------------------------------------------------------------------------

class TestTemporalConversion:
    def test_zone_less_timestamp_is_parsed_in_the_column_zone(self):
        sel = build_select_columns([_col("ts", "timestamp without time zone",
                                         "DateTime64(6, 'America/Chicago')")])
        assert "parseDateTime64BestEffortOrNull(\"ts\", 6, 'America/Chicago')" in sel
        assert "endsWith(\"ts\", ' BC'), toTimeZone(" in sel   # BC saturates

    def test_special_values_saturate_and_unparseable_raises(self):
        sel = build_select_columns([_col("ts", "timestamp with time zone",
                                         "Nullable(DateTime64(6, 'UTC'))")])
        assert "\"ts\" = 'infinity', toTimeZone(toDateTime64('2299-12-31 23:59:59', 6, 'UTC'), 'UTC')" in sel
        assert "\"ts\" = '-infinity', toTimeZone(toDateTime64('1900-01-01 00:00:00', 6, 'UTC'), 'UTC')" in sel
        assert sel.startswith("if(throwIf(isNotNull(\"ts\") AND ")
        # a BC timestamptz is refused, as the connector refuses it
        assert "OR endsWith(\"ts\", ' BC'))" in sel

    def test_date32_saturates(self):
        sel = build_select_columns([_col("d", "date", "Date32")])
        assert "\"d\" = 'infinity', toDate32('2299-12-31')" in sel
        assert "endsWith(\"d\", ' BC'), toDate32('1900-01-01')" in sel
        assert sel.startswith("if(throwIf(")


CLICKHOUSE = shutil.which("clickhouse")


@pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse binary not installed")
class TestTemporalConversionClickHouseLocal:
    """Evaluate the generated INSERT ... SELECT with clickhouse local."""

    COLUMNS = [
        _col("ts", "timestamp without time zone", "Nullable(DateTime64(6, 'America/Chicago'))"),
        _col("tz", "timestamp with time zone", "Nullable(DateTime64(6, 'UTC'))"),
        _col("d", "date", "Nullable(Date32)"),
    ]

    def _run(self, tmp_path, csv):
        (tmp_path / "in.csv").write_text(csv)
        q = (
            "CREATE TABLE t (ts Nullable(DateTime64(6, 'America/Chicago')), "
            "tz Nullable(DateTime64(6, 'UTC')), d Nullable(Date32)) ENGINE = Memory; "
            f"INSERT INTO t SELECT {build_select_columns(self.COLUMNS)} "
            f"FROM file('{tmp_path}/in.csv', CSV, '{build_insert_structure(self.COLUMNS)}') "
            "SETTINGS format_csv_null_representation='', input_format_csv_empty_as_default=0; "
            "SELECT toString(ts, 'UTC'), toString(tz), toString(d) FROM t FORMAT TSV"
        )
        return subprocess.run([CLICKHOUSE, "local", "--session_timezone=UTC", "-n", "-q", q],
                              capture_output=True, text=True, timeout=60)

    def test_round_trip(self, tmp_path):
        r = self._run(tmp_path,
                      '"2024-01-10 08:30:00","2024-01-10 08:30:00+00","2024-01-10"\n'
                      '"infinity","-infinity","infinity"\n'
                      '"9999-12-31 00:00:00","1850-01-01 00:00:00+00","0044-03-15 BC"\n'
                      ',,\n')
        assert r.returncode == 0, r.stderr
        rows = [line.split("\t") for line in r.stdout.strip().split("\n")]
        # wall clock read in the column zone (CST = UTC-6)
        assert rows[0] == ["2024-01-10 14:30:00.000000", "2024-01-10 08:30:00.000000", "2024-01-10"]
        assert rows[1] == ["2299-12-31 23:59:59.000000", "1900-01-01 00:00:00.000000", "2299-12-31"]
        assert rows[2] == ["2299-12-31 23:59:59.000000", "1900-01-01 00:00:00.000000", "1900-01-01"]
        assert rows[3] == ["\\N", "\\N", "\\N"]

    def test_unparseable_value_fails_the_insert(self, tmp_path):
        r = self._run(tmp_path, '"garbage","2024-01-10 08:30:00+00","2024-01-10"\n')
        assert r.returncode != 0
        assert "no ClickHouse representation" in r.stderr

    def test_bc_timestamptz_fails_the_insert(self, tmp_path):
        r = self._run(tmp_path, '"2024-01-10 08:30:00","0044-03-15 10:00:00+00 BC","2024-01-10"\n')
        assert r.returncode != 0


# ---------------------------------------------------------------------------
# D-13.05-11: the load honours direct type overrides
# ---------------------------------------------------------------------------

class TestLoadHonoursOverrides:
    def test_overridden_column_is_loaded_as_overridden(self, monkeypatch):
        monkeypatch.setattr(pgmod, "execute_pg", lambda c, s, p=None: [{
            "column_name": "ts", "pg_type": "timestamp without time zone",
            "ordinal_position": 1, "is_nullable": "YES",
            "character_maximum_length": None, "numeric_precision": None,
            "numeric_scale": None, "udt_name": "timestamp"}])
        monkeypatch.setattr(pd, "get_postgres_connection", lambda *a, **k: MagicMock())
        monkeypatch.setattr(pd, "get_table_pk", lambda *a: [])
        monkeypatch.setattr(pd, "get_table_row_count", lambda *a: 1)
        inserts = []

        def run_command(cmd):
            m = re.search(r"--queries-file (\S+)", cmd)
            with open(m.group(1)) as f:
                inserts.append(f.read())
            return "0"
        monkeypatch.setattr(pd, "run_command", run_command)
        overrides = ColumnTypeOverrideConfig.from_cli_string("direct:public.t1.ts=String")
        result = pd.load_table("t1", "db1", 5432, "u", "p", "app", "public",
                               "ch-host", 9000, "default", None, "app",
                               pg_server_timezone="UTC", override_config=overrides)
        assert result[3] is True
        assert 'SELECT "ts" FROM input(' in inserts[0]
        assert "parseDateTime64" not in inserts[0]


# ---------------------------------------------------------------------------
# D-13.05-12: the corrupting pgdump converter refuses to run
# ---------------------------------------------------------------------------

class TestPgdumpStrategyRefused:
    def test_load_function_refuses(self):
        with pytest.raises(RuntimeError, match="pgdump is disabled"):
            pd.pgdump_load_table("t1", "/backups/dump", "ch-host", 9000, "default",
                                 None, "app", "t1", ["id"], [])

    def test_main_refuses_before_touching_anything(self, harness):
        assert harness["run"]("--strategy", "pgdump") == 2
        assert harness["pg_sql"] == [] and harness["ch_sql"] == []
