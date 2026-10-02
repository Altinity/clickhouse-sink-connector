#!/usr/bin/env python3
"""Waiting for the connector and per-slice consistent snapshots (spec 13.06
sections 3.7.1 and 3.7.4), offline.

Covers the two consistent-comparison modes of the legacy driver:

- ``--lock_tables_on_source --wait_for_connector``: the end of the source binary
  log is read under the table lock and every replica's connector is awaited up
  to it before the sides run (no fixed sleep);
- ``--consistent_snapshot``: no lock; each PK-range slice is read on MySQL in one
  START TRANSACTION WITH CONSISTENT SNAPSHOT, whose binlog position the MySQL
  side prints, every connector is awaited up to that position, then the replica
  reads the slice; a differing slice is read again.

Database and process helpers are patched. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_checksum_connector_fence.py
"""
import argparse
import json
import logging
import os
import sys
import unittest
from unittest.mock import MagicMock, call, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402

MD5_A = "0123456789abcdef0123456789abcdef"
MD5_B = "fedcba9876543210fedcba9876543210"


def side_line(message, level="INFO"):
    return f"2026-10-01 10:00:00,000 - {level} - MainThread - {message}"


class TestBinlogCoordinates(unittest.TestCase):

    def test_position_key_orders_across_files(self):
        self.assertLess(tl.binlog_position_key("binary.000009", 999999), tl.binlog_position_key("binary.000010", 4))
        self.assertLess(tl.binlog_position_key("binary.227977", 455830627),
                        tl.binlog_position_key("binary.227977", 774291115))

    def test_snapshot_line_is_parsed_for_the_expected_table_only(self):
        output = "\n".join([side_line("Snapshot position for table shop.orders = binlog.000042 157 exact"),
                            side_line("Checksum for table shop.orders = " + MD5_A + " count 5")])
        self.assertEqual(tl.parse_snapshot_position(output, "shop.orders"), ("binlog.000042", 157, "exact"))
        with self.assertLogs(level="ERROR"):
            self.assertIsNone(tl.parse_snapshot_position(output, "shop.items"))
        with self.assertLogs(level="ERROR"):
            self.assertIsNone(tl.parse_snapshot_position(side_line("Checksum for table shop.orders = x"), "shop.orders"))


class TestConnectorOffset(unittest.TestCase):

    def fence(self, rows, key_contains=None):
        fence = tl.ConnectorFence("ch-host", "altinity_sink_connector.replica_source_info", key_contains,
                                  config_file="unused.xml", timeout_seconds=30, poll_seconds=1, idle_seconds=10)
        patches = [patch.object(tl, "clickhouse_credentials", return_value=("u", "p")),
                   patch.object(tl, "clickhouse_connection", return_value=MagicMock()),
                   patch.object(tl, "clickhouse_execute_conn", return_value=rows)]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return fence

    def offset_row(self, key, file_name, position):
        return (key, json.dumps({"ts_sec": 1, "file": file_name, "pos": position, "row": 1, "event": 2}))

    def test_one_offset_row(self):
        fence = self.fence([self.offset_row('["source--staging",{"server":"embeddedconnector"}]', "binary.227977", 774291115)])
        self.assertEqual(fence.offset(), ("binary.227977", 774291115))

    def test_no_or_several_offsets_are_errors(self):
        with self.assertRaises(tl.ConnectorFenceError):
            self.fence([]).offset()
        rows = [self.offset_row("a-staging", "binlog.000001", 4), self.offset_row("b-uat", "binlog.000001", 9)]
        with self.assertRaises(tl.ConnectorFenceError):
            self.fence(rows).offset()
        self.assertEqual(self.fence(rows, key_contains="uat").offset(), ("binlog.000001", 9))

    def test_unreadable_table_is_an_error(self):
        fence = self.fence([])
        with patch.object(tl, "clickhouse_execute_conn", side_effect=RuntimeError("Code: 60. Table does not exist")):
            with self.assertRaises(tl.ConnectorFenceError):
                fence.offset()


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class TestConnectorWait(unittest.TestCase):

    def wait(self, offsets, heads, timeout=30, idle=10):
        fence = tl.ConnectorFence("ch-host", "db.offsets", timeout_seconds=timeout, poll_seconds=1, idle_seconds=idle)
        clock = FakeClock()
        offsets = list(offsets)
        heads = list(heads)
        with patch.object(fence, "offset", side_effect=lambda: offsets.pop(0) if len(offsets) > 1 else offsets[0]), \
                patch.object(tl.time, "monotonic", clock.monotonic), patch.object(tl.time, "sleep", clock.sleep), \
                self.assertLogs(level="INFO") as logs:
            result = fence.wait(("binlog.000005", 500), lambda: heads.pop(0) if len(heads) > 1 else heads[0], "db.t")
        return result, clock.now, "\n".join(logs.output)

    def test_reached_when_offset_is_at_or_past_the_target(self):
        ((reached, offset), elapsed, log) = self.wait([("binlog.000005", 100), ("binlog.000005", 500)],
                                                      [("binlog.000006", 4)])
        self.assertEqual((reached, offset, elapsed), (True, ("binlog.000005", 500), 1))
        self.assertIn("reached binlog.000005:500", log)

    def test_offset_in_a_later_file_is_past_the_target(self):
        ((reached, _), elapsed, _) = self.wait([("binlog.000006", 4)], [("binlog.000006", 90)])
        self.assertEqual((reached, elapsed), (True, 0))

    def test_idle_source_ends_the_wait(self):
        # The connector records the start of its last transaction (400 < 500)
        # and the source wrote nothing after 500: nothing is in flight.
        ((reached, offset), elapsed, log) = self.wait([("binlog.000005", 400)], [("binlog.000005", 500)], idle=10)
        self.assertEqual((reached, offset), (True, ("binlog.000005", 400)))
        self.assertEqual(elapsed, 10)
        self.assertIn("idle", log)

    def test_busy_source_behind_connector_times_out_and_says_so(self):
        ((reached, _), elapsed, log) = self.wait([("binlog.000005", 400)], [("binlog.000005", 900)], timeout=30)
        self.assertFalse(reached)
        self.assertEqual(elapsed, 30)
        self.assertIn("did NOT reach binlog.000005:500", log)
        self.assertNotIn("WARNING", log)


class TestBehindConnector(unittest.TestCase):

    def test_a_missed_target_makes_later_waits_check_once_until_caught_up(self):
        fence = tl.ConnectorFence("ch-host", "db.offsets", timeout_seconds=30, poll_seconds=1, idle_seconds=10)
        clock = FakeClock()
        offsets = [("binlog.000005", 400)]
        target = ("binlog.000005", 500)
        busy_head = lambda: ("binlog.000005", 900)
        with patch.object(fence, "offset", side_effect=lambda: offsets[0]), \
                patch.object(tl.time, "monotonic", clock.monotonic), patch.object(tl.time, "sleep", clock.sleep), \
                self.assertLogs(level="INFO") as logs:
            self.assertFalse(fence.wait(target, busy_head, "slice 1")[0])
            self.assertEqual(clock.now, 30, "the first miss waits the full timeout")
            self.assertFalse(fence.wait(target, busy_head, "slice 2")[0])
            self.assertEqual(clock.now, 30, "a later wait of a connector that is behind checks once")
            offsets[0] = ("binlog.000005", 600)
            self.assertTrue(fence.wait(target, busy_head, "slice 3")[0])
            self.assertFalse(fence.behind)
            offsets[0] = ("binlog.000005", 400)
            fence.wait(target, busy_head, "slice 4")
            self.assertEqual(clock.now, 60, "after catching up a miss waits the full timeout again")
        log = "\n".join(logs.output)
        self.assertIn("later waits of this run check the offset once until it catches up", log)
        self.assertIn("has caught up", log)


class TestSlices(unittest.TestCase):

    def test_slices_start_at_the_smallest_key_and_stay_open_above(self):
        # Every slice is bounded below: an open lower end made the replica scan its
        # whole history (28.9 billion rows of an unpartitioned replica on the first
        # dev run) for the first slice of a date-filtered source.
        chunks = [{"min_pk": 68645500028, "max_pk": 1}, {"min_pk": 68646190772, "max_pk": 1},
                  {"min_pk": 68654473943, "max_pk": 1}]
        with patch.object(tl, "divide_table_into_even_chunks", return_value=iter(chunks)):
            conditions = tl.snapshot_slices(MagicMock(), "orders", "id", " 1=1 ", 100)
        self.assertEqual(conditions, ["`id` >= 68645500028 and `id` < 68646190772",
                                      "`id` >= 68646190772 and `id` < 68654473943",
                                      "`id` >= 68654473943"])
        self.assertTrue(all(">=" in c for c in conditions), "no slice is open below")

    def test_no_integer_key_or_no_rows_is_one_slice_and_one_chunk_is_bounded_below(self):
        self.assertEqual(tl.snapshot_slices(MagicMock(), "orders", None, " 1=1 ", 100), [None])
        with patch.object(tl, "divide_table_into_even_chunks", return_value=iter([])):
            self.assertEqual(tl.snapshot_slices(MagicMock(), "orders", "id", " 1=1 ", 100), [None])
        with patch.object(tl, "divide_table_into_even_chunks", return_value=iter([{"min_pk": 7, "max_pk": 9}])):
            self.assertEqual(tl.snapshot_slices(MagicMock(), "orders", "id", " 1=1 ", 100), ["`id` >= 7"])

    def test_partition_placeholder_is_resolved_for_slicing(self):
        with patch.object(tl, "args", argparse.Namespace(partition_date=None), create=True):
            self.assertEqual(tl.mysql_where_for_slicing("{partition_expression} >= 3", "to_days(`d`)"),
                             " 1=1  and to_days(`d`) >= 3 ")
            self.assertIsNone(tl.mysql_where_for_slicing("{partition_expression} >= 3", None))


MYSQL_CMD = ["python", "db_compare/mysql_table_checksum.py", "--mysql_database", "shop"]


class TestOneSlice(unittest.TestCase):
    """checksum_one_slice: snapshot held while the connector is awaited, replica read, re-check, version fence."""

    def run_slice(self, mysql_passes, ch_results, reached=True, recheck=1, exclusion=None, floors=(), changed=()):
        """mysql_passes: [(md5, count, position or None)] per pass; changed: [set of keys] per pass."""
        mysql_passes = list(mysql_passes)
        ch_results = list(ch_results)
        changed = list(changed)
        payloads = []

        def snapshot_side(cmd, host, table, on_position):
            (md5, count, position) = mysql_passes.pop(0)
            if position is None:
                return (None, b"", None, None)
            payload = on_position(position)
            payloads.append(payload)
            return ((host, "shop.orders", md5, count), b"", position, payload)

        fence = MagicMock()
        fence.wait.return_value = (reached, ("binlog.000005", 100))
        commands_for = MagicMock(side_effect=lambda condition, extra=None: (MYSQL_CMD, [("ch-host", ["ch", extra])]))
        floors = list(floors)
        with patch.object(tl, "run_snapshot_side", side_effect=snapshot_side), \
                patch.object(tl, "run_quick_safe_checksum", side_effect=lambda cmd, host, table: ch_results.pop(0)), \
                patch.object(tl, "slice_max_version", side_effect=lambda *a: floors.pop(0)), \
                patch.object(tl, "keys_changed_since", side_effect=lambda *a: changed.pop(0)), \
                self.assertLogs(level="INFO") as logs:
            outcome = tl.checksum_one_slice("shop.orders", "orders", "`id` < 101", commands_for, "db1", ["ch-host"],
                                            {"ch-host": fence}, lambda: ("binlog.000005", 900), recheck, exclusion)
        return outcome, fence, "\n".join(logs.output), payloads, commands_for

    POS = ("binlog.000005", 777, "exact")
    EXCLUSION = {"column": "id", "databases": {"ch-host": "shop"}, "cap": 3}

    def test_match_waits_for_the_snapshot_position_while_the_snapshot_is_held(self):
        (outcome, fence, log, payloads, _) = self.run_slice([(MD5_A, 5, self.POS)],
                                                             [("ch-host", "shop.orders", MD5_A, 5)])
        self.assertEqual(outcome, (tl.VERDICT_MATCH, 5, 0))
        self.assertEqual(fence.wait.call_args[0][0], ("binlog.000005", 777))
        self.assertEqual(payloads, [{"column": None, "keys": []}])
        self.assertNotIn("WARNING", log)
        # the side's INFO line reaches the driver log only at DEBUG: the driver logs the position itself
        self.assertIn("Snapshot of shop.orders slice [`id` < 101] at binlog.000005:777 (exact)", log)

    def test_difference_read_again_and_gone_is_a_match(self):
        (outcome, fence, log, _, _) = self.run_slice(
            [(MD5_A, 5, self.POS), (MD5_B, 6, self.POS)],
            [("ch-host", "shop.orders", MD5_B, 5), ("ch-host", "shop.orders", MD5_B, 6)])
        self.assertEqual(outcome, (tl.VERDICT_MATCH, 6, 0))
        self.assertEqual(fence.wait.call_count, 2, "a fresh snapshot is awaited on the re-read")
        self.assertNotIn("WARNING", log)
        self.assertIn("Checksum mismatch on pass 1 of 2, reading the slice again", log)

    def test_persistent_difference_warns_with_the_slice(self):
        (outcome, _, log, _, _) = self.run_slice(
            [(MD5_A, 5, self.POS), (MD5_A, 5, self.POS)],
            [("ch-host", "shop.orders", MD5_B, 5), ("ch-host", "shop.orders", MD5_B, 5)])
        self.assertEqual(outcome[0], tl.VERDICT_DIFFERENT)
        warnings = [line for line in log.splitlines() if line.startswith("WARNING")]
        self.assertEqual(len(warnings), 1, log)
        self.assertIn(f"Checksum difference : ('ch-host', 'shop.orders', '{MD5_B}', 5) to ('db1', 'shop.orders', "
                      f"'{MD5_A}', 5) in slice [`id` < 101]", warnings[0])

    def test_unreached_connector_is_named_in_the_difference(self):
        (outcome, _, log, _, _) = self.run_slice([(MD5_A, 5, self.POS)], [("ch-host", "shop.orders", MD5_B, 5)],
                                                 reached=False, recheck=0)
        self.assertEqual(outcome[0], tl.VERDICT_DIFFERENT)
        self.assertIn("connector on ch-host had not reached binlog.000005:777 (offset binlog.000005:100)", log)

    def test_missing_snapshot_position_is_an_error(self):
        (outcome, fence, _, _, _) = self.run_slice([(MD5_A, 5, None)], [])
        self.assertEqual(outcome, (tl.VERDICT_ERROR, 0, 0))
        fence.wait.assert_not_called()

    def test_failed_replica_is_an_error(self):
        (outcome, _, _, _, _) = self.run_slice([(MD5_A, 5, self.POS)], [None])
        self.assertEqual(outcome, (tl.VERDICT_ERROR, 0, 0))

    def test_keys_changed_after_the_floor_are_left_out_on_both_sides(self):
        (outcome, _, log, payloads, commands_for) = self.run_slice(
            [(MD5_A, 3, self.POS)], [("ch-host", "shop.orders", MD5_A, 3)], recheck=0,
            exclusion=self.EXCLUSION, floors=[1000], changed=[{42, 7}])
        self.assertEqual(outcome, (tl.VERDICT_MATCH, 3, 2))
        self.assertEqual(payloads, [{"column": "id", "keys": [7, 42]}], "the MySQL side gets the keys in its snapshot")
        self.assertEqual(commands_for.call_args_list[-1], call("`id` < 101", "`id` not in (7,42)"),
                         "the ClickHouse side leaves the same keys out")
        self.assertIn("Excluded 2 key(s) of shop.orders slice [`id` < 101] changed after its snapshot began", log)

    def test_too_many_changed_keys_are_not_left_out_and_say_so(self):
        (outcome, _, log, payloads, commands_for) = self.run_slice(
            [(MD5_A, 3, self.POS)], [("ch-host", "shop.orders", MD5_B, 3)], recheck=0,
            exclusion=self.EXCLUSION, floors=[1000], changed=[{1, 2, 3, 4}])
        self.assertEqual(outcome[0], tl.VERDICT_DIFFERENT)
        self.assertEqual(payloads, [{"column": "id", "keys": []}])
        self.assertEqual(commands_for.call_args_list[-1], call("`id` < 101", None))
        self.assertIn("more than 3 keys changed while the slice was read", log)


class TestVersionFenceQueries(unittest.TestCase):

    def test_queries_read_all_versions_of_the_slice(self):
        fence = MagicMock()
        fence.query.side_effect = [[(1790894739000000123,)], [(7,), (42,)], [(1,)], [(0,)]]
        self.assertEqual(tl.slice_max_version(fence, "shop", "orders", "`id` < 101"), 1790894739000000123)
        self.assertEqual(tl.keys_changed_since(fence, "shop", "orders", "id", "`id` < 101", 99, 5000), {7, 42})
        self.assertEqual(fence.query.call_args_list[0][0][0],
                         "SELECT max(_version) FROM `shop`.`orders` WHERE `id` < 101")
        self.assertEqual(fence.query.call_args_list[1][0][0],
                         "SELECT DISTINCT `id` FROM `shop`.`orders` WHERE (`id` < 101) AND _version > 99 LIMIT 5001")
        self.assertTrue(tl.replicas_have_version_column({"ch": fence}, {"ch": "shop"}, "orders"))
        self.assertFalse(tl.replicas_have_version_column({"ch": fence}, {"ch": "shop"}, "orders"))


STAND_IN_SNAPSHOT_SIDE = r'''#!{python}
import json, sys
print("2026-10-01 10:00:00,000 - INFO - MainThread - Snapshot position for table shop.orders = binlog.000005 777 exact", flush=True)
line = sys.stdin.readline()
if not line:
    print("2026-10-01 10:00:00,000 - ERROR - MainThread - no exclusion line", flush=True)
    sys.exit(1)
keys = json.loads(line)["keys"]
print(f"2026-10-01 10:00:00,000 - INFO - MainThread - Checksum for table shop.orders = {{'0123456789abcdef0123456789abcdef'}} count {{10 - len(keys)}}", flush=True)
'''


class TestSnapshotSideProtocol(unittest.TestCase):
    """run_snapshot_side against a stand-in side process (real pipes)."""

    def setUp(self):
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.script = os.path.join(self.tmp.name, "side.py")
        with open(self.script, "w") as handle:
            handle.write(STAND_IN_SNAPSHOT_SIDE.format(python=sys.executable))
        self.cmd = [sys.executable, self.script, "--mysql_database", "shop"]

    def tearDown(self):
        self.tmp.cleanup()

    def test_answer_is_sent_while_the_side_waits_and_the_result_is_parsed(self):
        seen = []
        with self.assertLogs(level="INFO"):
            (result, output, position, payload) = tl.run_snapshot_side(
                self.cmd, "db1", "orders",
                lambda position: (seen.append(position) or {"column": "id", "keys": [3, 4]}))
        self.assertEqual(seen, [("binlog.000005", 777, "exact")])
        self.assertEqual(position, ("binlog.000005", 777, "exact"))
        self.assertEqual(payload, {"column": "id", "keys": [3, 4]})
        self.assertEqual(result, ("db1", "shop.orders", MD5_A, 8))

    def test_no_pipe_is_left_open(self):
        """An unclosed pipe surfaces as a ResourceWarning, which the driver's
        execute_mysql logs at WARNING and the scheduled job fails on."""
        import gc
        import warnings
        with warnings.catch_warnings(record=True) as caught, self.assertLogs(level="INFO"):
            warnings.simplefilter("always")
            tl.run_snapshot_side(self.cmd, "db1", "orders", lambda position: {"column": None, "keys": []})
            gc.collect()
        self.assertEqual([str(w.message) for w in caught if issubclass(w.category, ResourceWarning)], [])

    def test_a_failing_wait_closes_the_side_and_propagates(self):
        def fail(position):
            raise tl.ConnectorFenceError("offset unreadable")
        with self.assertRaises(tl.ConnectorFenceError), self.assertLogs(level="INFO"):
            tl.run_snapshot_side(self.cmd, "db1", "orders", fail)


class TestMySQLSideExclusionLine(unittest.TestCase):

    def read(self, text):
        import io
        return my.read_excluded_keys(io.StringIO(text))

    def test_keys_become_a_not_in_filter(self):
        with self.assertLogs(level="INFO"):
            self.assertEqual(self.read('{"column": "id", "keys": [7, 42]}\n'), "`id` not in (7,42)")
        self.assertIsNone(self.read('{"column": null, "keys": []}\n'))

    def test_malformed_or_missing_answers_are_errors(self):
        for text in ("", '{"column": "id; DROP", "keys": [1]}\n', '{"column": "id", "keys": ["1"]}\n',
                     '{"column": "id\\n", "keys": [1]}\n', '{"column": "id", "keys": [true]}\n'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.read(text)


class TestLockAndWait(unittest.TestCase):
    """compute_checksum under --lock_tables_on_source with and without --wait_for_connector."""

    def compute(self, fences):
        order = []
        lock_conn = MagicMock(name="lock_conn")
        args = argparse.Namespace(partition_date=None, threads_per_table=1, threads=1, source_timezone="UTC",
                                  binary_encoding="hex", include_floating_point_columns=False,
                                  include_json_columns=False)
        with patch.object(tl, "args", args, create=True), \
                patch.object(tl, "get_mysql_connection", return_value=lock_conn), \
                patch.object(tl, "lock_tables", side_effect=lambda *a, **k: order.append("lock")), \
                patch.object(tl, "unlock_tables", side_effect=lambda *a, **k: order.append("unlock")), \
                patch.object(tl, "source_binary_log_head", side_effect=lambda conn: (order.append(("head", conn)) or ("binlog.000005", 900))), \
                patch.object(tl.time, "sleep", side_effect=lambda s: order.append(("sleep", s))), \
                patch.object(tl, "run_quick_safe_checksum", side_effect=lambda cmd, host, table: (order.append(("side", host)) or (host, "shop.orders", MD5_A, 5))):
            results = tl.compute_checksum("shop", {}, {}, "orders", "u", "p", "db1", ["ch-host"], "id", 10, None,
                                          lock_enabled=True, sleep_after_lock=3, fences=fences)
        return results, order, lock_conn

    def test_wait_replaces_the_fixed_sleep_and_happens_under_the_lock(self):
        fence = MagicMock()
        fence.wait.return_value = (True, ("binlog.000005", 900))
        (results, order, lock_conn) = self.compute({"ch-host": fence})
        self.assertEqual(len(results), 2)
        self.assertEqual(order[0], "lock")
        self.assertEqual(order[1], ("head", lock_conn), "the position is read on the locked session")
        self.assertNotIn(("sleep", 3), order)
        self.assertEqual(fence.wait.call_args[0][0], ("binlog.000005", 900))
        self.assertLess(order.index(("head", lock_conn)), order.index(("side", "db1")))
        self.assertEqual(order[-1], "unlock")

    def test_without_wait_the_fixed_sleep_stays(self):
        (_, order, _) = self.compute(None)
        self.assertEqual(order[:2], ["lock", ("sleep", 3)])
        self.assertFalse([o for o in order if isinstance(o, tuple) and o[0] == "head"])


class TestMySQLSideSnapshotPosition(unittest.TestCase):

    def conn_with(self, responses):
        def execute(conn, sql):
            for prefix, value in responses:
                if sql.startswith(prefix):
                    if isinstance(value, Exception):
                        raise value
                    rowset = MagicMock()
                    rowset.__iter__.return_value = iter(value)
                    rowset.fetchone.return_value = value[0] if value else None
                    return (rowset, -1)
            raise AssertionError(sql)
        return execute

    def test_percona_snapshot_variables_are_exact(self):
        responses = [("SHOW SESSION STATUS", [("Binlog_snapshot_file", "binary.227977"),
                                             ("Binlog_snapshot_position", "455830627")])]
        with patch.object(my, "execute_mysql", side_effect=self.conn_with(responses)):
            self.assertEqual(my.snapshot_binlog_position(MagicMock()), ("binary.227977", 455830627, "exact"))

    def test_other_servers_use_the_binary_log_head_as_an_upper_bound(self):
        for (version, statement) in (("8.0.41", "SHOW MASTER STATUS"), ("8.4.3", "SHOW BINARY LOG STATUS")):
            with self.subTest(version=version):
                other = "SHOW BINARY LOG STATUS" if statement == "SHOW MASTER STATUS" else "SHOW MASTER STATUS"
                responses = [("SHOW SESSION STATUS", []), ("SELECT @@version", [(version,)]),
                             (other, AssertionError(f"{other} must not run on {version}")),
                             (statement, [("binlog.000042", 157, "", "", "")])]
                with patch.object(my, "execute_mysql", side_effect=self.conn_with(responses)):
                    self.assertEqual(my.snapshot_binlog_position(MagicMock()), ("binlog.000042", 157, "upper_bound"))

    def test_no_binary_log_is_an_error(self):
        responses = [("SHOW SESSION STATUS", []), ("SELECT @@version", [("8.0.41",)]), ("SHOW MASTER STATUS", [])]
        with patch.object(my, "execute_mysql", side_effect=self.conn_with(responses)):
            with self.assertRaises(RuntimeError):
                my.snapshot_binlog_position(MagicMock())

    def test_driver_head_picks_the_statement_from_the_version(self):
        responses = [("SELECT @@version", [("8.0.41-32",)]), ("SHOW MASTER STATUS", [("binary.227977", 455830627)])]
        with patch.object(tl, "execute_mysql", side_effect=self.conn_with(responses)):
            self.assertEqual(tl.source_binary_log_head(MagicMock()), ("binary.227977", 455830627))
        responses = [("SELECT @@version", [("8.0.41",)]), ("SHOW MASTER STATUS", [])]
        with patch.object(tl, "execute_mysql", side_effect=self.conn_with(responses)):
            with self.assertRaises(tl.BinlogPositionError):
                tl.source_binary_log_head(MagicMock())

    def test_snapshot_needs_one_connection(self):
        argv = ["mysql_table_checksum.py", "--mysql_host", "db1", "--tables_regex", "^t$", "--consistent_snapshot",
                "--threads_per_table", "4", "--source_timezone", "UTC"]
        with patch.object(sys, "argv", argv), self.assertRaises(SystemExit) as raised, \
                patch("sys.stderr", new=MagicMock()):
            my.main()
        self.assertEqual(raised.exception.code, 2)


class TestDriverFlags(unittest.TestCase):

    def test_snapshot_and_lock_are_exclusive(self):
        argv = ["top_level_table_checksum.py", "--config_file", "x.yaml", "--consistent_snapshot",
                "--lock_tables_on_source"]
        with patch.object(sys, "argv", argv), self.assertRaises(SystemExit) as raised, \
                patch("sys.stderr", new=MagicMock()):
            tl.main()
        self.assertEqual(raised.exception.code, 2)

    def test_offset_table_is_required_and_validated(self):
        args = argparse.Namespace(clickhouse_port=9000, clickhouse_config_file="c.xml", fence_timeout_seconds=1,
                                  fence_poll_seconds=1, fence_idle_seconds=1)
        with patch.object(tl, "args", args, create=True):
            for clickhouse in ({"host": "ch"}, {"host": "ch", "offset_table": "db.t; DROP TABLE x"}):
                with self.subTest(clickhouse=clickhouse), self.assertRaises(SystemExit), \
                        self.assertLogs(level="ERROR"):
                    tl.build_connector_fences({"replicas": [{"clickhouse": clickhouse}]})
            fences = tl.build_connector_fences(
                {"replicas": [{"clickhouse": {"host": "ch", "offset_table": "altinity_sink_connector.replica_source_info_staging",
                                              "offset_key_contains": "staging"}}]})
        self.assertEqual(fences["ch"].offset_table, "altinity_sink_connector.replica_source_info_staging")
        self.assertEqual(fences["ch"].offset_key_contains, "staging")


if __name__ == "__main__":
    unittest.main()
