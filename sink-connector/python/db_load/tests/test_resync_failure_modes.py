"""Failure modes of ch-mysql-resync (spec 11.04 section 6). Offline: the clickhouse-client wrapper, the loader and
mysqlsh are replaced by in-process fakes; no database is contacted.

Run:  python3 -m unittest sink-connector/python/db_load/tests/test_resync_failure_modes.py   (from the repository root)
"""
import contextlib
import io
import json
import os
import re
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
    comment = None          # the scratch table's comment, as last set by MODIFY COMMENT

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
        if "SELECT name, comment FROM system.tables" in sql:
            return [["t", PartitionedClickHouse.comment]] if PartitionedClickHouse.comment is not None else []
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
        if "MODIFY COMMENT " in sql:
            PartitionedClickHouse.comment = sql.split("MODIFY COMMENT ", 1)[1].strip("'")
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
        PartitionedClickHouse.comment = None

    def tearDown(self):
        shutil.rmtree(self.d)

    def _args(self, **over):
        base = dict(ch_config=os.path.join(self.d, "client.xml"), dump_base=self.d, stamp="20260928", schemas=["s"],
                    restore_suffix="_restore", ch_host="ch.example", ch_port=9000, apply=True, canary_list=None,
                    tables=".*", skip_load=False, drop_ch_only=False, load_parallel=1, load_threads=1, loader_cmd=None,
                    loader_cwd=None, canary_threshold=0.99, force=False, offset_table=None, offset_key=None,
                    skip_connector_position_check=True)
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


class OffsetClickHouse:
    """Offline stand-in for the offset-table read of rewind-sql: rows are [offset_key, offset_val, age in seconds]."""
    offsets = []
    queries = []

    def __init__(self, host, config, apply, port=9000):
        assert not apply, "rewind-sql never writes"

    def rows(self, sql, timeout=3600):
        OffsetClickHouse.queries.append(sql)
        if "FROM db.replica_source_info FINAL" in sql:
            return OffsetClickHouse.offsets
        raise AssertionError(f"unexpected query {sql}")

    def write(self, sql, timeout=3600):
        raise AssertionError("rewind-sql never writes")


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
        write_file(os.path.join(self.d, "client.xml"), "<config/>")
        args = SimpleNamespace(dump_base=self.d, stamp="20260928", position_file=None, offset_table="db.replica_source_info",
                               offset_key="k1", ch_host="ch.example", ch_port=9000, ch_config=os.path.join(self.d, "client.xml"),
                               connector_stopped=True, connector_idle_seconds=60, allow_forward_rewind=False)
        OffsetClickHouse.offsets = [["k1", '{"file":"mysql-bin.000043","pos":4,"server_id":7}', "600"]]
        out = io.StringIO()
        with patch.object(mr, "ClickHouse", OffsetClickHouse), contextlib.redirect_stdout(out):
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


# ----------------------------------------------------------------------------------------------------------------------
# Spec 13.08 S1 guards (D-13.08-3 .. -11). One scripted fake drives every case: unpartitioned live tables in schema `s`,
# scratch database `s_restore`, offset table `db.replica_source_info`, dump position mysql-bin.000042:4.
# ----------------------------------------------------------------------------------------------------------------------
PLAIN_COLUMNS = [("id", ""), ("v", ""), ("_version", ""), ("is_deleted", "")]
LEGACY_COLUMNS = [("id", ""), ("v", ""), ("_sign", ""), ("_version", "")]
SCD2_COLUMNS = [("id", ""), ("v", ""), ("_valid_from", "DEFAULT"), ("_valid_to", "DEFAULT"), ("_operation", ""),
                ("is_deleted", ""), ("_version", "")]
POSITION = {"file": "mysql-bin.000042", "pos": 4, "ts_sec": 1790431200}


def offset_row(key, binlog_file, pos, age="600"):
    return [key, json.dumps({"ts_sec": 1, "file": binlog_file, "pos": pos, "row": 0, "server_id": 7, "event": 0},
                            separators=(",", ":")), age]


class ScriptedClickHouse:
    """``tables`` maps name -> {columns, rows, canary (same, n), comment (scratch comment or None), unsigned}."""
    tables = {}
    offsets = []
    writes = []

    def __init__(self, host, config, apply, port=9000):
        pass

    def one(self, sql, timeout=3600):
        if sql in ("SELECT version()", "SELECT hostName()"):
            return "test"
        m = re.search(r"SELECT countIf\(`_sign` != 1\) FROM `s_restore`\.`(\w+)`", sql)
        if m:
            return str(self.tables[m.group(1)].get("unsigned", 0))
        m = re.search(r"SELECT count\(\) FROM `s(?:_restore)?`\.`(\w+)`", sql)
        if m:
            return str(self.tables[m.group(1)]["rows"])
        return "0"

    def rows(self, sql, timeout=3600):
        if "SELECT name, comment FROM system.tables" in sql:
            return [[t, d["comment"]] for t, d in self.tables.items() if d.get("comment") is not None]
        if "FROM system.tables" in sql:
            return [[t, "ReplacingMergeTree", "", "id", str(d["rows"])] for t, d in self.tables.items()]
        m = re.search(r"SELECT name, default_kind FROM system.columns WHERE database='s' AND table='(\w+)'", sql)
        if m:
            return [[c, k] for c, k in self.tables[m.group(1)]["columns"]]
        m = re.search(r"SELECT name FROM system.columns WHERE database='s' AND table='(\w+)'", sql)
        if m:
            return [[c] for c, _ in self.tables[m.group(1)]["columns"]]
        if "countIf(r.h = l.h)" in sql:
            same, n = self.tables[re.search(r"FROM `s_restore`\.`(\w+)`", sql).group(1)]["canary"]
            return [[str(same), str(n)]]
        if "FROM system.parts" in sql:
            n = self.tables[re.search(r"table='(\w+)'", sql).group(1)]["rows"]
            return [["all", str(n)]] if n else []
        if "FROM db.replica_source_info FINAL" in sql:
            return self.offsets
        return []

    def write(self, sql, timeout=3600):
        ScriptedClickHouse.writes.append(sql)


class GuardCase(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        write_file(os.path.join(self.d, "client.xml"), "<config/>")
        write_file(os.path.join(self.d, "binlog_position_20260928.json"), json.dumps(POSITION))
        ScriptedClickHouse.tables, ScriptedClickHouse.offsets, ScriptedClickHouse.writes = {}, [], []
        self.out = ""

    def tearDown(self):
        shutil.rmtree(self.d)

    def table(self, name, columns=PLAIN_COLUMNS, rows=3, canary=(3, 3), comment=None, unsigned=0, schema="s"):
        ScriptedClickHouse.tables[name] = dict(columns=columns, rows=rows, canary=canary, comment=comment, unsigned=unsigned)
        dump_dir = os.path.join(self.d, f"{schema}_20260928")
        os.makedirs(dump_dir, exist_ok=True)
        write_file(os.path.join(dump_dir, "@.done.json"), "{}")
        write_file(os.path.join(dump_dir, f"{schema}@{name}.sql"),
                   f"CREATE TABLE `{name}` (\n  `id` int NOT NULL,\n  `v` int NOT NULL\n) ENGINE=InnoDB;\n")

    def args(self, **over):
        base = dict(ch_config=os.path.join(self.d, "client.xml"), dump_base=self.d, stamp="20260928", schemas=["s"],
                    restore_suffix="_restore", ch_host="ch.example", ch_port=9000, apply=True, canary_list=None,
                    tables=".*", skip_load=False, drop_ch_only=False, load_parallel=1, load_threads=1, loader_cmd=None,
                    loader_cwd=None, canary_threshold=0.99, force=False, offset_table="db.replica_source_info",
                    offset_key="k1", skip_connector_position_check=False)
        base.update(over)
        return SimpleNamespace(**base)

    def patch_run(self, args):
        out = io.StringIO()
        try:
            with patch.object(mr, "ClickHouse", ScriptedClickHouse), \
                    patch.object(mr, "exact_dump_rows", lambda files: ScriptedClickHouse.tables[files[0].split(".")[0]]["rows"]), \
                    patch.object(mr, "isolate_table_dir", lambda dump_dir, schema, table: dump_dir), \
                    patch.object(mr, "data_files", lambda dump_dir, schema, table: [f"{table}.tsv.zst"]), \
                    patch.object(mr, "run_loader", lambda *a, **k: (0, "load.log")), \
                    contextlib.redirect_stdout(out):
                return mr.cmd_patch(args)
        finally:
            self.out = out.getvalue()

    def report(self):
        outdir = os.path.join(self.d, "patch_20260928")
        return read_file(os.path.join(outdir, sorted(f for f in os.listdir(outdir) if f.startswith("report_"))[-1]))

    def replaces(self):
        return [w for w in ScriptedClickHouse.writes if "REPLACE PARTITION" in w]


class TestLegacySignTable(GuardCase):
    """D-13.08-3: the loader never writes `_sign`; on a legacy-engine table the reloaded rows must still carry the
    connector's live-row value 1, or every `_sign > 0` reader loses them."""

    def test_reloaded_rows_of_a_sign_table_get_sign_1(self):
        self.table("legacy", columns=LEGACY_COLUMNS)
        self.table("modern")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 0, self.out)
        w = ScriptedClickHouse.writes
        alter = "ALTER TABLE `s_restore`.`legacy` MODIFY COLUMN `_sign` DEFAULT 1"
        self.assertIn(alter, w)
        self.assertLess(w.index("CREATE TABLE `s_restore`.`legacy` AS `s`.`legacy`"), w.index(alter))
        self.assertLess(w.index(alter), w.index("ALTER TABLE `s`.`legacy` REPLACE PARTITION ID 'all' FROM `s_restore`.`legacy`"))
        self.assertFalse(any("`modern` MODIFY COLUMN" in x for x in w), "only tables with the connector's _sign column")
        self.assertIn("s\tlegacy\tREPLACED_OK", self.report())

    def test_scratch_rows_whose_sign_is_not_1_are_never_replaced(self):
        self.table("legacy", columns=LEGACY_COLUMNS, unsigned=3)
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 1)
        self.assertEqual(self.replaces(), [])
        self.assertIn("s\tlegacy\tSIGN_MISMATCH", self.report())


class TestPerTableCanary(GuardCase):
    """D-13.08-4: a large matching canary table must not dilute a small one whose rows all differ."""

    def test_each_canary_table_must_pass_on_its_own(self):
        self.table("big", rows=1000, canary=(1000, 1000))
        self.table("small", rows=5, canary=(0, 5))
        write_file(os.path.join(self.d, "canary.txt"), "s.big\ns.small\n")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        rc = self.patch_run(self.args(canary_list=os.path.join(self.d, "canary.txt")))
        self.assertEqual(rc, 1, "pooled 1000/1005 = 0.995 passes 0.99; the small table alone is 0/5")
        self.assertEqual(self.replaces(), [])
        self.assertIn("s\tsmall\tCANARY_FAILED", self.report())
        self.assertIn("s\tbig\tCANARY_FAILED", self.report())
        self.assertIn("s.small 0/5", self.out)


class TestConnectorPositionGate(GuardCase):
    """D-13.08-5: before any REPLACE, the connector's durable offset must be at or past the dump position."""

    def test_connector_behind_the_dump_position_refuses_every_replace(self):
        self.table("t")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000041", 999999)]
        self.assertEqual(self.patch_run(self.args()), 1)
        self.assertEqual(self.replaces(), [])
        self.assertIn("s\tt\tCONNECTOR_BEHIND", self.report())

    def test_connector_at_or_past_the_dump_position_allows_the_replace(self):
        for binlog_file, pos in (("mysql-bin.000042", 4), ("mysql-bin.000043", 1)):
            with self.subTest(offset=f"{binlog_file}:{pos}"):
                self.table("t")
                ScriptedClickHouse.writes = []
                ScriptedClickHouse.offsets = [offset_row("k1", binlog_file, pos)]
                self.assertEqual(self.patch_run(self.args()), 0, self.out)
                self.assertEqual(len(self.replaces()), 1)

    def test_apply_without_offset_table_or_with_unknown_key_refuses(self):
        self.table("t")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        for over in (dict(offset_table=None), dict(offset_key="typo")):
            with self.subTest(**over):
                ScriptedClickHouse.writes = []
                self.assertEqual(self.patch_run(self.args(**over)), 1)
                self.assertEqual(self.replaces(), [])
                self.assertIn("s\tt\tCONNECTOR_UNVERIFIED", self.report())

    def test_explicit_override_skips_the_check_with_a_warning(self):
        self.table("t")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000041", 4)]
        self.assertEqual(self.patch_run(self.args(offset_table=None, skip_connector_position_check=True)), 0, self.out)
        self.assertEqual(len(self.replaces()), 1)
        self.assertIn("WARNING: --skip-connector-position-check", self.out)


class TestSkipLoadStampMarker(GuardCase):
    """D-13.08-8: --skip-load replaces only from a scratch table marked as loaded from this dump."""

    def test_load_marks_the_scratch_table_with_stamp_and_dump_rows(self):
        self.table("t", rows=3)
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 0, self.out)
        self.assertIn("ALTER TABLE `s_restore`.`t` MODIFY COMMENT 'ch-mysql-resync scratch source=s.t stamp=20260928 dump_rows=3'",
                      ScriptedClickHouse.writes)

    def test_skip_load_refuses_a_scratch_table_from_another_dump(self):
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        for comment in (mr.scratch_marker("s", "t", "20260901", 3),   # same row count, other dump
                        mr.scratch_marker("s", "t", "20260928"),      # load never reconciled
                        "", None):                                    # unmarked, or absent
            with self.subTest(comment=comment):
                self.table("t", rows=3, comment=comment)
                ScriptedClickHouse.writes = []
                self.assertEqual(self.patch_run(self.args(skip_load=True)), 1)
                self.assertEqual(self.replaces(), [])
                self.assertIn("s\tt\tSCRATCH_STAMP_MISMATCH", self.report())

    def test_skip_load_replaces_from_a_scratch_table_of_this_dump(self):
        self.table("t", rows=3, comment=mr.scratch_marker("s", "t", "20260928", 3))
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args(skip_load=True)), 0, self.out)
        self.assertEqual(len(self.replaces()), 1)


class TestScd2Refused(GuardCase):
    """D-13.08-10 (FM-11.04-9): a replication-history (SCD2) table is refused before anything is written for it."""

    def test_table_with_history_columns_is_refused(self):
        self.table("hist", columns=SCD2_COLUMNS)
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 1)
        self.assertFalse(any("`hist`" in w for w in ScriptedClickHouse.writes), ScriptedClickHouse.writes)
        self.assertIn("s\thist\tSCD2_REFUSED\t_valid_from,_valid_to", self.report())


class TestRestoreSuffixGuard(GuardCase):
    """D-13.08-11: an empty or colliding --restore-suffix must never let the scratch recreation hit a live table."""

    def test_empty_or_colliding_suffix_is_refused_before_any_statement(self):
        self.table("t")
        self.table("t", schema="s_restore")
        for over in (dict(restore_suffix=""), dict(schemas=["s", "s_restore"])):
            with self.subTest(**over):
                ScriptedClickHouse.writes = []
                with self.assertRaises(SystemExit):
                    self.patch_run(self.args(**over))
                self.assertEqual(ScriptedClickHouse.writes, [])

    def test_existing_unmarked_table_in_the_scratch_database_is_not_recreated(self):
        self.table("t", comment="a live table that happens to live in s_restore")
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 1)
        self.assertFalse(any(w.startswith(("DROP", "CREATE TABLE")) for w in ScriptedClickHouse.writes), ScriptedClickHouse.writes)
        self.assertIn("s\tt\tSCRATCH_NOT_OURS", self.report())

    def test_marked_scratch_table_is_recreated(self):
        self.table("t", comment=mr.scratch_marker("s", "t", "20260901", 3))
        ScriptedClickHouse.offsets = [offset_row("k1", "mysql-bin.000042", 4)]
        self.assertEqual(self.patch_run(self.args()), 0, self.out)
        self.assertIn("DROP TABLE IF EXISTS `s_restore`.`t`", ScriptedClickHouse.writes)


class TestRewindGuards(unittest.TestCase):
    """D-13.08-6 (direction), D-13.08-7 (unknown key), D-13.08-9 / FM-11.04-6 (running connector)."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        write_file(os.path.join(self.d, "client.xml"), "<config/>")
        write_file(os.path.join(self.d, "binlog_position_20260928.json"),
                   json.dumps({"file": "mysql-bin.000350", "pos": 1000, "ts_sec": 1790431200}))
        OffsetClickHouse.queries = []

    def tearDown(self):
        shutil.rmtree(self.d)

    def run_rewind(self, offsets, **over):
        base = dict(dump_base=self.d, stamp="20260928", position_file=None, offset_table="db.replica_source_info",
                    offset_key="k1", ch_host="ch.example", ch_port=9000, ch_config=os.path.join(self.d, "client.xml"),
                    connector_stopped=True, connector_idle_seconds=60, allow_forward_rewind=False)
        base.update(over)
        OffsetClickHouse.offsets = offsets
        out, err = io.StringIO(), io.StringIO()
        with patch.object(mr, "ClickHouse", OffsetClickHouse), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = mr.cmd_rewind_sql(SimpleNamespace(**base))
        return rc, out.getvalue(), err.getvalue()

    def assert_refused(self, offsets, needle, **over):
        out = io.StringIO()
        with self.assertRaises(SystemExit) as cm, contextlib.redirect_stdout(out):
            self.run_rewind(offsets, **over)
        self.assertIn(needle, str(cm.exception.code))
        self.assertNotIn("INSERT INTO", out.getvalue())

    def test_forward_move_is_refused(self):
        self.assert_refused([offset_row("k1", "mysql-bin.000300", 4)], "FORWARD")

    def test_forward_move_with_override_is_emitted_with_a_warning(self):
        rc, out, err = self.run_rewind([offset_row("k1", "mysql-bin.000300", 4)], allow_forward_rewind=True)
        self.assertEqual(rc, 0)
        self.assertIn('"file":"mysql-bin.000350","pos":1000', out)
        self.assertIn("WARNING: --allow-forward-rewind", err)

    def test_backward_or_equal_move_is_emitted(self):
        for binlog_file, pos in (("mysql-bin.000350", 2000), ("mysql-bin.000350", 1000), ("mysql-bin.000351", 4)):
            with self.subTest(current=f"{binlog_file}:{pos}"):
                rc, out, _ = self.run_rewind([offset_row("k1", binlog_file, pos)])
                self.assertEqual(rc, 0)
                self.assertIn("WHERE offset_key = 'k1';", out)

    def test_unknown_key_or_empty_table_is_refused_with_the_keys_found(self):
        self.assert_refused([offset_row("k1", "mysql-bin.000351", 4), offset_row("k2", "mysql-bin.000351", 4)],
                            "keys present: ['k1', 'k2']", offset_key="typo")
        self.assert_refused([], "keys present: []")

    def test_unreadable_offset_table_is_refused(self):
        self.assert_refused([offset_row("k1", "mysql-bin.000351", 4)], "--ch-config are required", ch_config=None)
        self.assert_refused([offset_row("k1", "mysql-bin.000351", 4)], "--ch-config are required",
                            ch_config=os.path.join(self.d, "missing.xml"))

    def test_running_connector_is_refused(self):
        self.assert_refused([offset_row("k1", "mysql-bin.000351", 4, age="5")], "looks RUNNING")
        self.assert_refused([offset_row("k1", "mysql-bin.000351", 4)], "--connector-stopped", connector_stopped=False)
        rc, _, _ = self.run_rewind([offset_row("k1", "mysql-bin.000351", 4, age="5")], connector_idle_seconds=0)
        self.assertEqual(rc, 0, "--connector-idle-seconds 0 disables the age check (the attestation is still required)")


if __name__ == "__main__":
    unittest.main()
