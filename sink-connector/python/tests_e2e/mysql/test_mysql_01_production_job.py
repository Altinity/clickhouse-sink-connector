"""The production scheduled checksum job, reproduced exactly (spec 13.06 sections 3.3, 3.4, 3.8, 3.17).

The job is a bash script with ``set -euo pipefail``: it sources ``install.sh`` in a
copy of the tool tree, runs ``db_compare/top_level_table_checksum.py`` twice through
``tee`` (the partitioned run for one ``--partition_date`` and the non-partitioned run
under source locks), then fails when any line of the two logs contains WARNING
(``if grep -Hn "WARNING" top_level_table_checksum_*.log; then exit 1; fi``). The
config has two MySQL databases (the second written to ClickHouse under another name,
so it needs ``database_override_map``), ``ignored_columns``, and the bitemporal
``where`` of the production configs.
"""
import pytest

from mysql_e2e_support import (BITEMPORAL_ROWS_IN_WINDOW, BITEMPORAL_WHERE, CH_HOST, DB, DB2, DB2_CH,
                               JOB_EXCLUDED, JOB_LOG_NON_PARTITIONED, JOB_LOG_PARTITIONED, JOB_NON_PARTITIONED,
                               JOB_PARTITIONED, MYSQL_HOST, TOMORROW, YESTERDAY, checksummed_tables, mysql_query,
                               parse_side_results, parse_verdicts, planted, warning_lines)

CLEAN_PARTITIONED = {f"{DB}.fills": "MATCH", f"{DB}.positions_bt": "MATCH", f"{DB}.quotes": "EMPTY",
                     f"{DB2}.daily_marks": "MATCH"}
CLEAN_NON_PARTITIONED = {t: "MATCH" for t in JOB_NON_PARTITIONED}


def assert_clean_job(result, partitioned=CLEAN_PARTITIONED, non_partitioned=CLEAN_NON_PARTITIONED):
    """Both runs exit 0, the job verdict passes, every expected table has its verdict line."""
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    if partitioned is not None:
        log = result.log(JOB_LOG_PARTITIONED)
        assert parse_verdicts(log) == partitioned, log
        assert checksummed_tables(log) == sorted(partitioned), log
    if non_partitioned is not None:
        log = result.log(JOB_LOG_NON_PARTITIONED)
        assert parse_verdicts(log) == non_partitioned, log
        assert checksummed_tables(log) == sorted(non_partitioned), log


@pytest.fixture(scope="module")
def job_config(ws):
    return ws.write_job_config("top_level_table_checksum.yaml")


def test_job_sources_install_sh_under_set_euo_pipefail(ws):
    """The job sources install.sh after `set -euo pipefail`, usually with PYTHONPATH unset.
    install.sh read "${PYTHONPATH}" unguarded, so `set -u` aborted the job before any
    checksum (spec 13.06 D-13.06-39)."""
    result = ws.bash("set -euo pipefail\nunset PYTHONPATH\nsource ./install.sh\n"
                     "python -c 'import sys, db.mysql, sqlalchemy; print(\"tools importable from\", sys.path[1:3])'\n")
    assert result.returncode == 0, result
    assert "tools importable from" in result.output, result


def test_clean_data_passes_the_job(ws, job_config):
    """Clean data: both runs exit 0, no WARNING line, a verdict line for every table the job
    covers and none for the tables it excludes."""
    result = ws.run_job([ws.job_command(job_config, partitioned=True),
                         ws.job_command(job_config, partitioned=False)])
    assert_clean_job(result)
    for table in JOB_EXCLUDED:
        assert table not in result.all_logs, (table, result)
    # The second database is read from its ClickHouse name (database_override_map).
    sides = parse_side_results(result.all_logs)
    assert (CH_HOST, f"{DB2_CH}.accounts") in {(h, t) for (h, t, _, _) in sides}, sides
    assert (MYSQL_HOST, f"{DB2}.accounts") in {(h, t) for (h, t, _, _) in sides}, sides


def test_bitemporal_where_selects_the_trading_day_window(ws, job_config):
    """The per-table where of the bitemporal table (db_from at or after (D - 1) 16:30
    America/Chicago) selects the same rows on both sides: MySQL converts with CONVERT_TZ,
    ClickHouse hides the conversion in /*!50000 */ comments and parses 16:30 in its server
    zone (America/Chicago, as in production)."""
    expected = mysql_query(
        f"SELECT count(*) FROM `{DB}`.positions_bt WHERE trade_date = '{YESTERDAY}' AND "
        + BITEMPORAL_WHERE.replace("{partition_expression}", "trade_date"))[0][0]
    assert expected == BITEMPORAL_ROWS_IN_WINDOW, "MySQL time zone tables missing? CONVERT_TZ gave NULL"
    result = ws.run_job([ws.job_command(job_config, partitioned=True)])
    assert result.returncode == 0, result
    sides = [s for s in parse_side_results(result.all_logs) if s[1].endswith(".positions_bt")]
    assert sorted((host, count) for (host, _, _, count) in sides) == \
        sorted([(CH_HOST, expected), (MYSQL_HOST, expected)]), sides
    assert parse_verdicts(result.all_logs)[f"{DB}.positions_bt"] == "MATCH", result


def test_debug_run_passes_the_job(ws, job_config):
    """--debug dumps the side output into the log; the job must still see no WARNING line."""
    result = ws.run_job([ws.job_command(job_config, partitioned=True, debug=True),
                         ws.job_command(job_config, partitioned=False, debug=True)])
    assert_clean_job(result)


def test_single_database_run_passes_the_job(ws, job_config):
    """--mysql_database restricts both runs to one database of the config."""
    result = ws.run_job([ws.job_command(job_config, partitioned=True, mysql_database=DB),
                         ws.job_command(job_config, partitioned=False, mysql_database=DB)])
    assert_clean_job(result,
                     partitioned={t: v for t, v in CLEAN_PARTITIONED.items() if t.startswith(f"{DB}.")},
                     non_partitioned={t: v for t, v in CLEAN_NON_PARTITIONED.items() if t.startswith(f"{DB}.")})


def test_table_include_list_restricts_the_job(ws):
    """table_include_list "db.t1$,db.t2,db2.*" keeps exactly those tables in both runs."""
    config = ws.write_job_config("top_level_table_checksum_include.yaml",
                                 table_include_list=f"{DB}.fills$,{DB}.instruments,{DB2}.*")
    result = ws.run_job([ws.job_command(config, partitioned=True), ws.job_command(config, partitioned=False)])
    assert_clean_job(result,
                     partitioned={f"{DB}.fills": "MATCH", f"{DB2}.daily_marks": "MATCH"},
                     non_partitioned={f"{DB}.instruments": "MATCH", f"{DB2}.accounts": "MATCH",
                                      f"{DB2}.position_flags": "MATCH"})


def test_empty_partition_does_not_fail_the_job(ws, job_config):
    """A --partition_date whose partitions are all empty (tomorrow's) passes the job."""
    result = ws.run_job([ws.job_command(job_config, partitioned=True, partition_date=TOMORROW)])
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    assert checksummed_tables(result.log(JOB_LOG_PARTITIONED)) == sorted(JOB_PARTITIONED), result


def test_empty_partition_is_reported_empty_not_matched(ws, job_config):
    """Zero rows on both sides is verdict EMPTY, logged at INFO, never "No difference"
    (spec 13.06 D-13.06-5)."""
    result = ws.run_job([ws.job_command(job_config, partitioned=True, partition_date=TOMORROW)])
    assert result.returncode == 0, result
    assert parse_verdicts(result.log(JOB_LOG_PARTITIONED)) == {t: "EMPTY" for t in JOB_PARTITIONED}, result


def test_side_notes_reach_the_job_log_below_warning(ws, job_config):
    """The sides' coverage notes (a FLOAT column is not compared) are relayed into the job
    log as INFO side notes (spec 13.06 D-13.06-8), without the word WARNING (section 3.17)."""
    result = ws.run_job([ws.job_command(job_config, partitioned=False, mysql_database=DB)])
    assert result.returncode == 0, result
    notes = [line for line in result.all_logs.splitlines()
             if " side note: " in line and "Not compared in table pyops.instruments" in line and "'ratio'" in line]
    assert len(notes) == 2, result   # one per side
    assert all(" - INFO - " in line for line in notes), notes
    assert warning_lines(result.all_logs) == [], result


def test_planted_difference_fails_the_job(ws, job_config):
    """A value changed in ClickHouse is a "Checksum difference" WARNING, and the job verdict fails."""
    with planted(DB, "fills", "account", "id = 2", "PLANTED"):
        result = ws.run_job([ws.job_command(job_config, partitioned=True),
                             ws.job_command(job_config, partitioned=False)])
    assert result.returncode == 1, result
    assert parse_verdicts(result.log(JOB_LOG_PARTITIONED))[f"{DB}.fills"] == "DIFFERENT", result
    grep_lines = [line for line in result.output.splitlines() if line.startswith(JOB_LOG_PARTITIONED + ":")]
    assert len(grep_lines) == 1 and "Checksum difference" in grep_lines[0] and f"'{DB}.fills'" in grep_lines[0], result
    assert parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED)) == CLEAN_NON_PARTITIONED, result


def test_planted_difference_in_the_renamed_database_fails_the_job(ws, job_config):
    """The second database is compared with its ClickHouse copy under the overridden name."""
    with planted(DB2_CH, "accounts", "name", "id = 3", "PLANTED"):
        result = ws.run_job([ws.job_command(job_config, partitioned=False)])
    assert result.returncode == 1, result
    verdicts = parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED))
    assert verdicts == dict(CLEAN_NON_PARTITIONED, **{f"{DB2}.accounts": "DIFFERENT"}), result


def test_difference_in_an_ignored_column_passes_the_job(ws, job_config):
    """A difference only in a column of ignored_columns is not compared."""
    with planted(DB, "fills", "note", "id = 1", "PLANTED"):
        result = ws.run_job([ws.job_command(job_config, partitioned=True)])
    assert_clean_job(result, non_partitioned=None)


def test_clickhouse_side_failure_fails_the_job(ws, job_config):
    """A wrong password in clickhouse-client.xml: every ClickHouse side fails, the run exits
    non-zero and pipefail fails the job before its WARNING scan."""
    good = ws.ch_config.read_text()
    ws.write_clickhouse_client_config("clickhouse-client.xml", "not-the-password")
    try:
        result = ws.run_job([ws.job_command(job_config, partitioned=True)])
    finally:
        ws.ch_config.write_text(good)
    assert result.returncode != 0, result
    log = result.log(JOB_LOG_PARTITIONED)
    assert parse_verdicts(log) == {t: "ERROR" for t in JOB_PARTITIONED}, log
    assert "No difference" not in log and "EMPTY on both sides" not in log, log
