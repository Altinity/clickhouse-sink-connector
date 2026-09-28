"""Offline unit tests for ch-mysql-resync (ch_sink_tools/db_load/mysql_resync.py). No database is contacted: the
tests cover the pure planning functions the procedure is made of, plus the dump-directory helpers on a temp dir.

Run:  python3 -m unittest sink-connector/python/db_load/tests/test_mysql_resync.py   (from the repository root)
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))  # sink-connector/python

from ch_sink_tools.db_load import mysql_resync as mr  # noqa: E402

DDL = """/*!40101 SET @saved_cs_client = @@character_set_client */;
CREATE TABLE IF NOT EXISTS `user` (
  `user_id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `full_name` varchar(255) DEFAULT NULL,
  `amount` decimal(30,10) NOT NULL DEFAULT '0.0000000000',
  `created` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `flags` tinyint(1) DEFAULT NULL,
  `big` bigint unsigned NOT NULL,
  `derived` int GENERATED ALWAYS AS (`user_id` + 1) VIRTUAL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `username` (`username`),
  KEY `idx_created` (`created`),
  CONSTRAINT `fk` FOREIGN KEY (`big`) REFERENCES `t` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=544 DEFAULT CHARSET=utf8mb4;
"""


class TestParseMysqlDdl(unittest.TestCase):
    def test_columns_in_order_with_nullability_and_generated(self):
        cols = mr.parse_mysql_ddl(DDL)
        self.assertEqual([c[0] for c in cols], ["user_id", "username", "full_name", "amount", "created", "flags", "big", "derived"])
        by = {c[0]: c for c in cols}
        self.assertEqual(by["user_id"][1:], ("int", False, False))
        self.assertEqual(by["full_name"][1:], ("varchar(255)", True, False))
        self.assertEqual(by["amount"][1], "decimal(30,10)")
        self.assertEqual(by["big"][1], "bigint unsigned")
        self.assertTrue(by["derived"][3], "GENERATED ALWAYS column must be flagged: MySQL Shell does not dump it")

    def test_index_and_constraint_lines_are_not_columns(self):
        names = [c[0] for c in mr.parse_mysql_ddl(DDL)]
        self.assertNotIn("username`", names)
        self.assertNotIn("fk", names)
        self.assertNotIn("idx_created", names)


class TestTypeSuggestion(unittest.TestCase):
    def test_common_types(self):
        self.assertEqual(mr.mysql_to_ch("int", False), "Int32")
        self.assertEqual(mr.mysql_to_ch("int unsigned", True), "Nullable(UInt32)")
        self.assertEqual(mr.mysql_to_ch("bigint unsigned", False), "UInt64")
        self.assertEqual(mr.mysql_to_ch("tinyint(1)", False), "Int8")
        self.assertEqual(mr.mysql_to_ch("decimal(30,10)", False), "Decimal(30,10)")
        self.assertEqual(mr.mysql_to_ch("datetime(6)", True), "Nullable(DateTime64(6))")
        self.assertEqual(mr.mysql_to_ch("datetime", False), "DateTime64(3)")
        self.assertEqual(mr.mysql_to_ch("date", True), "Nullable(Date32)")
        self.assertEqual(mr.mysql_to_ch("varchar(45)", False), "String")
        self.assertEqual(mr.mysql_to_ch("json", True), "Nullable(String)")


class TestDrift(unittest.TestCase):
    def setUp(self):
        self.mcols = mr.parse_mysql_ddl(DDL)

    def test_no_drift_when_every_writable_source_column_exists(self):
        ch = {"user_id": "", "username": "", "full_name": "", "amount": "", "created": "", "flags": "", "big": "",
              "_version": "DEFAULT", "is_deleted": "DEFAULT"}
        mysql_only, ch_only = mr.column_drift(self.mcols, ch)
        self.assertEqual(mysql_only, [])
        self.assertEqual(ch_only, [], "virtual columns are not drift")

    def test_source_only_column_is_reported_and_generated_is_ignored(self):
        ch = {"user_id": "", "username": "", "full_name": "", "amount": "", "created": "", "big": "", "_version": "DEFAULT", "is_deleted": "DEFAULT"}
        mysql_only, _ = mr.column_drift(self.mcols, ch)
        self.assertEqual([c[0] for c in mysql_only], ["flags"], "derived (GENERATED) must not count as missing")

    def test_materialized_target_column_does_not_satisfy_a_source_column(self):
        ch = {"user_id": "", "username": "", "full_name": "", "amount": "MATERIALIZED", "created": "", "flags": "", "big": ""}
        mysql_only, _ = mr.column_drift(self.mcols, ch)
        self.assertEqual([c[0] for c in mysql_only], ["amount"], "a MATERIALIZED column cannot receive the source value (AGENTS.md column-kind rules)")

    def test_replica_only_column_is_listed_not_fatal(self):
        ch = {"user_id": "", "username": "", "full_name": "", "amount": "", "created": "", "flags": "", "big": "", "legacy_col": "", "_version": "DEFAULT"}
        mysql_only, ch_only = mr.column_drift(self.mcols, ch)
        self.assertEqual(mysql_only, [])
        self.assertEqual(ch_only, ["legacy_col"])

    def test_drift_ddl_keeps_source_position(self):
        ch = {"user_id": "", "username": "", "full_name": "", "amount": "", "created": "", "big": "", "_version": "DEFAULT", "is_deleted": "DEFAULT"}
        mysql_only, _ = mr.column_drift(self.mcols, ch)
        stmts = mr.drift_ddl("db", "user", self.mcols, mysql_only)
        self.assertEqual(len(stmts), 1)
        self.assertIn("ADD COLUMN IF NOT EXISTS `flags` Nullable(Int8) AFTER `created`", stmts[0])


class TestPlanReplace(unittest.TestCase):
    def test_unpartitioned_table_is_one_replace_of_the_all_partition(self):
        stmts, ch_only = mr.plan_replace("db", "t", "db_restore", False, {"all": 10}, {"all": 12})
        self.assertEqual(stmts, ["ALTER TABLE `db`.`t` REPLACE PARTITION ID 'all' FROM `db_restore`.`t`"])
        self.assertEqual(ch_only, [])

    def test_unpartitioned_empty_source_empties_the_replica(self):
        stmts, ch_only = mr.plan_replace("db", "t", "db_restore", False, {}, {"all": 5})
        self.assertEqual(len(stmts), 1, "an empty MySQL table means an empty replica: REPLACE from the empty scratch table")
        self.assertEqual(ch_only, [])

    def test_unpartitioned_both_empty_is_a_noop(self):
        self.assertEqual(mr.plan_replace("db", "t", "db_restore", False, {}, {}), ([], []))

    def test_partitioned_replaces_dump_partitions_and_lists_replica_only_ones(self):
        stmts, ch_only = mr.plan_replace("db", "t", "db_restore", True, {"202609": 3, "202608": 2}, {"202609": 9, "202607": 4, "202601": 1})
        self.assertEqual(stmts, ["ALTER TABLE `db`.`t` REPLACE PARTITION ID '202608' FROM `db_restore`.`t`",
                                 "ALTER TABLE `db`.`t` REPLACE PARTITION ID '202609' FROM `db_restore`.`t`"])
        self.assertEqual(ch_only, ["202601", "202607"], "never dropped by the plan: listed for --drop-ch-only")

    def test_partitioned_empty_source_replaces_nothing_and_lists_everything(self):
        stmts, ch_only = mr.plan_replace("db", "t", "db_restore", True, {}, {"202609": 9})
        self.assertEqual(stmts, [])
        self.assertEqual(ch_only, ["202609"])


class TestSortingKey(unittest.TestCase):
    def test_plain_columns(self):
        self.assertEqual(mr.plain_identifiers("saga_id, created"), ["saga_id", "created"])

    def test_expression_key_disables_the_hash_join(self):
        self.assertIsNone(mr.plain_identifiers("toDate(ts), id"))
        self.assertIsNone(mr.plain_identifiers(""))


class TestRewind(unittest.TestCase):
    def test_offset_keeps_server_id_and_points_at_the_captured_position(self):
        cur = '{"ts_sec":1790581120,"file":"binary.000350","pos":110633288,"row":5,"server_id":220,"event":27}'
        new = json.loads(mr.rewind_offset_json(cur, "binary.000349", 1032888023, 1790580000))
        self.assertEqual(new, {"ts_sec": 1790580000, "file": "binary.000349", "pos": 1032888023, "row": 0, "server_id": 220, "event": 0})

    def test_offset_without_current_row(self):
        new = json.loads(mr.rewind_offset_json("", "binary.000001", 4, 0))
        self.assertEqual(new["server_id"], 0)
        self.assertEqual(new["pos"], 4)

    def test_sql_inserts_a_newer_row_for_the_same_key_only(self):
        key = '["source--staging",{"server":"embeddedconnector"}]'
        sql = mr.rewind_offset_sql("db.offsets", key, '{"file":"f","pos":4}')
        self.assertIn("INSERT INTO db.offsets (id, offset_key, offset_val, record_insert_ts, record_insert_seq)", sql)
        self.assertIn("record_insert_seq + 1", sql)
        self.assertIn("FROM db.offsets FINAL", sql)
        self.assertIn("WHERE offset_key = '[\"source--staging\",{\"server\":\"embeddedconnector\"}]'", sql,
                      "the offset store is shared by every connector: an unscoped INSERT ... SELECT would rewind all of them")

    def test_sql_refuses_an_unscoped_rewind(self):
        with self.assertRaises(ValueError):
            mr.rewind_offset_sql("db.offsets", "", '{"file":"f","pos":4}')

    def test_select_offset_row_requires_an_unambiguous_key(self):
        rows = [["k1", '{"server_id":1}'], ["k2", '{"server_id":2}']]
        self.assertEqual(mr.select_offset_row(rows, "k2"), ("k2", '{"server_id":2}'))
        with self.assertRaises(ValueError):
            mr.select_offset_row(rows, None)          # two connectors, no key given -> never guess
        with self.assertRaises(ValueError):
            mr.select_offset_row(rows, "k3")          # unknown key
        self.assertEqual(mr.select_offset_row([["only", "{}"]], None), ("only", "{}"))


class FakeClickHouse:
    """Offline stand-in for the clickhouse-client wrapper: one live table `s.t` (unpartitioned, sorting key id) whose
    scratch copy `s_restore.t` holds 1 row and hash-matches NONE of the live rows (canary 0/10)."""
    writes = []

    def __init__(self, host, config, apply, port=9000):
        pass

    def one(self, sql, timeout=3600):
        if sql == "SELECT version()":
            return "test"
        if sql == "SELECT hostName()":
            return "test-host"
        if "SELECT count() FROM `s_restore`.`t`" in sql:
            return "1"
        if "SELECT count() FROM `s`.`t`" in sql:
            return "1"  # live count after the REPLACE == dump rows
        return "0"

    def rows(self, sql, timeout=3600):
        if "FROM system.tables" in sql:
            return [["t", "ReplacingMergeTree", "", "id", "10"]]
        if "SELECT name, default_kind FROM system.columns" in sql:
            return [["id", ""], ["v", ""], ["_version", "DEFAULT"], ["is_deleted", "DEFAULT"]]
        if "SELECT name FROM system.columns" in sql:
            return [["id"], ["v"], ["_version"], ["is_deleted"]]
        if "countIf(r.h = l.h)" in sql:
            return [["0", "10"]]
        if "FROM system.parts" in sql:
            return [["all", "10"]]
        return []

    def write(self, sql, timeout=3600):
        FakeClickHouse.writes.append(sql)


class TestCanaryGate(unittest.TestCase):
    """A failed canary must stop the run before ANY REPLACE, mark the tables CANARY_FAILED and exit non-zero."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        dump_dir = os.path.join(self.d, "s_20260928")
        os.makedirs(dump_dir)
        open(os.path.join(dump_dir, "@.done.json"), "w").write("{}")
        open(os.path.join(dump_dir, "s@t.sql"), "w").write("CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `v` int NOT NULL\n) ENGINE=InnoDB;\n")
        open(os.path.join(self.d, "client.xml"), "w").write("<config/>")
        open(os.path.join(self.d, "canary.txt"), "w").write("s.t\n")
        FakeClickHouse.writes = []

    def tearDown(self):
        shutil.rmtree(self.d)

    def _args(self, **over):
        from types import SimpleNamespace
        base = dict(ch_config=os.path.join(self.d, "client.xml"), dump_base=self.d, stamp="20260928", schemas=["s"],
                    restore_suffix="_restore", ch_host="ch.example", ch_port=9000, apply=True, canary_list=os.path.join(self.d, "canary.txt"),
                    tables=".*", skip_load=True, drop_ch_only=False, load_parallel=1, load_threads=1, loader_cmd=None, loader_cwd=None,
                    canary_threshold=0.99, force=False)
        base.update(over)
        return SimpleNamespace(**base)

    def _run(self, args):
        from unittest.mock import patch
        with patch.object(mr, "ClickHouse", FakeClickHouse), patch.object(mr, "exact_dump_rows", lambda files: 1), \
                patch.object(mr, "isolate_table_dir", lambda dump_dir, schema, table: dump_dir), \
                patch.object(mr, "data_files", lambda dump_dir, schema, table: ["x.tsv.zst"]):
            return mr.cmd_patch(args)

    def test_failed_canary_is_nonzero_and_replaces_nothing(self):
        rc = self._run(self._args())
        self.assertNotEqual(rc, 0)
        self.assertFalse(any("REPLACE PARTITION" in w for w in FakeClickHouse.writes), FakeClickHouse.writes)
        outdir = os.path.join(self.d, "patch_20260928")
        report = open(os.path.join(outdir, sorted(f for f in os.listdir(outdir) if f.startswith("report_"))[-1])).read()
        self.assertIn("s\tt\tCANARY_FAILED", report, report)

    def test_force_overrides_the_canary_and_replaces(self):
        rc = self._run(self._args(force=True))
        self.assertEqual(rc, 0)
        self.assertTrue(any("REPLACE PARTITION ID 'all'" in w for w in FakeClickHouse.writes), FakeClickHouse.writes)

    def test_zero_joined_canary_rows_fail_closed(self):
        from unittest.mock import patch
        rows = FakeClickHouse.rows

        def zero_join(self_, sql, timeout=3600):
            if "countIf(r.h = l.h)" in sql:
                return [["0", "0"]]  # scratch has rows (count 1) but no sorting key overlaps the live table
            return rows(self_, sql, timeout)
        with patch.object(FakeClickHouse, "rows", zero_join):
            rc = self._run(self._args())
        self.assertNotEqual(rc, 0, "a zero canary denominator on a non-empty scratch table is a mismatch, not missing evidence")
        self.assertFalse(any("REPLACE PARTITION" in w for w in FakeClickHouse.writes), FakeClickHouse.writes)

    def test_apply_fails_when_a_selected_table_is_skipped_for_schema_drift(self):
        open(os.path.join(self.d, "s_20260928", "s@t.sql"), "w").write(
            "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `missing_in_ch` int NOT NULL\n) ENGINE=InnoDB;\n")
        rc = self._run(self._args(canary_list=None, force=True))
        self.assertNotEqual(rc, 0, "a selected table left unrepaired (SCHEMA_DRIFT) is a failure in apply mode: no rewind advice")
        self.assertFalse(any("REPLACE PARTITION" in w for w in FakeClickHouse.writes), FakeClickHouse.writes)


class TestDdlLiterals(unittest.TestCase):
    def test_default_literal_containing_not_null_is_still_nullable(self):
        cols = mr.parse_mysql_ddl("CREATE TABLE `t` (\n  `c` varchar(20) DEFAULT 'this is NOT NULL',\n  `d` varchar(20) NOT NULL\n) ENGINE=InnoDB;\n")
        self.assertEqual([(c[0], c[2]) for c in cols], [("c", True), ("d", False)])


class TestDumpDirectory(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        for name in ("s@t.sql", "s@t.json", "s@t@@0.tsv.zst", "s@t@@1.tsv.zst", "s@t_other.sql", "s@t_other@@0.tsv.zst"):
            open(os.path.join(self.d, name), "w").write("x")

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_table_list_and_data_files_do_not_bleed_across_prefixes(self):
        self.assertEqual(mr.dump_tables(self.d, "s"), ["t", "t_other"])
        self.assertEqual([os.path.basename(f) for f in mr.data_files(self.d, "s", "t")], ["s@t@@0.tsv.zst", "s@t@@1.tsv.zst"])

    def test_isolated_dir_uses_hard_links(self):
        iso = mr.isolate_table_dir(self.d, "s", "t")
        names = sorted(os.listdir(iso))
        self.assertEqual(names, ["s@t.json", "s@t.sql", "s@t@@0.tsv.zst", "s@t@@1.tsv.zst"])
        for n in names:
            p = os.path.join(iso, n)
            self.assertFalse(os.path.islink(p), "zstd refuses symlinks; the isolated dir must hold hard links")
            self.assertEqual(os.stat(p).st_nlink, 2)

    @unittest.skipUnless(shutil.which("zstd"), "zstd not installed")
    def test_exact_dump_rows_counts_newline_terminated_rows(self):
        raw = os.path.join(self.d, "rows.tsv")
        open(raw, "w").write("1\ta\n2\tb\\nstill row two\n3\tc\n")
        subprocess.run(["zstd", "-q", "-f", raw, "-o", raw + ".zst"], check=True)
        self.assertEqual(mr.exact_dump_rows([raw + ".zst"]), 3)


class TestIdentifiers(unittest.TestCase):
    def test_backtick_quoting(self):
        self.assertEqual(mr.q("plain"), "`plain`")
        self.assertEqual(mr.q("we`ird"), "`we``ird`")


if __name__ == "__main__":
    unittest.main()
