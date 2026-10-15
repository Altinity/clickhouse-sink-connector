"""A replica that declares non-Nullable the columns MySQL declares nullable (spec 13.06 D-13.06-40).

``pyref.position_flags`` has two columns MySQL declares nullable that never hold NULL: a
STORED generated column (``is_valid TINYINT(1) GENERATED ALWAYS AS (qty > 0) STORED``,
IS_NULLABLE = 'YES') and an explicitly nullable varchar. The connector auto-creates both as
Nullable; this module then declares their ClickHouse twins non-Nullable (``Int8``,
``String``), the way hand-written replica DDL does. The values stay equal, so the scheduled
job and the manual recipe must report the table equal, and a real change must still be
DIFFERENT. Before the fix each side built the trailing null flags over the columns its own
catalog declares nullable, so every row hashed differently and the job failed on clean data.

The module runs last and restores nothing: with the fix the non-Nullable twins MATCH, so a
re-run of the earlier modules against the same stack is unaffected.
"""
import pytest

from mysql_e2e_support import (CH_HOST, DB2, DB2_CH, JOB_LOG_NON_PARTITIONED, JOB_NON_PARTITIONED, MYSQL_HOST,
                               REPLICATION_TIMEOUT, _wait_for, ch_mutate, ch_query, checksummed_tables, mysql_query,
                               parse_checksum_lines, parse_side_results, parse_verdicts, planted, wait_for_replication,
                               warning_lines)

TABLE = "position_flags"
NON_NULLABLE_TWINS = {"label": "String", "is_valid": "Int8"}
CLEAN_NON_PARTITIONED = {t: "MATCH" for t in JOB_NON_PARTITIONED}
CH_EXCLUDE = ["_version", "is_deleted", "_is_deleted", "__is_deleted"]
MYSQL_EXCLUDE = ["_sign", "_version", "is_deleted", "_is_deleted", "__is_deleted"]
# The canonical row strings of the seed: id#qty#label#is_valid, then one null flag per
# compared column (four), the same on both sides.
EXPECTED_ROWS = ["1#10#alpha#1#0000", "2#0#beta#0#0000", "3#-5#gamma#0#0000", "4#7##1#0000", "5#1#epsilon#1#0000"]


def clickhouse_column_types():
    return dict(ch_query("SELECT name, type FROM system.columns WHERE database = %(db)s AND table = %(t)s",
                         {"db": DB2_CH, "t": TABLE}))


def pending_mutations():
    return ch_query("SELECT count() FROM system.mutations WHERE database = %(db)s AND table = %(t)s AND is_done = 0",
                    {"db": DB2_CH, "t": TABLE})[0][0]


@pytest.fixture(scope="module")
def non_nullable_twins(stack):
    """Once the replica is in sync, declare the ClickHouse twins of the two nullable,
    never-NULL MySQL columns non-Nullable, and wait for the mutations to finish."""
    nullability = dict(mysql_query(
        f"SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.columns WHERE table_schema = '{DB2}' "
        f"AND table_name = '{TABLE}' AND COLUMN_NAME IN ('label', 'is_valid')"))
    assert nullability == {"label": "YES", "is_valid": "YES"}, nullability
    assert mysql_query(f"SELECT count(*) FROM `{DB2}`.`{TABLE}` WHERE label IS NULL OR is_valid IS NULL") == ((0,),)
    wait_for_replication([(DB2, TABLE)])
    for column, ch_type in NON_NULLABLE_TWINS.items():
        ch_mutate(f"ALTER TABLE `{DB2_CH}`.`{TABLE}` MODIFY COLUMN `{column}` {ch_type}")
    _wait_for(lambda: pending_mutations() == 0, REPLICATION_TIMEOUT, 2,
              f"the MODIFY COLUMN mutations of {DB2_CH}.{TABLE} to finish (system.mutations is_done)")
    types = clickhouse_column_types()
    assert {c: types.get(c) for c in NON_NULLABLE_TWINS} == NON_NULLABLE_TWINS, types


@pytest.fixture(scope="module")
def job_config(ws):
    return ws.write_job_config("top_level_table_checksum_null_flags.yaml")


def test_job_matches_a_replica_with_non_nullable_twins(ws, job_config, non_nullable_twins):
    """The non-partitioned run of the scheduled job: MATCH for the table, no WARNING line,
    and the job passes."""
    result = ws.run_job([ws.job_command(job_config, partitioned=False)])
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    log = result.log(JOB_LOG_NON_PARTITIONED)
    assert parse_verdicts(log) == CLEAN_NON_PARTITIONED, log
    assert checksummed_tables(log) == sorted(CLEAN_NON_PARTITIONED), log
    sides = {(host, table): count for (host, table, _, count) in parse_side_results(log) if table.endswith(f".{TABLE}")}
    assert sides == {(MYSQL_HOST, f"{DB2}.{TABLE}"): 5, (CH_HOST, f"{DB2_CH}.{TABLE}"): 5}, log


def clickhouse_recipe(ws):
    return ws.py("db_compare/clickhouse_table_checksum.py", "--clickhouse_host", CH_HOST, "--clickhouse_database", DB2_CH,
                 "--tables_regex", TABLE, "--no_wc", "--clickhouse_config_file", "clickhouse-client.xml",
                 "--sign_column", "", "--exclude_columns", *CH_EXCLUDE, "--debug_output")


def mysql_recipe(ws):
    return ws.py("db_compare/mysql_table_checksum.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB2,
                 "--tables_regex", TABLE, "--no_wc", "--defaults_file", ".my.cnf",
                 "--exclude_columns", *MYSQL_EXCLUDE, "--debug_output")


def test_manual_recipe_is_equal_for_a_replica_with_non_nullable_twins(ws, non_nullable_twins):
    """The owning DBA's recipe (test_mysql_02_manual_recipes) on the whole table: equal
    checksum lines, an empty diff of the sorted per-row files, and every row string ends
    with the same four null flags on both sides."""
    for side in ("mysql", "ch"):
        (ws.ws / f"out.{TABLE}.{side}.txt").unlink(missing_ok=True)
    ch = clickhouse_recipe(ws)
    my = mysql_recipe(ws)
    assert ch.returncode == 0, ch
    assert my.returncode == 0, my
    (ch_lines, my_lines) = (parse_checksum_lines(ch.output), parse_checksum_lines(my.output))
    assert len(ch_lines) == 1 and len(my_lines) == 1, (ch, my)
    assert ch_lines[0] == (f"{DB2_CH}.{TABLE}", my_lines[0][1], 5) and my_lines[0][2] == 5, (ch, my)
    diff = ws.bash(f"set -uo pipefail\nsort out.{TABLE}.mysql.txt > out.{TABLE}.mysql.sorted\n"
                   f"sort out.{TABLE}.ch.txt > out.{TABLE}.ch.sorted\n"
                   f"diff out.{TABLE}.mysql.sorted out.{TABLE}.ch.sorted\n")
    assert diff.returncode == 0 and diff.output.strip() == "", diff
    for side in ("mysql", "ch"):
        rows = sorted((ws.ws / f"out.{TABLE}.{side}.txt").read_text().splitlines())
        assert rows == EXPECTED_ROWS, (side, rows)


def test_planted_difference_in_a_replica_with_non_nullable_twins_fails_the_job(ws, job_config, non_nullable_twins):
    """A value changed in ClickHouse is still a "Checksum difference" and fails the job."""
    with planted(DB2_CH, TABLE, "label", "id = 2", "PLANTED"):
        result = ws.run_job([ws.job_command(job_config, partitioned=False)])
    assert result.returncode == 1, result
    verdicts = parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED))
    assert verdicts == dict(CLEAN_NON_PARTITIONED, **{f"{DB2}.{TABLE}": "DIFFERENT"}), result


def test_null_against_the_non_nullable_default_fails_the_job(ws, job_config, non_nullable_twins):
    """A real NULL in MySQL against the empty string the non-Nullable replica holds renders
    as '' on both sides: only the null flag differs, and it is still a difference. The
    MySQL change is unlogged (the connector never sees it) and undone afterwards."""
    assert ch_query(f"SELECT label FROM `{DB2_CH}`.`{TABLE}` FINAL WHERE id = 4") == [("",)]
    mysql_query(f"UPDATE `{DB2}`.`{TABLE}` SET label = NULL WHERE id = 4", unlogged=True)
    try:
        result = ws.run_job([ws.job_command(job_config, partitioned=False, mysql_database=DB2)])
    finally:
        mysql_query(f"UPDATE `{DB2}`.`{TABLE}` SET label = '' WHERE id = 4", unlogged=True)
    assert result.returncode == 1, result
    assert parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED)) == {
        f"{DB2}.accounts": "MATCH", f"{DB2}.{TABLE}": "DIFFERENT"}, result
