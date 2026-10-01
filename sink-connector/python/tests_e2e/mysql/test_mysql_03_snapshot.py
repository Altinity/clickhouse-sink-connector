"""Snapshot path: mysql_dumper -> snapshot_position.json -> clickhouse_loader into a fresh
database, verified with the production checksum job (specs 13.03 and 13.04).

The dump (MySQL Shell util.dumpTables, consistent) must hand over a binlog position at
or before the source position read right after it; the loader, told the connector's
binary.handling.mode, must give tables the production job reports equal to MySQL.
"""
import json
import shutil

import pytest

from mysql_e2e_support import (CH_HOST, DB, JOB_LOG_NON_PARTITIONED, JOB_LOG_PARTITIONED, MYSQL_HOST, SNAPSHOT_DB,
                               binlog_position_key, ch_query, checksummed_tables, parse_counts, parse_verdicts,
                               warning_lines)

SNAPSHOT_TABLES = {"fills", "positions_bt", "quotes", "temp_x", "temp_events_by_days", "instruments",
                   "keyless_events", "ledger", "temp_checksum_named", "fills_p1", "heartbeat",
                   "temp_resync_guard"}


def test_dumper_hands_over_a_verified_snapshot_position(mysql_snapshot):
    result = mysql_snapshot.result
    assert result.returncode == 0, result
    assert mysql_snapshot.position_file.is_file(), result
    handoff = json.loads(mysql_snapshot.position_file.read_text())
    position = binlog_position_key(handoff["binlog_file"], handoff["binlog_position"])
    assert binlog_position_key(*mysql_snapshot.before) <= position <= binlog_position_key(*mysql_snapshot.after), \
        (mysql_snapshot.before, handoff, mysql_snapshot.after)
    assert (handoff["file"], handoff["pos"]) == (handoff["binlog_file"], handoff["binlog_position"])
    assert handoff["database"] == DB and set(handoff["tables"]) == SNAPSHOT_TABLES, handoff
    assert f"snapshot position: binlog {handoff['binlog_file']}:{handoff['binlog_position']}" in result.output


def test_loader_fills_a_fresh_database(snapshot_load):
    assert snapshot_load.returncode == 0, snapshot_load
    tables = {name for (name,) in ch_query(f"SELECT name FROM system.tables WHERE database = '{SNAPSHOT_DB}'")}
    assert SNAPSHOT_TABLES <= tables, tables


def test_production_job_matches_the_loaded_snapshot(ws, snapshot_load):
    """The production job, pointed at the loaded database with database_override_map,
    reports a match for every table."""
    assert snapshot_load.returncode == 0, snapshot_load
    config = ws.write_job_config("top_level_table_checksum_snapshot.yaml", databases=[DB],
                                 override_map=f"{DB}:{SNAPSHOT_DB}")
    result = ws.run_job([ws.job_command(config, partitioned=True), ws.job_command(config, partitioned=False)])
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    partitioned = {f"{DB}.fills": "MATCH", f"{DB}.positions_bt": "MATCH", f"{DB}.quotes": "EMPTY"}
    non_partitioned = {f"{DB}.instruments": "MATCH", f"{DB}.keyless_events": "MATCH", f"{DB}.ledger": "MATCH"}
    assert parse_verdicts(result.log(JOB_LOG_PARTITIONED)) == partitioned, result
    assert parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED)) == non_partitioned, result
    assert f"'{SNAPSHOT_DB}.instruments'" in result.all_logs, result


def test_keyless_table_keeps_every_row_after_optimize_final(snapshot_load):
    """A table without a key must not be collapsed by merges (spec 13.04 D-13.04-1)."""
    assert snapshot_load.returncode == 0, snapshot_load
    ch_query(f"OPTIMIZE TABLE `{SNAPSHOT_DB}`.keyless_events FINAL")
    assert ch_query(f"SELECT count() FROM `{SNAPSHOT_DB}`.keyless_events FINAL")[0][0] == 6
    sorting_key = ch_query(f"SELECT sorting_key FROM system.tables WHERE database = '{SNAPSHOT_DB}' "
                           "AND name = 'keyless_events'")[0][0]
    assert sorting_key != "", sorting_key


VALUE_COLUMNS = {
    "fills": "id, side, flags, venue_id, toString(executed_at), toString(updated_at), qty, price",
    "instruments": "id, isin, code4, token, blob_data, active, mask, toString(listed_at), toString(updated_at), tick",
    "keyless_events": "toString(event_time), kind, amount, raw",
}


@pytest.mark.parametrize("table", sorted(VALUE_COLUMNS))
def test_loaded_values_equal_the_streamed_values(snapshot_load, table):
    """TIMESTAMP, DATETIME(6), binary and BIT values the loader wrote equal what the
    connector streamed (spec 13.04 D-13.04-2, D-13.04-3)."""
    assert snapshot_load.returncode == 0, snapshot_load
    columns = VALUE_COLUMNS[table]

    def rows(database):
        return ch_query(f"SELECT {columns} FROM `{database}`.`{table}` FINAL WHERE is_deleted = 0 ORDER BY {columns}")

    streamed = rows(DB)
    assert len(streamed) > 0
    assert rows(SNAPSHOT_DB) == streamed


COUNT_TABLES = ["fills", "positions_bt", "quotes", "instruments", "keyless_events", "ledger"]


def test_clickhouse_count_agrees_between_the_live_and_the_restored_copy(ws, snapshot_load):
    """clickhouse_table_count run once per ClickHouse database/host (it has no option that
    compares two hosts): the live replica and the restored copy give the same counts."""
    assert snapshot_load.returncode == 0, snapshot_load
    regex = "^(" + "|".join(COUNT_TABLES) + ")$"
    counts = {}
    for (host, database) in ((CH_HOST, DB), (MYSQL_HOST, SNAPSHOT_DB)):
        result = ws.py("db_compare/clickhouse_table_count.py", "--clickhouse_host", host,
                       "--clickhouse_database", database, "--clickhouse_config_file", "clickhouse-client.xml",
                       "--tables_regex", regex)
        assert result.returncode == 0, result
        counts[database] = {name.split(".", 1)[1]: n for name, n in parse_counts(result.output).items()}
    assert sorted(counts[DB]) == sorted(COUNT_TABLES), counts
    assert counts[DB] == counts[SNAPSHOT_DB], counts
    assert counts[DB]["fills"] == 9 and counts[DB]["keyless_events"] == 6, counts


def test_loader_fails_loudly_on_a_corrupt_dump_chunk(ws, mysql_snapshot, tmp_path):
    """A data chunk zstd cannot decompress fails the load with a non-zero exit (spec 13.04
    D-13.04-4: the load pipeline runs under pipefail)."""
    assert mysql_snapshot.result.returncode == 0, mysql_snapshot.result
    broken = tmp_path / "dump_broken"
    shutil.copytree(mysql_snapshot.dump_dir, broken)
    chunks = sorted(broken.glob(f"{DB}@keyless_events@*.zst"))
    assert chunks, sorted(p.name for p in broken.iterdir())
    for chunk in chunks:
        chunk.write_bytes(b"this is not a zstd frame")
    database = f"{SNAPSHOT_DB}_broken"
    # DESTRUCTIVE: drops only this test's scratch database (pyops_snapshot_broken) on the
    # disposable e2e ClickHouse, before and after the load; nothing else is touched.
    ch_query(f"DROP DATABASE IF EXISTS `{database}`")
    argv = ["db_load/clickhouse_loader.py", "--clickhouse_host", CH_HOST, "--clickhouse_config_file",
            "clickhouse-client.xml", "--clickhouse_database", database, "--mysql_source_database", DB,
            "--dump_dir", broken, "--threads", "4", "--mysqlshell", "--rmt_delete_support"]
    result = ws.py(*argv)
    try:
        assert result.returncode != 0, result
    finally:
        # DESTRUCTIVE: the same scratch database, removed after the test.
        ch_query(f"DROP DATABASE IF EXISTS `{database}`")


def test_dumper_fails_loudly_when_mysql_shell_fails(ws, mysql_snapshot):
    """MySQL Shell refuses a dump into a non-empty directory; the dumper must exit non-zero and
    leave the existing handoff file alone (spec 13.03 D-13.03-12: the exit status is read)."""
    assert mysql_snapshot.result.returncode == 0, mysql_snapshot.result
    before = mysql_snapshot.position_file.read_text()
    result = ws.py("db_dump/mysql_dumper.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                   "--defaults_file", ".my.cnf", "--dump_dir", mysql_snapshot.dump_dir, "--threads", "4")
    assert result.returncode != 0, result
    assert mysql_snapshot.position_file.read_text() == before
