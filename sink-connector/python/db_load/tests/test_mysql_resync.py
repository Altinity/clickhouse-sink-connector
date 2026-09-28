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

    def test_sql_inserts_a_newer_row_for_the_same_key(self):
        sql = mr.rewind_offset_sql("db.offsets", '{"file":"f","pos":4}')
        self.assertIn("INSERT INTO db.offsets (id, offset_key, offset_val, record_insert_ts, record_insert_seq)", sql)
        self.assertIn("record_insert_seq + 1", sql)
        self.assertIn("FROM db.offsets FINAL", sql)


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
