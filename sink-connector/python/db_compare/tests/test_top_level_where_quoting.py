"""Regression tests for the quoting of the ``--where`` argument the top-level
checksum tool hands to the two side scripts.

Background: fstr() on both sides used to be ``eval(f"f'{template}'")``, which
consumed backslash escapes, so the top-level emitted ``toDate(\\'...\\')`` and
config authors wrote ``\\' 16:30:00\\'`` in per-table ``where:`` overrides.
When fstr() became a literal substitution the backslashes started reaching the
servers unchanged and every partitioned table failed on the ClickHouse side
with ``Code: 62 ... Unrecognized token: '\\'`` (first run of 2.11.0 in
production, 2026-09-29). These tests pin the contract end to end: the argv
word the side script receives, the SQL fragment after fstr(), and -- when a
``clickhouse-format`` binary is available -- that ClickHouse parses it.
"""
import os
import shutil
import subprocess
import sys
import unittest
from datetime import datetime
from types import SimpleNamespace

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import db_compare.clickhouse_table_checksum as ch  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402
import db_compare.top_level_table_checksum as tl  # noqa: E402

CLICKHOUSE_FORMAT = shutil.which("clickhouse-format") or shutil.which("clickhouse")


def top_level_args(**overrides):
    values = dict(
        partition_date=datetime(2026, 9, 28), threads=8, threads_per_table=16,
        source_timezone="UTC", binary_encoding="base64",
        include_floating_point_columns=False, include_json_columns=False,
    )
    values.update(overrides)
    return SimpleNamespace(**values)


def where_word(command):
    """The argv word that follows ``--where`` in the command the top-level
    builds (an argv list run without a shell, so it reaches the side as is)."""
    return command[command.index("--where") + 1]


def clickhouse_parses(sql):
    """True when ``clickhouse-format`` accepts ``sql`` (the same parser the
    server uses); None when no binary is available to ask."""
    if not CLICKHOUSE_FORMAT:
        return None
    argv = [CLICKHOUSE_FORMAT]
    if os.path.basename(CLICKHOUSE_FORMAT) == "clickhouse":
        argv.append("format")
    result = subprocess.run(argv + ["--oneline"], input=sql, capture_output=True, text=True)
    return result.returncode == 0


class ClickHouseWhereQuotingTestCase(unittest.TestCase):
    def setUp(self):
        tl.args = top_level_args()

    def test_partition_date_uses_plain_quotes(self):
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10, where=None,
                                                 partition_key="trade_date")
        value = where_word(cmd)
        self.assertEqual(value, " 1=1  and {partition_expression}=toDate('2026-09-28') ")
        self.assertNotIn("\\", " ".join(cmd), "no backslash may reach the side script")

    def test_fragment_after_fstr_is_valid_clickhouse(self):
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10, where=None,
                                                 partition_key="trade_date")
        fragment = ch.fstr(where_word(cmd), "trade_date")
        self.assertEqual(fragment, " 1=1  and trade_date=toDate('2026-09-28') ")
        parses = clickhouse_parses(f"select 1 from db1.t1 final where {fragment}")
        if parses is None:
            self.skipTest("no clickhouse-format binary on this host")
        self.assertTrue(parses)

    def test_legacy_escaped_fragment_is_rejected_by_clickhouse(self):
        # Mutation check for the test above: the pre-fix text must NOT parse,
        # otherwise the parser step proves nothing.
        parses = clickhouse_parses("select 1 from db1.t1 final where  1=1  and trade_date=toDate(\\'2026-09-28\\') ")
        if parses is None:
            self.skipTest("no clickhouse-format binary on this host")
        self.assertFalse(parses)

    def test_override_where_is_appended_before_partition_clause(self):
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10,
                                                 where="db_from >= toDateTime('2026-09-27 16:30:00')",
                                                 partition_key="trade_date")
        self.assertEqual(where_word(cmd),
                         " 1=1  and db_from >= toDateTime('2026-09-27 16:30:00')  and {partition_expression}=toDate('2026-09-28') ")

    def test_no_partition_date_emits_no_partition_clause(self):
        tl.args = top_level_args(partition_date=None)
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10, where=None)
        self.assertEqual(where_word(cmd), " 1=1 ")
        self.assertNotIn("{partition_expression}", " ".join(cmd))


class MySQLWhereQuotingTestCase(unittest.TestCase):
    def setUp(self):
        tl.args = top_level_args()

    def test_partition_date_is_a_bare_yyyymmdd_literal(self):
        cmd = tl.get_mysql_checksum_command("my1", "db1", "t1", "id", 10, where=None)
        self.assertEqual(where_word(cmd), " 1=1  and {partition_expression}=20260928")
        self.assertNotIn("\\", " ".join(cmd))

    def test_fragment_after_fstr(self):
        cmd = tl.get_mysql_checksum_command("my1", "db1", "t1", "id", 10, where=None)
        self.assertEqual(my.fstr(where_word(cmd), "trade_date"), " 1=1  and trade_date=20260928")


class WhereOverrideNormalizationTestCase(unittest.TestCase):
    LEGACY = ("db_from >= /*!50000 CONVERT_TZ( */ TIMESTAMP(CONCAT(DATE_SUB({partition_expression}, INTERVAL 1 DAY),"
              " \\' 16:30:00\\')) /*!50000 , \\'America/Chicago\\', \\'UTC\\') */")
    PLAIN = ("db_from >= /*!50000 CONVERT_TZ( */ TIMESTAMP(CONCAT(DATE_SUB({partition_expression}, INTERVAL 1 DAY),"
             " ' 16:30:00')) /*!50000 , 'America/Chicago', 'UTC') */")

    def test_legacy_escaped_quotes_are_folded(self):
        self.assertEqual(tl.normalize_where_override("db.t", self.LEGACY), self.PLAIN)

    def test_plain_override_is_unchanged(self):
        self.assertEqual(tl.normalize_where_override("db.t", self.PLAIN), self.PLAIN)

    def test_none_and_empty_pass_through(self):
        self.assertIsNone(tl.normalize_where_override("db.t", None))
        self.assertEqual(tl.normalize_where_override("db.t", ""), "")

    def test_folded_override_reaches_clickhouse_unescaped(self):
        tl.args = top_level_args()
        override = tl.normalize_where_override("db.t", self.LEGACY)
        cmd = tl.get_clickhouse_checksum_command("ch1", "db1", "t1", "id", 10, where=override,
                                                 partition_key="trade_date")
        fragment = ch.fstr(where_word(cmd), "trade_date")
        self.assertNotIn("\\", fragment)
        self.assertIn("' 16:30:00'", fragment)
        parses = clickhouse_parses(f"select 1 from db1.t1 final where {fragment}")
        if parses is None:
            self.skipTest("no clickhouse-format binary on this host")
        self.assertTrue(parses)

    def test_folded_override_reaches_mysql_unescaped(self):
        tl.args = top_level_args()
        override = tl.normalize_where_override("db.t", self.LEGACY)
        cmd = tl.get_mysql_checksum_command("my1", "db1", "t1", "id", 10, where=override)
        fragment = my.fstr(where_word(cmd), "trade_date")
        self.assertNotIn("\\", fragment)
        self.assertIn("CONVERT_TZ( */ TIMESTAMP(CONCAT(DATE_SUB(trade_date, INTERVAL 1 DAY), ' 16:30:00'))", fragment)


if __name__ == "__main__":
    unittest.main()
