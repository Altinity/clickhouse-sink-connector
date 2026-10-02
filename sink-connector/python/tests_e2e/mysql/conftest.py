"""Session fixtures of the Python toolset MySQL end-to-end suite.

The settings, the helpers and the two modes (CI compose stack, or an external
stack with PYTOOLS_E2E_EXTERNAL_STACK=1) are described in mysql_e2e_support.py.
The helpers live in that uniquely named module, not here, so the test modules
can import them even when several suites with their own conftest.py are
collected in one pytest run.
"""
import os
from types import SimpleNamespace

import pytest

from mysql_e2e_support import (BINARY_ENCODING, CH_HOST, CH_PORT, CONNECTOR_IMAGE, COMPOSE_SERVICES_CONNECTOR,
                               COMPOSE_SERVICES_MYSQL, DB, DOLLAR_TABLE_REGEX, EXTERNAL_STACK, MYSQL_HOST, MYSQL_PORT, OFFSET_TABLE,
                               REPLICATION_TIMEOUT, SEED, SNAPSHOT_DB, SOURCE_TIMEZONE, TOOLS_PYTHON, TOOLS_ROOT,
                               Workspace, _run_compose, _wait_for, _wait_for_clickhouse, _wait_for_mysql, ch_query,
                               connector_offset_rows, mysql_binlog_position, seed_mysql, wait_for_replication)


@pytest.fixture(scope="session")
def stack():
    """The running stack with the seed replicated into ClickHouse."""
    if MYSQL_PORT != 3306 or CH_PORT != 9000:
        pytest.fail("the checksum drivers do not forward ports to their side scripts (spec 13.06 D-13.06-15): "
                    f"serve MySQL on 3306 and ClickHouse native on 9000 (got {MYSQL_PORT} and {CH_PORT})")
    print(f"Tools under test: {TOOLS_ROOT} (python: {TOOLS_PYTHON or 'source ./install.sh'})")
    started = False
    try:
        if EXTERNAL_STACK:
            print(f"External stack: MySQL {MYSQL_HOST}:{MYSQL_PORT}, ClickHouse {CH_HOST}:{CH_PORT}")
            _wait_for_mysql()
            _wait_for_clickhouse()
            if SEED:
                seed_mysql()
        else:
            print(f"Starting compose stack with connector image: {CONNECTOR_IMAGE}")
            started = True
            _run_compose("up", "-d", *COMPOSE_SERVICES_MYSQL)
            _wait_for_mysql()
            seed_mysql()
            _run_compose("up", "-d", *COMPOSE_SERVICES_CONNECTOR)
            _wait_for_clickhouse()
        wait_for_replication()
        _wait_for(lambda: len(connector_offset_rows()) == 1, REPLICATION_TIMEOUT, 5,
                  f"one connector offset row in {OFFSET_TABLE}")
        yield
    finally:
        if started and os.environ.get("KEEP_STACK") != "1":
            _run_compose("down", "-v", "--remove-orphans", check=False)
        elif started:
            print("KEEP_STACK=1 set; leaving compose stack running.")


@pytest.fixture(scope="session")
def ws(stack, tmp_path_factory):
    """The job workspace: a copy of the tool tree with .my.cnf and clickhouse-client.xml."""
    return Workspace(tmp_path_factory.mktemp("pytools"))


@pytest.fixture(scope="session")
def mysql_snapshot(ws):
    """db_dump/mysql_dumper.py: a consistent MySQL Shell dump of the first database, with the
    source binlog positions read just before and just after it. The `$` table is left out:
    the loader cannot load it (spec 13.04 D-13.04-32, test_mysql_05_justification)."""
    dump_dir = ws.ws / f"dump_{DB}"
    before = mysql_binlog_position()
    result = ws.py("db_dump/mysql_dumper.py", "--mysql_host", MYSQL_HOST, "--mysql_database", DB,
                   "--defaults_file", ".my.cnf", "--dump_dir", dump_dir, "--threads", "4",
                   "--exclude_tables_regex", DOLLAR_TABLE_REGEX)
    after = mysql_binlog_position()
    return SimpleNamespace(dump_dir=dump_dir, result=result, before=before, after=after,
                           position_file=dump_dir / "snapshot_position.json")


@pytest.fixture(scope="session")
def snapshot_load(ws, mysql_snapshot):
    """db_load/clickhouse_loader.py: the dump loaded into a fresh ClickHouse database, told the
    connector's settings (binary.handling.mode base64, clickhouse.datetime.timezone UTC, the
    is_deleted engine of the connector's tables)."""
    assert mysql_snapshot.result.returncode == 0, mysql_snapshot.result
    # DESTRUCTIVE: drops only the suite's own scratch database (pyops_snapshot) on the
    # disposable e2e ClickHouse, so the loader fills a FRESH database; nothing else is touched.
    ch_query(f"DROP DATABASE IF EXISTS `{SNAPSHOT_DB}`")
    loader = ["db_load/clickhouse_loader.py"]
    argv = loader + ["--clickhouse_host", CH_HOST, "--clickhouse_config_file", "clickhouse-client.xml",
                     "--clickhouse_database", SNAPSHOT_DB, "--mysql_source_database", DB,
                     "--dump_dir", mysql_snapshot.dump_dir, "--threads", "4", "--mysqlshell", "--rmt_delete_support",
                     "--clickhouse_datetime_timezone", SOURCE_TIMEZONE]
    if ws.supports("--binary_handling_mode", *loader):
        argv += ["--binary_handling_mode", BINARY_ENCODING]
    return ws.py(*argv)
