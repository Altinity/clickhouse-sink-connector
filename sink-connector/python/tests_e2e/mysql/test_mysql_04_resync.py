"""Ad hoc patching with ch-mysql-resync after an unlogged change (spec 13.08).

A change made with sql_log_bin=0 never reaches the connector: the production job
reports it, `ch-mysql-resync dump` + `patch --apply` (gated on the connector's
durable offset) conform ClickHouse to MySQL, and the job passes again. The guard
scenarios write only to pyops.temp_resync_guard, which the jobs exclude.

The tool runs from the job workspace as `python -m ch_sink_tools.db_load.mysql_resync`
(the `ch-mysql-resync` console script of an installed package). Flags the pre-fix
tools lack (--offset-table, --connector-stopped, ...) are left out when the tool
under test does not have them (Workspace.supports), so a pre-fix run measures the
pre-fix behaviour, not an argparse error.
"""
import datetime as dt
import json

import pytest

from mysql_e2e_support import (BINARY_ENCODING, CH_HOST, DB, JOB_LOG_NON_PARTITIONED, MYSQL_HOST, MYSQL_PASSWORD,
                               MYSQL_PORT, MYSQL_USER, OFFSET_TABLE, ch_insert, ch_query, connector_offset_rows,
                               mysql_query,
                               parse_verdicts, planted, wait_for_connector_offset_at_or_past, warning_lines)

RESYNC = ["-m", "ch_sink_tools.db_load.mysql_resync"]
LOADER = ["-m", "ch_sink_tools.db_load.clickhouse_loader"]
STAMP = "e2e"
GUARD = "temp_resync_guard"
SCRATCH_DB = f"{DB}_restore"     # ch-mysql-resync's default --restore-suffix


def resync(ws, *argv, env=None):
    return ws.py(*RESYNC, *argv, env=env)


def offset_key():
    rows = connector_offset_rows()
    assert len(rows) == 1, rows
    return rows[0][0]


def patch_argv(ws, dump_base, tables, offset_table=OFFSET_TABLE, extra=()):
    argv = ["patch", "--dump-base", dump_base, "--stamp", STAMP, "--schemas", DB, "--tables", tables,
            "--ch-host", CH_HOST, "--ch-config", ws.ch_config, "--apply"]
    loader = f"{ws.python} {' '.join(LOADER)}"
    if ws.supports("--binary_handling_mode", *LOADER):
        loader += f" --binary_handling_mode {BINARY_ENCODING}"
    argv += ["--loader-cmd", loader]
    if offset_table and ws.supports("--offset-table", *RESYNC, "patch"):
        argv += ["--offset-table", offset_table, "--offset-key", offset_key()]
    return argv + list(extra)


def rewind_argv(ws, dump_base, position_file=None, stopped=True):
    argv = ["rewind-sql", "--dump-base", dump_base, "--stamp", STAMP, "--offset-table", OFFSET_TABLE,
            "--offset-key", offset_key(), "--ch-host", CH_HOST, "--ch-config", ws.ch_config]
    if position_file:
        argv += ["--position-file", position_file]
    if stopped and ws.supports("--connector-stopped", *RESYNC, "rewind-sql"):
        # The suite's connector keeps running; the attestation is what the guard checks.
        argv += ["--connector-stopped", "--connector-idle-seconds", "0"]
    return argv


def guard_rows():
    return sorted(ch_query(f"SELECT id, v FROM `{DB}`.`{GUARD}` FINAL WHERE is_deleted = 0"))


GUARD_ROWS = [(1, "one"), (2, "two"), (3, "three")]


@pytest.fixture(scope="module")
def resync_dump(ws):
    """An unlogged change in MySQL, then `ch-mysql-resync dump` of the changed table and
    the guard table (consistent), with the connector's offset at or past the dump."""
    # a new value on every run, so a re-run against the same stack changes the row again
    mysql_query(f"UPDATE `{DB}`.instruments SET symbol = 'UNLOGGED {dt.datetime.now():%H%M%S%f}', "
                "tick = 42.000001 WHERE id = 2", unlogged=True)
    base = ws.root / "resync"
    result = resync(ws, "dump", "--dump-base", base, "--stamp", STAMP, "--mysql-uri",
                    f"{MYSQL_USER}@{MYSQL_HOST}:{MYSQL_PORT}", "--schemas", DB,
                    "--tables", f"^(instruments|{GUARD})$", "--consistent", "--threads", "2",
                    env={"MYSQL_PWD": MYSQL_PASSWORD})
    assert result.returncode == 0, result
    position = json.loads((base / f"binlog_position_{STAMP}.json").read_text())
    wait_for_connector_offset_at_or_past(position["file"], position["pos"])
    return base


@pytest.fixture
def clean_guard_table():
    assert guard_rows() == GUARD_ROWS, "the guard table must start equal to MySQL"
    yield
    # DESTRUCTIVE: removes only the scratch copy pyops_restore.temp_resync_guard a test may have
    # left on the disposable e2e ClickHouse (never a live table).
    ch_query(f"DROP TABLE IF EXISTS `{SCRATCH_DB}`.`{GUARD}`")


def test_unlogged_change_is_reported_then_repaired_by_resync(ws, resync_dump):
    config = ws.write_job_config("top_level_table_checksum_resync.yaml", databases=[DB])
    before = ws.run_job([ws.job_command(config, partitioned=False)])
    assert before.returncode == 1, before
    assert parse_verdicts(before.log(JOB_LOG_NON_PARTITIONED))[f"{DB}.instruments"] == "DIFFERENT", before

    result = resync(ws, *patch_argv(ws, resync_dump, "^instruments$"))
    assert result.returncode == 0, result
    assert f"{DB}.instruments: REPLACED_OK" in result.output, result

    after = ws.run_job([ws.job_command(config, partitioned=False)])
    assert after.returncode == 0, after
    assert warning_lines(after.all_logs) == [], after
    assert parse_verdicts(after.log(JOB_LOG_NON_PARTITIONED)) == {
        f"{DB}.instruments": "MATCH", f"{DB}.keyless_events": "MATCH", f"{DB}.ledger": "MATCH"}, after


def test_patch_refuses_to_replace_while_the_connector_is_behind_the_dump(ws, resync_dump, clean_guard_table):
    """The connector's durable offset is BEFORE the dump position: no REPLACE (spec 13.08 D-13.08-5)."""
    fake = "pye2e_fake_offsets"
    (key, current) = (offset_key(), connector_offset_rows()[0][1])
    behind = dict(current, file=current["file"].rsplit(".", 1)[0] + ".000001", pos=4)
    # DESTRUCTIVE: drops only this test's stand-in offset database (pye2e_fake_offsets) on the
    # disposable e2e ClickHouse; the connector's real offset table is never written.
    ch_query(f"DROP DATABASE IF EXISTS {fake}")
    ch_query(f"CREATE DATABASE {fake}")
    ch_query(f"CREATE TABLE {fake}.replica_source_info AS {OFFSET_TABLE}")
    ch_insert(f"INSERT INTO {fake}.replica_source_info (id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
             "VALUES", [("e2e", key, json.dumps(behind), dt.datetime.now().replace(microsecond=0), 1)])
    try:
        with planted(DB, GUARD, "v", "id = 1", "planted-in-clickhouse"):
            result = resync(ws, *patch_argv(ws, resync_dump, f"^{GUARD}$", offset_table=f"{fake}.replica_source_info"))
            rows_during = guard_rows()
    finally:
        # DESTRUCTIVE: the same stand-in offset database, removed after the test.
        ch_query(f"DROP DATABASE IF EXISTS {fake}")
    assert rows_during == [(1, "planted-in-clickhouse")] + GUARD_ROWS[1:], (rows_during, result)
    assert result.returncode == 1, result
    assert "CONNECTOR_BEHIND" in result.output, result


def test_patch_skip_load_refuses_a_scratch_table_it_did_not_load(ws, resync_dump, clean_guard_table):
    """--skip-load REPLACEs only from a scratch table marked as loaded from this dump (spec
    13.08 D-13.08-8); an unmarked one with stale rows is refused."""
    ch_query(f"CREATE DATABASE IF NOT EXISTS `{SCRATCH_DB}`")
    # DESTRUCTIVE: replaces only the scratch copy pyops_restore.temp_resync_guard (the stale
    # table this test plants) on the disposable e2e ClickHouse; never a live table.
    ch_query(f"DROP TABLE IF EXISTS `{SCRATCH_DB}`.`{GUARD}`")
    ch_query(f"CREATE TABLE `{SCRATCH_DB}`.`{GUARD}` AS `{DB}`.`{GUARD}`")
    ch_insert(f"INSERT INTO `{SCRATCH_DB}`.`{GUARD}` (id, v, _version, is_deleted) VALUES",
             [(i, "stale", 1, 0) for (i, _) in GUARD_ROWS])
    result = resync(ws, *patch_argv(ws, resync_dump, f"^{GUARD}$", extra=("--skip-load",)))
    assert guard_rows() == GUARD_ROWS, result
    assert result.returncode == 1, result
    assert "SCRATCH_STAMP_MISMATCH" in result.output, result


def test_rewind_sql_requires_the_connector_stopped_attestation(ws, resync_dump):
    """Without --connector-stopped no rewind statement is printed (spec 13.08 D-13.08-9)."""
    result = resync(ws, *rewind_argv(ws, resync_dump, stopped=False))
    assert result.returncode != 0, result
    assert "INSERT INTO" not in result.output, result
    assert "--connector-stopped" in result.output, result


def test_rewind_sql_refuses_a_forward_rewind(ws, resync_dump):
    """A recorded position after the connector's offset would skip binlog events (spec 13.08 D-13.08-6)."""
    current = connector_offset_rows()[0][1]
    (stem, sequence) = current["file"].rsplit(".", 1)
    ahead = ws.root / "position_ahead.json"
    ahead.write_text(json.dumps({"file": f"{stem}.{int(sequence) + 1:06d}", "pos": 4, "ts_sec": 0}))
    result = resync(ws, *rewind_argv(ws, resync_dump, position_file=ahead))
    assert result.returncode != 0, result
    assert "INSERT INTO" not in result.output, result
    assert "FORWARD" in result.output, result


def test_rewind_sql_accepts_the_dumper_snapshot_position(ws, resync_dump, mysql_snapshot):
    """mysql_dumper's snapshot_position.json is the --position-file of rewind-sql (specs 13.03
    D-13.03-1 and 13.08): the statement rewinds to the dump's binlog position."""
    assert mysql_snapshot.result.returncode == 0, mysql_snapshot.result
    handoff = json.loads(mysql_snapshot.position_file.read_text())
    # A rewind goes back: the connector has applied the binlog past the dump position.
    wait_for_connector_offset_at_or_past(handoff["file"], handoff["pos"])
    result = resync(ws, *rewind_argv(ws, resync_dump, position_file=mysql_snapshot.position_file))
    assert result.returncode == 0, result
    assert f"{handoff['file']}:{handoff['pos']}" in result.output, result
    assert result.output.count("INSERT INTO") == 1, result
    statement = result.output[result.output.index("INSERT INTO"):]
    assert OFFSET_TABLE in statement and handoff["file"] in statement and str(handoff["pos"]) in statement, statement


def test_z_patch_refuses_an_empty_restore_suffix(ws, resync_dump, clean_guard_table):
    """An empty --restore-suffix makes the scratch database the live one: refused before any
    statement, the live table is untouched (spec 13.08 D-13.08-11). Runs last: the pre-fix
    tool drops the live table."""
    result = resync(ws, *patch_argv(ws, resync_dump, f"^{GUARD}$", offset_table=None, extra=("--restore-suffix", "")))
    assert ch_query(f"EXISTS TABLE `{DB}`.`{GUARD}`")[0][0] == 1, result
    assert guard_rows() == GUARD_ROWS, result
    assert result.returncode != 0, result
