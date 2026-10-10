"""Offline tests for the snapshot guarantees of the MySQL dumper (Spec 13.03).

Both copies are covered: the legacy script (db_dump/mysql_dumper.py) and the
packaged entry point (ch_sink_tools/db_dump/mysql_dumper.py, ``ch-mysql-dump``).
No database, no mysqlsh: the connection layer and ``run_command`` are replaced
by fakes, and the fake mysqlsh writes MySQL Shell dump metadata (``@.json``,
``<schema>.json``, ``@.done.json``) into a temporary dump directory.

Covered defects:
- D-13.03-1  snapshot position read from the dump metadata, checked, logged and
             written to ``snapshot_position.json``.
- D-13.03-2  no explicit partition list without a partition filter, and a
             post-dump check for source tables missing from the dump.
- D-13.03-12 ``run_command`` returns the real exit status (``wait()``).
- D-13.03-13 failures exit non-zero under ``python -O``.
"""
import json
import logging
import os
import re
import subprocess
import sys

import pytest

from db_dump import mysql_dumper as legacy_md
from ch_sink_tools.db_dump import mysql_dumper as pkg_md

PYTHON_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

COPIES = [pytest.param(legacy_md, id="legacy"), pytest.param(pkg_md, id="packaged")]

DB = "appdb"


class FakeResult(list):
    """The subset of a SQLAlchemy result the dumper reads: rows by name through mappings()."""

    def mappings(self):
        return self

    def fetchall(self):
        return list(self)


class FakeConn:
    def close(self):
        pass


def base_metadata(**overrides):
    """Root dump metadata with the keys MySQL Shell writes (Dumper::write_dump_started_metadata)."""
    meta = {
        "dumper": "mysqlsh Ver 8.4.0",
        "version": "2.0.1",
        "origin": "dumpTables",
        "schemas": [DB],
        "basenames": {DB: DB},
        "tzUtc": True,
        "user": "dumper",
        "hostname": "db1",
        "server": "db1",
        "serverVersion": "8.4.0",
        "gtidExecutedInconsistent": False,
        "consistent": True,
        "binlogFile": "binlog.000042",
        "binlogPosition": 157,
        "gtidExecuted": "3e11fa47-71ca-11e1-9e33-c80aa9429562:1-100,\n"
                        "4f22fb58-71ca-11e1-9e33-c80aa9429563:1-5",
        "begin": "2026-10-01 00:00:00",
    }
    meta.update(overrides)
    return meta


class FakeMysqlsh:
    """Stands in for run_command: records the util.dumpTables statement and writes dump metadata."""

    def __init__(self, dump_dir, dumped_tables, metadata=None, done=True, rc="0", drop_keys=(),
                 write=True, root_metadata=True):
        self.dump_dir = dump_dir
        self.dumped_tables = dumped_tables
        self.metadata = metadata if metadata is not None else base_metadata()
        for k in drop_keys:
            self.metadata.pop(k, None)
        self.done = done
        self.rc = rc
        self.write = write
        self.root_metadata = root_metadata
        self.statement = None

    def __call__(self, cmd):
        temp_path = re.search(r"-f\s+(\S+)", cmd).group(1)
        with open(temp_path) as f:
            self.statement = f.read()
        if self.rc != "0" or not self.write:
            return self.rc
        os.makedirs(self.dump_dir)
        if self.root_metadata:
            with open(os.path.join(self.dump_dir, "@.json"), "w") as f:
                json.dump(self.metadata, f)
        with open(os.path.join(self.dump_dir, f"{DB}.json"), "w") as f:
            json.dump({"schema": DB, "includesDdl": True, "includesData": True,
                       "tables": list(self.dumped_tables)}, f)
        if self.done:
            with open(os.path.join(self.dump_dir, "@.done.json"), "w") as f:
                json.dump({"end": "2026-10-01 00:05:00", "dataBytes": 1}, f)
        return "0"


class Source:
    """Fake information_schema listing; each call returns the next snapshot of the source."""

    def __init__(self, *listings, partitions=None):
        self.listings = list(listings)
        self.calls = 0
        self.partitions = partitions or {}

    def _current(self):
        return self.listings[min(self.calls, len(self.listings) - 1)]

    def tables(self, conn, no_wc, db, include_re, exclude_tables_regex=None,
               non_partitioned_tables_only=False, include_partitions_regex=None):
        tables = self._current()
        self.calls += 1
        return FakeResult({"table_schema": db, "table_name": t} for t in tables)

    def parts(self, conn, db, include_re, exclude_tables_regex=None, include_partitions_regex=None,
              non_partitioned_tables_only=False, limit=None):
        # called right after tables() for the same listing
        tables = self.listings[min(self.calls - 1, len(self.listings) - 1)]
        rows = []
        for t in tables:
            names = self.partitions.get(t, [None])
            for p in names:
                if include_partitions_regex and (p is None or not re.search(include_partitions_regex, p)):
                    continue
                rows.append({"table_schema": db, "table_name": t, "partition_name": p})
        return FakeResult(rows)


def run_main(md, monkeypatch, tmp_path, fake, source, extra_args=()):
    monkeypatch.setattr(md, "check_program_exists", lambda name: True)
    monkeypatch.setattr(md, "resolve_credentials_from_config", lambda f: ("dumper", "pw"))
    monkeypatch.setattr(md, "get_mysql_connection", lambda *a, **k: FakeConn())
    monkeypatch.setattr(md, "get_tables_from_regex", source.tables)
    monkeypatch.setattr(md, "get_partitions_from_regex", source.parts)
    monkeypatch.setattr(md, "run_command", fake)
    argv = ["mysql_dumper", "--mysql_host", "db1", "--mysql_database", DB,
            "--dump_dir", fake.dump_dir, "--defaults_file", str(tmp_path / "my.cnf")] + list(extra_args)
    monkeypatch.setattr(sys, "argv", argv)
    root = logging.getLogger()
    before = list(root.handlers)
    try:
        with pytest.raises(SystemExit) as exc:
            md.main()
    finally:
        for h in list(root.handlers):
            if h not in before:
                root.removeHandler(h)
    return exc.value.code


def read_position(dump_dir):
    with open(os.path.join(dump_dir, "snapshot_position.json")) as f:
        return json.load(f)


# ---------------------------------------------------------------------------------------------------------------
# D-13.03-1: snapshot position handoff
# ---------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("md", COPIES)
class TestSnapshotPositionHandoff:
    def test_position_written_and_logged_after_successful_dump(self, md, monkeypatch, tmp_path, caplog):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1", "t2"])
        caplog.set_level(logging.INFO)
        code = run_main(md, monkeypatch, tmp_path, fake, Source(["t1", "t2"]))
        assert code == 0
        pos = read_position(dump_dir)
        assert pos["binlog_file"] == "binlog.000042"
        assert pos["binlog_position"] == 157
        assert pos["gtid_executed"] == ("3e11fa47-71ca-11e1-9e33-c80aa9429562:1-100,"
                                        "4f22fb58-71ca-11e1-9e33-c80aa9429563:1-5")
        assert pos["source_host"] == "db1"
        assert pos["database"] == DB
        assert pos["dump_started"] == "2026-10-01 00:00:00"
        assert pos["dump_finished"] == "2026-10-01 00:05:00"
        # keys read by `ch-mysql-resync rewind-sql --position-file`
        assert pos["file"] == "binlog.000042" and pos["pos"] == 157
        assert "binlog.000042:157" in caplog.text

    def test_handoff_file_accepted_by_resync_rewind_sql(self, md, monkeypatch, tmp_path, capsys):
        from ch_sink_tools.db_load import mysql_resync
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"])
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 0
        capsys.readouterr()
        # rewind-sql reads the offset table (key, direction, idle check); the connector is stopped at a later position
        ch_config = tmp_path / "client.xml"
        ch_config.write_text("<config/>")
        current = json.dumps({"ts_sec": 1, "file": "binlog.000050", "pos": 4, "row": 0, "server_id": 7, "event": 0},
                             separators=(",", ":"))
        monkeypatch.setattr(mysql_resync, "ClickHouse", lambda *a, **k: object())
        monkeypatch.setattr(mysql_resync, "read_offset_rows", lambda ch, table: [["connector1", current, "600"]])
        rc = mysql_resync.main(["rewind-sql", "--dump-base", dump_dir,
                                "--position-file", os.path.join(dump_dir, "snapshot_position.json"),
                                "--offset-table", "sink.replica_source_info", "--offset-key", "connector1",
                                "--ch-host", "ch.example", "--ch-config", str(ch_config), "--connector-stopped"])
        out = capsys.readouterr().out
        assert rc == 0
        assert '"file":"binlog.000042","pos":157' in out

    def test_missing_binlog_position_fails(self, md, monkeypatch, tmp_path, caplog):
        # account without REPLICATION CLIENT: MySQL Shell continues without the binlog keys
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], drop_keys=("binlogFile", "binlogPosition"))
        code = run_main(md, monkeypatch, tmp_path, fake, Source(["t1"]))
        assert code == 1
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))
        assert "binlogFile" in caplog.text

    def test_empty_binlog_file_fails(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], metadata=base_metadata(binlogFile=""))
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))

    def test_inconsistent_dump_fails(self, md, monkeypatch, tmp_path, caplog):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], metadata=base_metadata(consistent=False))
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))
        assert "consistent" in caplog.text

    def test_gtid_executed_inconsistent_fails(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], metadata=base_metadata(gtidExecutedInconsistent=True))
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1

    def test_missing_metadata_file_fails(self, md, monkeypatch, tmp_path, caplog):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], root_metadata=False)
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1
        assert "@.json" in caplog.text

    def test_incomplete_dump_fails(self, md, monkeypatch, tmp_path, caplog):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], done=False)
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1
        assert "@.done.json" in caplog.text

    def test_dry_run_skips_post_dump_checks(self, md, monkeypatch, tmp_path):
        # mysqlsh dryRun writes no dump files, so there is nothing to verify
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], write=False)
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"]), ["--dry_run"]) == 0
        assert "'dryRun': 1" in fake.statement
        assert not os.path.exists(dump_dir)

    def test_mysqlsh_failure_exits_one_without_handoff(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], rc="1")
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"])) == 1
        assert not os.path.exists(dump_dir)

    def test_schema_only_dump_writes_no_handoff(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], drop_keys=("binlogFile", "binlogPosition"))
        assert run_main(md, monkeypatch, tmp_path, fake, Source(["t1"]), ["--schema_only"]) == 0
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))


class TestLegacyNoConsistent:
    def test_no_consistent_dump_warns_and_writes_no_handoff(self, monkeypatch, tmp_path, caplog):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1"], metadata=base_metadata(consistent=False, gtidExecutedInconsistent=True))
        assert run_main(legacy_md, monkeypatch, tmp_path, fake, Source(["t1"]), ["--no_consistent"]) == 0
        assert "'consistent': 0" in fake.statement
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))
        assert "no usable snapshot position" in caplog.text


# ---------------------------------------------------------------------------------------------------------------
# D-13.03-2: whole tables by default, missing tables detected
# ---------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("md", COPIES)
class TestTableAndPartitionScope:
    def test_no_partition_list_without_partition_filter(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t_np", "t_p"])
        src = Source(["t_np", "t_p"], partitions={"t_p": ["p2025", "p2026"]})
        assert run_main(md, monkeypatch, tmp_path, fake, src) == 0
        assert "partitions" not in fake.statement
        assert "'t_np'" in fake.statement and "'t_p'" in fake.statement

    def test_partition_list_passed_with_partition_filter(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t_p"])
        src = Source(["t_p"], partitions={"t_p": ["p2025", "p2026"]})
        code = run_main(md, monkeypatch, tmp_path, fake, src,
                        ["--partitioned_tables_only", "--include_partitions_regex", "p2026"])
        assert code == 0
        assert "'partitions': {'appdb.t_p': ['p2026']}" in fake.statement

    def test_table_created_before_snapshot_but_missing_from_dump_fails(self, md, monkeypatch, tmp_path, caplog):
        # t_new appears after the pre-dump selection: it is in the source but not in the dump
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1", "t2"])
        src = Source(["t1", "t2"], ["t1", "t2", "t_new"])
        assert run_main(md, monkeypatch, tmp_path, fake, src) == 1
        assert "t_new" in caplog.text
        assert not os.path.exists(os.path.join(dump_dir, "snapshot_position.json"))

    def test_table_dropped_after_dump_is_not_an_error(self, md, monkeypatch, tmp_path):
        dump_dir = str(tmp_path / "dump")
        fake = FakeMysqlsh(dump_dir, ["t1", "t2"])
        src = Source(["t1", "t2"], ["t1"])
        assert run_main(md, monkeypatch, tmp_path, fake, src) == 0


# ---------------------------------------------------------------------------------------------------------------
# D-13.03-12: exit status is waited for
# ---------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("md", COPIES)
class TestRunCommandExitStatus:
    def test_child_closing_output_before_exit_reports_zero(self, md):
        rc = md.run_command("echo x; exec 1>&- 2>&-; sleep 0.3; exit 0")
        assert rc == "0"

    def test_child_closing_output_before_failing_reports_status(self, md):
        rc = md.run_command("exec 1>&- 2>&-; sleep 0.3; exit 3")
        assert rc == "3"


# ---------------------------------------------------------------------------------------------------------------
# D-13.03-13: no assert-based control flow (python -O)
# ---------------------------------------------------------------------------------------------------------------
_OPTIMIZED_DRIVER = r"""
import sys
import importlib
md = importlib.import_module(sys.argv[1])
scenario = sys.argv[2]
class R(list):
    def mappings(self):
        return self
    def fetchall(self):
        return list(self)
md.check_program_exists = lambda name: scenario != "no_mysqlsh"
md.resolve_credentials_from_config = lambda f: ("u", "p")
md.get_mysql_connection = lambda *a, **k: object()
md.get_tables_from_regex = lambda *a, **k: R([{"table_schema": "appdb", "table_name": "t1"}])
md.get_partitions_from_regex = lambda *a, **k: R([])
md.run_command = lambda cmd: "1"
# isolate the exit-status check: post-dump verification must not be what fails
md.verify_dump = lambda *a, **k: None
args = ["x", "--mysql_host", "db1", "--mysql_database", "appdb", "--dump_dir", sys.argv[3],
        "--defaults_file", "/nonexistent/my.cnf"]
if scenario == "password_without_user":
    args += ["--mysql_password", "pw"]
sys.argv = args
md.main()
"""


@pytest.mark.parametrize("module", ["db_dump.mysql_dumper", "ch_sink_tools.db_dump.mysql_dumper"])
@pytest.mark.parametrize("scenario", ["mysqlsh_fails", "no_mysqlsh", "password_without_user"])
class TestOptimizedInterpreter:
    def test_failure_exits_non_zero_under_python_O(self, module, scenario, tmp_path):
        env = dict(os.environ, PYTHONPATH=PYTHON_ROOT)
        p = subprocess.run([sys.executable, "-O", "-c", _OPTIMIZED_DRIVER, module, scenario,
                            str(tmp_path / "dump")],
                           cwd=PYTHON_ROOT, env=env, capture_output=True, text=True, timeout=60)
        assert p.returncode == 1, p.stdout + p.stderr
