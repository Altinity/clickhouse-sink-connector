"""Dedicated end-to-end runs of the shapes the checksum fixes are about (spec 13.06 D-13.06-1
to -4, -10) and of one loader limit (spec 13.04 D-13.04-32).

The tables live under the temp_ prefix, outside the scheduled runs; each scenario runs the
production driver with the job's flags restricted to its table. JUSTIFICATION.md maps
every test to the fix it proves.
"""
import pytest

from mysql_e2e_support import (CH_HOST, DB, DB2, DB2_CH, DOLLAR_TABLE, DOLLAR_TABLE_REGEX, MYSQL_HOST,
                               JOB_LOG_NON_PARTITIONED, ch_query, parse_side_results, parse_verdicts, planted,
                               warning_lines)

SINGLE = "top_level_table_checksum_single.log"


def single_table_command(ws, config, table_regex, partitioned):
    """The job's command for one table: its flags, the table regex, nothing excluded but heartbeat."""
    # Every partition (no --partition_date): with it the ClickHouse side would compare the MySQL
    # expression TO_DAYS(...) with a date (spec 11.02 section 6 item 5).
    return ws.job_command(config, partitioned=partitioned, partition_date=None, mysql_database=DB,
                          tables_regex=table_regex, exclude_tables_regex="heartbeat", log_name=SINGLE)


@pytest.fixture(scope="module")
def pyops_config(ws):
    return ws.write_job_config("top_level_table_checksum_pyops.yaml", databases=[DB])


def test_checksum_in_column_names_does_not_confuse_the_verdict(ws, pyops_config):
    """Columns named checksum_* (one of them a FLOAT the sides note as not compared): the
    side output is parsed for exactly one result line (spec 13.06 D-13.06-2)."""
    result = ws.run_job([single_table_command(ws, pyops_config, "^temp_checksum_named$", partitioned=False)])
    assert result.returncode == 0, result
    assert parse_verdicts(result.log(SINGLE)) == {f"{DB}.temp_checksum_named": "MATCH"}, result
    assert sorted(c for (_, _, _, c) in parse_side_results(result.log(SINGLE))) == [2, 2], result


def test_function_partition_expression_reaches_the_sides(ws, pyops_config):
    """A RANGE (TO_DAYS(ev_date)) table: the partition expression is passed as one argument,
    not through a shell (spec 13.06 D-13.06-10)."""
    result = ws.run_job([single_table_command(ws, pyops_config, "^temp_events_by_days$", partitioned=True)])
    assert result.returncode == 0, result
    assert parse_verdicts(result.log(SINGLE)) == {f"{DB}.temp_events_by_days": "MATCH"}, result


def test_dollar_table_is_compared_under_its_own_name(ws, pyops_config):
    """`temp_fx$rates`: the sides compare that table, not a shell expansion of `$rates`
    (spec 13.06 D-13.06-3). The connector created it in ClickHouse but wrote its rows to
    `temp_fx_rates`, so the honest verdict is DIFFERENT: 2 rows in MySQL, 0 in ClickHouse."""
    assert ch_query(f"SELECT count() FROM `{DB}`.`{DOLLAR_TABLE}`")[0][0] == 0
    result = ws.run_job([single_table_command(ws, pyops_config, DOLLAR_TABLE_REGEX, partitioned=False)])
    sides = {(host, table): count for (host, table, _, count) in parse_side_results(result.log(SINGLE))}
    assert sides == {(MYSQL_HOST, f"{DB}.{DOLLAR_TABLE}"): 2, (CH_HOST, f"{DB}.{DOLLAR_TABLE}"): 0}, result
    assert parse_verdicts(result.log(SINGLE)) == {f"{DB}.{DOLLAR_TABLE}": "DIFFERENT"}, result
    assert result.returncode == 1, result


def test_equal_mysql_and_clickhouse_host_strings_still_give_verdicts(ws):
    """The source result is identified by position, not by host string (spec 13.06 D-13.06-4)."""
    config = ws.write_job_config("top_level_table_checksum_same_host.yaml", databases=[DB2], ch_host=MYSQL_HOST)
    result = ws.run_job([ws.job_command(config, partitioned=False)])
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    assert parse_verdicts(result.log(JOB_LOG_NON_PARTITIONED)) == {f"{DB2}.accounts": "MATCH",
                                                                   f"{DB2}.position_flags": "MATCH"}, result


PACKAGED_DRIVER = ["-m", "ch_sink_tools.db_compare.top_level_table_checksum"]


def run_packaged_driver(ws, cwd, config):
    return ws.run([ws.python, *PACKAGED_DRIVER, "--config_file", ws.ws / config, "--defaults_file", ws.my_cnf,
                   "--mysql_database", DB2, "--tables_regex", "^accounts$", "--threads", "2"],
                  cwd=cwd, env={"PYTHONPATH": str(ws.ws)})


def test_packaged_driver_run_from_another_directory_reports_a_difference(ws):
    """ch-mysql-checksum (the packaged driver) run outside the tool tree compares with its own
    side modules: MATCH on clean data, DIFFERENT for a planted value, never a false
    "No difference" from two sides that did not run (spec 13.06 D-13.06-1)."""
    cwd = ws.root / "elsewhere"
    cwd.mkdir(exist_ok=True)
    (cwd / "clickhouse-client.xml").write_text(ws.ch_config.read_text())
    config = ws.write_job_config("top_level_table_checksum_packaged.yaml", databases=[DB2], where_overrides={})
    clean = run_packaged_driver(ws, cwd, config)
    assert clean.returncode == 0, clean
    assert parse_verdicts(clean.output) == {f"{DB2}.accounts": "MATCH"}, clean
    with planted(DB2_CH, "accounts", "name", "id = 1", "PLANTED"):
        diverged = run_packaged_driver(ws, cwd, config)
    assert parse_verdicts(diverged.output) == {f"{DB2}.accounts": "DIFFERENT"}, diverged


@pytest.mark.xfail(strict=True, reason="D-13.04-32: the loader takes table names from MySQL Shell's percent-encoded "
                                       "file names (temp_fx%24rates) and does not quote them; the load aborts")
def test_loader_loads_a_table_with_a_dollar_in_its_name(ws):
    dump_dir = ws.ws / "dump_dollar"
    dump = ws.py("db_dump/mysql_dumper.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                 "--defaults_file", ".my.cnf", "--dump_dir", dump_dir, "--include_tables_regex", DOLLAR_TABLE_REGEX)
    assert dump.returncode == 0, dump
    database = "pyops_dollar"
    # DESTRUCTIVE: drops only this test's scratch database (pyops_dollar) on the disposable
    # e2e ClickHouse, before and after the load; nothing else is touched.
    ch_query(f"DROP DATABASE IF EXISTS `{database}`")
    try:
        load = ws.py("db_load/clickhouse_loader.py", "--clickhouse_host", CH_HOST, "--clickhouse_config_file",
                     "clickhouse-client.xml", "--clickhouse_database", database, "--mysql_source_database", DB,
                     "--dump_dir", dump_dir, "--threads", "2", "--mysqlshell", "--rmt_delete_support")
        assert load.returncode == 0, load
        assert ch_query(f"SELECT count() FROM `{database}`.`{DOLLAR_TABLE}`")[0][0] == 2
    finally:
        # DESTRUCTIVE: the same scratch database, removed after the test.
        ch_query(f"DROP DATABASE IF EXISTS `{database}`")
