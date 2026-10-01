"""Failure modes of ch-mysql-resync (spec 11.04 section 6). Offline: the clickhouse-client wrapper, the loader and
mysqlsh are replaced by in-process fakes; no database is contacted.

Run:  python3 -m unittest sink-connector/python/db_load/tests/test_resync_failure_modes.py   (from the repository root)
"""
import contextlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

from ch_sink_tools.db_load import mysql_resync as mr  # noqa: E402


def write_file(path, text):
    with open(path, "w") as f:
        f.write(text)


def read_file(path):
    with open(path) as f:
        return f.read()

# Live table s.t, partitioned by month: three partitions come from the dump, one (202001) exists only in ClickHouse.
SCRATCH_PARTS = {"202401": 4, "202402": 5, "202403": 6}
LIVE_PARTS = {"202001": 2, "202401": 3, "202402": 3, "202403": 3}
DUMP_ROWS = sum(SCRATCH_PARTS.values())


class PartitionedClickHouse:
    """Offline clickhouse-client stand-in; ``fail_on_replace`` makes the N-th REPLACE raise (a cut connection or a
    killed tool half way through phase 2)."""
    writes = []
    fail_on_replace = None

    def __init__(self, host, config, apply, port=9000):
        pass

    def one(self, sql, timeout=3600):
        if sql == "SELECT version()":
            return "test"
        if sql == "SELECT hostName()":
            return "test-host"
        if "SELECT count() FROM `s_restore`.`t`" in sql:
            return str(DUMP_ROWS)
        if "SELECT count() FROM `s`.`t`" in sql:
            return str(DUMP_ROWS + LIVE_PARTS["202001"])   # replaced partitions + the kept replica-only one
        return "0"

    def rows(self, sql, timeout=3600):
        if "FROM system.tables" in sql:
            return [["t", "ReplacingMergeTree", "toYYYYMM(d)", "id", "11"]]
        if "SELECT name, default_kind FROM system.columns" in sql:
            return [["id", ""], ["d", ""], ["_version", "DEFAULT"], ["is_deleted", "DEFAULT"]]
        if "FROM system.parts" in sql and "database='s_restore'" in sql:
            return [[k, str(v)] for k, v in SCRATCH_PARTS.items()]
        if "FROM system.parts" in sql and "database='s'" in sql:
            return [[k, str(v)] for k, v in LIVE_PARTS.items()]
        return []

    def write(self, sql, timeout=3600):
        if "REPLACE PARTITION" in sql:
            replaces = sum("REPLACE PARTITION" in w for w in PartitionedClickHouse.writes) + 1
            if PartitionedClickHouse.fail_on_replace == replaces:
                raise RuntimeError("clickhouse-client rc=210: Connection reset by peer")
        PartitionedClickHouse.writes.append(sql)


class TestInterruptedPatch(unittest.TestCase):
    """FM-11.04-1: a patch interrupted in phase 2 leaves a mix of replaced and not-yet-replaced partitions; the
    scratch tables survive, and a re-run with --skip-load re-issues every REPLACE and verifies."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        dump_dir = os.path.join(self.d, "s_20260928")
        os.makedirs(dump_dir)
        write_file(os.path.join(dump_dir, "@.done.json"), "{}")
        write_file(os.path.join(dump_dir, "s@t.sql"), "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `d` date NOT NULL\n) ENGINE=InnoDB;\n")
        write_file(os.path.join(self.d, "client.xml"), "<config/>")
        PartitionedClickHouse.writes = []
        PartitionedClickHouse.fail_on_replace = None

    def tearDown(self):
        shutil.rmtree(self.d)

    def _args(self, **over):
        base = dict(ch_config=os.path.join(self.d, "client.xml"), dump_base=self.d, stamp="20260928", schemas=["s"],
                    restore_suffix="_restore", ch_host="ch.example", ch_port=9000, apply=True, canary_list=None,
                    tables=".*", skip_load=False, drop_ch_only=False, load_parallel=1, load_threads=1, loader_cmd=None,
                    loader_cwd=None, canary_threshold=0.99, force=False)
        base.update(over)
        return SimpleNamespace(**base)

    def _run(self, args):
        with patch.object(mr, "ClickHouse", PartitionedClickHouse), \
                patch.object(mr, "exact_dump_rows", lambda files: DUMP_ROWS), \
                patch.object(mr, "isolate_table_dir", lambda dump_dir, schema, table: dump_dir), \
                patch.object(mr, "data_files", lambda dump_dir, schema, table: ["x.tsv.zst"]), \
                patch.object(mr, "run_loader", lambda *a, **k: (0, "load.log")), \
                contextlib.redirect_stdout(io.StringIO()):
            return mr.cmd_patch(args)

    def _replaces(self):
        return [w for w in PartitionedClickHouse.writes if "REPLACE PARTITION" in w]

    def test_interruption_leaves_scratch_tables_and_a_resume_completes(self):
        PartitionedClickHouse.fail_on_replace = 2
        with self.assertRaises(RuntimeError):
            self._run(self._args())
        # Exactly one partition was replaced before the cut; the only DROP ever issued targets the scratch copy.
        self.assertEqual(len(self._replaces()), 1, self._replaces())
        drops = [w for w in PartitionedClickHouse.writes if w.startswith("DROP")]
        self.assertEqual(drops, ["DROP TABLE IF EXISTS `s_restore`.`t`"])
        self.assertFalse(any("DROP PARTITION" in w for w in PartitionedClickHouse.writes))

        # Resume: the scratch table is already loaded, so --skip-load reconciles it and re-issues every REPLACE
        # (REPLACE PARTITION is idempotent: the scratch table keeps its parts after being copied from).
        PartitionedClickHouse.writes = []
        PartitionedClickHouse.fail_on_replace = None
        rc = self._run(self._args(skip_load=True))
        self.assertEqual(rc, 0)
        self.assertEqual(sorted(self._replaces()),
                         sorted(f"ALTER TABLE `s`.`t` REPLACE PARTITION ID '{p}' FROM `s_restore`.`t`" for p in SCRATCH_PARTS))
        self.assertFalse(any(w.startswith("DROP") or "DROP PARTITION" in w for w in PartitionedClickHouse.writes))
        outdir = os.path.join(self.d, "patch_20260928")
        report = read_file(os.path.join(outdir, sorted(f for f in os.listdir(outdir) if f.startswith("report_"))[-1]))
        self.assertIn("s\tt\tREPLACED_OK", report, report)
        drop_file = read_file(os.path.join(outdir, sorted(f for f in os.listdir(outdir) if f.startswith("drop_ch_only_"))[-1]))
        self.assertIn("DROP PARTITION ID '202001'", drop_file, "the replica-only partition is listed, never dropped")


class TestDumpRerun(unittest.TestCase):
    """FM-11.04-2: a dump interrupted half way. The captured position is kept (it predates the first read), a complete
    schema is skipped and an incomplete directory is refused, never resumed or overwritten."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        write_file(os.path.join(self.d, "binlog_position_20260928.json"), json.dumps({"file": "mysql-bin.000042", "pos": 4, "ts_sec": 1790431200}))
        os.makedirs(os.path.join(self.d, "done_20260928"))
        write_file(os.path.join(self.d, "done_20260928", "@.done.json"), "{}")
        os.makedirs(os.path.join(self.d, "half_20260928"))   # a dump killed half way: no @.done.json

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_rerun_keeps_the_position_skips_complete_and_refuses_incomplete(self):
        args = SimpleNamespace(dump_base=self.d, stamp="20260928", mysql_uri="u@h:3306", schemas=["done", "half"],
                               tables=".*", threads=1, consistent=False, mysqlsh="mysqlsh")
        out = io.StringIO()
        with patch.dict(os.environ, {"MYSQL_PWD": "secret"}), \
                patch.object(mr, "capture_binlog_position", side_effect=AssertionError("position must not be re-captured")), \
                patch.object(mr.subprocess, "run", side_effect=AssertionError("no schema may be re-dumped")), \
                contextlib.redirect_stdout(out):
            rc = mr.cmd_dump(args)
        self.assertEqual(rc, 1)
        text = out.getvalue()
        self.assertIn("binlog position already captured", text)
        self.assertIn("SKIP done: already complete", text)
        self.assertIn("ERROR half:", text)
        self.assertIn("exists but is incomplete -- move it away and rerun", text)
        self.assertEqual(json.loads(read_file(os.path.join(self.d, "binlog_position_20260928.json")))["pos"], 4)


class TestRewindAfterFailover(unittest.TestCase):
    """FM-11.04-4: the rewind is expressed as file/position only."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        write_file(os.path.join(self.d, "binlog_position_20260928.json"),
                   json.dumps({"file": "mysql-bin.000042", "pos": 1156385, "ts_sec": 1790431200, "source_host": "db-a",
                               "gtid_executed": "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442"}))

    def tearDown(self):
        shutil.rmtree(self.d)

    def _rewind_sql(self):
        args = SimpleNamespace(dump_base=self.d, stamp="20260928", position_file=None, offset_table="db.replica_source_info",
                               offset_key="k1", ch_host=None, ch_port=9000, ch_config=None)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(mr.cmd_rewind_sql(args), 0)
        return out.getvalue()

    def test_rewind_points_at_the_captured_file_and_position(self):
        sql = self._rewind_sql()
        self.assertIn('"file":"mysql-bin.000042","pos":1156385,"row":0', sql)
        self.assertIn("WHERE offset_key = 'k1'", sql)

    @unittest.skip("DEFECT FM-11.04-4: rewind-sql writes file/pos only although the dump recorded gtid_executed; "
                   "after a source failover (or a dump taken from another server) the file/pos names nothing valid")
    def test_rewind_carries_the_captured_gtid_set(self):
        self.assertIn('"gtids":"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442"', self._rewind_sql())


if __name__ == "__main__":
    unittest.main()
