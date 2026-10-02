"""Consistent checksums of a table that is written while it is compared (spec 13.06 sections 3.7.1, 3.7.4).

A writer thread keeps changing ``pyops.temp_churn`` for the whole module, the way an
application does on a production source: each transaction closes a row (``is_active = 0``)
and inserts its successor, and some transactions only update. A single unlocked pass reads
the two databases at different moments, and the MySQL side reads its PK chunks over
separate connections, so it can differ on clean data. The two consistent modes must not:

- ``--lock_tables_on_source --wait_for_connector``: the table is locked, the end of the
  source binary log is read under the lock and the connector is awaited up to it before
  either side reads (no fixed sleep). No re-check is allowed, so the MATCH proves the
  comparison itself is consistent.
- ``--consistent_snapshot``: no lock; each PK slice is read on MySQL in one consistent
  snapshot, the connector is awaited up to that snapshot's binlog position, then
  ClickHouse reads the slice. The suite's MySQL (``mysql:8.0``) has no snapshot position
  variables, so the position is the binary log head read right after the snapshot starts,
  an upper bound; a slice that raced a write in between is read again.

Both modes must still report a real divergence, and ``--consistent_snapshot`` names the
slice that holds it. The table name starts with ``temp_``, which the scheduled-job
commands of the other modules exclude, so they never see it.
"""
import random
import re
import shlex
import threading
import time

import pytest

from mysql_e2e_support import (BINARY_ENCODING, CH_HOST, DB, OFFSET_TABLE, SOURCE_TIMEZONE, mysql_connection,
                               mysql_query, parse_verdicts, planted, wait_for_replication, warning_lines)

TABLE = "temp_churn"
ROWS = 60000
COLD_ROWS = 1000          # ids 1..1000 are never written by the churn: the planted divergence stays put
SLICE_ROWS = 10000
LOG = "top_level_table_checksum_consistent.log"


class Churn:
    """Writes to the table in the background until stopped; counts its transactions."""

    def __init__(self):
        self.stop = threading.Event()
        self.transactions = 0
        self.errors = []
        self.thread = threading.Thread(target=self.run, name="churn", daemon=True)

    def run(self):
        conn = mysql_connection(DB)
        conn.autocommit(False)
        rng = random.Random(7)
        try:
            while not self.stop.is_set():
                row_id = rng.randint(COLD_ROWS + 1, ROWS)
                with conn.cursor() as cur:
                    if rng.random() < 0.5:
                        # Close one version and insert its successor in one transaction,
                        # like the bitemporal application writes.
                        cur.execute(f"UPDATE `{TABLE}` SET is_active = 0, updated_at = NOW(6) "
                                    f"WHERE id = {row_id} AND is_active = 1")
                        cur.execute(f"INSERT INTO `{TABLE}` (grp, v, is_active, note, updated_at) "
                                    f"VALUES ({row_id % 97}, {rng.randint(0, 10 ** 6)}, 1, 'successor', NOW(6))")
                    else:
                        cur.execute(f"UPDATE `{TABLE}` SET v = v + 1, updated_at = NOW(6) WHERE id = {row_id}")
                conn.commit()
                self.transactions += 1
                time.sleep(0.002)
        except Exception as e:  # reported by the fixture, never swallowed
            self.errors.append(repr(e))
        finally:
            conn.close()

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.thread.join(timeout=120)
        assert not self.thread.is_alive(), "the churn writer did not stop"
        assert not self.errors, self.errors


@pytest.fixture(scope="module")
def churn_table(stack):
    """The table, seeded and replicated before the writer starts."""
    mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{TABLE}`")  # DESTRUCTIVE: the module's own scratch table only
    mysql_query(f"CREATE TABLE `{DB}`.`{TABLE}` (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, grp INT NOT NULL, "
                "v INT NOT NULL, is_active TINYINT NOT NULL, note VARCHAR(32) NOT NULL, "
                "updated_at DATETIME(6) NOT NULL) ENGINE=InnoDB")
    conn = mysql_connection(DB)
    try:
        with conn.cursor() as cur:
            batch = 5000
            for start in range(0, ROWS, batch):
                cur.executemany(f"INSERT INTO `{TABLE}` (grp, v, is_active, note, updated_at) "
                                "VALUES (%s, %s, 1, 'seed', '2026-10-01 00:00:00.000000')",
                                [(i % 97, i) for i in range(start, min(start + batch, ROWS))])
    finally:
        conn.close()
    wait_for_replication([(DB, TABLE)])
    return TABLE


@pytest.fixture
def churn(churn_table):
    """The writer, running for one test only (the idle-source test runs without it)."""
    with Churn() as writer:
        time.sleep(3)  # the writer is running before the comparison starts
        yield writer
    print(f"[churn] {writer.transactions} transactions written during the test")


@pytest.fixture(scope="module")
def config(ws):
    return ws.write_job_config("top_level_table_checksum_consistent.yaml", databases=(DB,), override_map=None,
                               ignored_columns=(), where_overrides={}, replica_extra={"offset_table": OFFSET_TABLE})


def command(config, mode_flags):
    argv = ["python", "db_compare/top_level_table_checksum.py", "--defaults_file", ".my.cnf", "--config", config,
            "--binary_encoding", BINARY_ENCODING, "--source_timezone", SOURCE_TIMEZONE,
            "--tables_regex", f"^{TABLE}$", "--exclude_tables_regex", "(no_partition|heartbeat)", "--threads", "1",
            "--fence_idle_seconds", "3"] + mode_flags
    return shlex.join(argv) + f" | tee {LOG}\n"


LOCK_AND_WAIT = ["--lock_tables_on_source", "--wait_for_connector", "--threads_per_table", "4",
                 "--recheck_differences", "0"]
SNAPSHOT = ["--consistent_snapshot", "--threads_per_table", "4", "--snapshot_slice_rows", str(SLICE_ROWS),
            "--recheck_differences", "3"]


def run(ws, config, mode_flags):
    result = ws.run_job([command(config, mode_flags)])
    return result, result.log(LOG)


def test_lock_and_wait_matches_while_the_table_is_written(ws, config, churn):
    before = churn.transactions
    (result, log) = run(ws, config, LOCK_AND_WAIT)
    time.sleep(1)  # the writer is blocked while the table is locked; it resumes after UNLOCK
    assert churn.transactions > before, "the table was not written around the run"
    assert result.returncode == 0, result
    assert warning_lines(log) == [], result
    assert parse_verdicts(log) == {f"{DB}.{TABLE}": "MATCH"}, log
    assert re.search(r"Connector on \S+ (reached|is idle at)", log), log
    assert "Locking table" in log and "Unlocking table" in log, log


def test_consistent_snapshot_matches_while_the_table_is_written(ws, config, churn):
    before = churn.transactions
    (result, log) = run(ws, config, SNAPSHOT)
    assert churn.transactions > before, "the table was not written during the run"
    assert result.returncode == 0, result
    assert warning_lines(log) == [], result
    assert parse_verdicts(log) == {f"{DB}.{TABLE}": "MATCH"}, log
    assert "Locking table" not in log, "the snapshot mode must not lock"
    slices = re.search(r"Consistent snapshots for \S+: (\d+) slice\(s\)", log)
    assert slices and int(slices.group(1)) >= ROWS // SLICE_ROWS, log
    assert "upper_bound" in log, "mysql:8.0 has no snapshot position variables"
    assert re.search(r"Connector on \S+ reached \S+ for \S+ slice", log), log


def test_both_modes_report_a_real_divergence(ws, config, churn):
    with planted(DB, TABLE, "note", "id = 7", "planted"):
        for mode in (LOCK_AND_WAIT, SNAPSHOT):
            (result, log) = run(ws, config, mode)
            assert parse_verdicts(log) == {f"{DB}.{TABLE}": "DIFFERENT"}, log
            assert result.returncode != 0, "the job's WARNING scan must fail the run"
            assert len(warning_lines(log)) == 1, log
            if mode is SNAPSHOT:
                assert re.search(r"Checksum difference : .* in slice \[`id` < \d+\]", warning_lines(log)[0]), log


def test_consistent_snapshot_on_an_idle_source_needs_no_writes(ws, config, churn_table):
    """No writer: the connector offset stays at the start of the last transaction, before
    the snapshot position, and the source writes nothing after it. The wait ends after
    --fence_idle_seconds instead of the timeout."""
    started = time.monotonic()
    (result, log) = run(ws, config, SNAPSHOT + ["--fence_timeout_seconds", "600"])
    assert result.returncode == 0, result
    assert parse_verdicts(log) == {f"{DB}.{TABLE}": "MATCH"}, log
    assert "nothing is in flight" in log or re.search(r"Connector on \S+ reached", log), log
    assert time.monotonic() - started < 600, "the idle source waited for the timeout"
