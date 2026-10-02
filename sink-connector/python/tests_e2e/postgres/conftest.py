"""Session fixtures for the PostgreSQL end-to-end suite of the Python toolset.

The suite drives the real command-line tools (``ch-pg-dump``, ``ch-checksum``,
``ch-pg-checksum``, ``ch-pg-count`` and the ``auto_diff`` phase of
``ch-checksum``) against a real PostgreSQL and a real ClickHouse.

Two modes:

* CI mode (default): start the ``postgres`` and ``clickhouse`` services of
  ``sink-connector-lightweight/docker/docker-compose-postgres.yml`` with
  ``docker compose`` and tear them down afterwards (``KEEP_STACK=1`` leaves them
  running so the workflow can collect logs).
* External-stack mode (``PYTOOLS_E2E_EXTERNAL_STACK=1``): use an already running
  PostgreSQL (``wal_level=logical``) and ClickHouse given by the
  ``PYTOOLS_E2E_PG_*`` / ``PYTOOLS_E2E_CH_*`` variables below.

The streaming connector is not started: the scenarios only need its logical
replication slot, which the suite creates exactly as the connector would
(``pg_create_logical_replication_slot(<slot.name>, <plugin.name>)`` with the
values of the connector config).

``ch-pg-dump`` shells out to ``psql`` and ``clickhouse-client``; both must be on
``PATH`` (the workflow takes ``clickhouse-client`` from the stack's ClickHouse
image).
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import psycopg2
import psycopg2.extras
import pytest
import yaml
from clickhouse_driver import Client

SUITE_DIR = Path(__file__).resolve().parent
PYTHON_ROOT = SUITE_DIR.parents[1]            # sink-connector/python
REPO_ROOT = SUITE_DIR.parents[3]
COMPOSE_DIR = REPO_ROOT / "sink-connector-lightweight" / "docker"
COMPOSE_FILE = COMPOSE_DIR / "docker-compose-postgres.yml"
CONNECTOR_CONFIG_TEMPLATE = COMPOSE_DIR / "config_postgres.yml"
SEED_SQL = SUITE_DIR / "sql" / "seed_postgres.sql"

EXTERNAL_STACK = os.environ.get("PYTOOLS_E2E_EXTERNAL_STACK") == "1"

# --- Endpoints (CI defaults are the ports docker-compose-postgres.yml publishes) ---
PG_HOST = os.environ.get("PYTOOLS_E2E_PG_HOST", "127.0.0.1")
PG_PORT = int(os.environ.get("PYTOOLS_E2E_PG_PORT", "5432"))
PG_USER = os.environ.get("PYTOOLS_E2E_PG_USER", "root")
PG_PASSWORD = os.environ.get("PYTOOLS_E2E_PG_PASSWORD", "root")
PG_DATABASE = os.environ.get("PYTOOLS_E2E_PG_DATABASE", "public")

CH_HOST = os.environ.get("PYTOOLS_E2E_CH_HOST", "127.0.0.1")
CH_PORT = int(os.environ.get("PYTOOLS_E2E_CH_PORT", "9000"))   # native protocol
CH_USER = os.environ.get("PYTOOLS_E2E_CH_USER", "root")
CH_PASSWORD = os.environ.get("PYTOOLS_E2E_CH_PASSWORD", "root")

CONNECTOR_IMAGE = os.environ.get(
    "CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE", "altinity/clickhouse-sink-connector:latest-lt"
)
COMPOSE_SERVICES = ["postgres", "clickhouse"]

# --- Names owned by this suite (everything it creates or drops starts with pye2e) ---
PG_SCHEMA = "pye2e"
SLOT_NAME = "pye2e_slot"
MISSING_SLOT_NAME = "pye2e_slot_missing"
CONNECTOR_NAME = "pye2e-pg-connector"
CH_DATABASE = "pye2e_pg"
OFFSET_DB = "pye2e_offsets"
OFFSET_TABLE = f"{OFFSET_DB}.replica_source_info"
CH_DB_PREFIX = "pye2e_"

# Tables compared by the verification scenarios (include regex '^t_').
VERIFIED_TABLES = ["t_events_nopk", "t_orders"]
INCLUDE_REGEX = "^t_"

TOOL_TIMEOUT = int(os.environ.get("PYTOOLS_E2E_TOOL_TIMEOUT", "600"))

# Tree the tools are run from (its ch_sink_tools package is put first on
# PYTHONPATH). Default: this repository. Point it at an extracted older tree to
# show which tests fail before a fix (see JUSTIFICATION.md).
# PYTOOLS_E2E_DUMP_TOOLS_ROOT overrides the tree for ch-pg-dump only, so the
# verification tools of one tree can be judged on data loaded by another.
TOOLS_ROOT = Path(os.environ.get("PYTOOLS_E2E_TOOLS_ROOT") or PYTHON_ROOT).resolve()
DUMP_TOOLS_ROOT = Path(os.environ.get("PYTOOLS_E2E_DUMP_TOOLS_ROOT") or TOOLS_ROOT).resolve()
PROBE_DIR = SUITE_DIR / "probe"

# A checksum user without SELECT on t_orders.note (created by the seed).
READER_USER = "pye2e_reader"
READER_PASSWORD = "pye2e"


# ---------------------------------------------------------------------------
# Generic helpers
# ---------------------------------------------------------------------------

def _wait_for(predicate, timeout, interval, description):
    deadline = time.time() + timeout
    last_err = None
    while time.time() < deadline:
        try:
            if predicate():
                return
        except Exception as e:  # not ready yet
            last_err = e
        time.sleep(interval)
    raise TimeoutError(
        f"Timed out after {timeout}s waiting for {description}. Last error: {last_err}"
    )


def pg_connect():
    conn = psycopg2.connect(
        host=PG_HOST, port=PG_PORT, user=PG_USER, password=PG_PASSWORD,
        dbname=PG_DATABASE, connect_timeout=5,
    )
    conn.autocommit = True
    return conn


def pg_query(sql, params=None):
    conn = pg_connect()
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            return cur.fetchall() if cur.description else []
    finally:
        conn.close()


def ch_client(database="default"):
    return Client(host=CH_HOST, port=CH_PORT, user=CH_USER, password=CH_PASSWORD,
                  database=database)


def ch_query(sql, params=None):
    client = ch_client()
    try:
        return client.execute(sql, params)
    finally:
        client.disconnect()


def ch_database_exists(name):
    return ch_query(f"EXISTS DATABASE `{name}`")[0][0] == 1


def drop_suite_ch_databases():
    """Drop every ClickHouse database this suite owns (name prefix pye2e_)."""
    for (name,) in ch_query(
            "SELECT name FROM system.databases WHERE startsWith(name, %(p)s)",
            {"p": CH_DB_PREFIX}):
        # DESTRUCTIVE: drops one ClickHouse database per iteration, bounded to
        # names starting with pye2e_ (the suite's own, selected by startsWith
        # above) on a disposable test stack; nothing else is touched.
        ch_query(f"DROP DATABASE IF EXISTS `{name}` SYNC")


def drop_slot_if_exists(slot_name):
    if pg_query("SELECT 1 FROM pg_replication_slots WHERE slot_name = %s", (slot_name,)):
        pg_query("SELECT pg_drop_replication_slot(%s)", (slot_name,))


def clone_ch_database(source_db, target_db, tables):
    """Copy *tables* of *source_db* (structure and rows) into a fresh *target_db*.

    Each negative scenario plants its divergence in its own clone, so the
    dumped database stays pristine and the tests stay order independent.
    """
    # DESTRUCTIVE: drops only the clone target, always a pye2e_pg_* name chosen
    # by a test (never the dumped source database), so a rerun starts clean.
    assert target_db.startswith(CH_DB_PREFIX) and target_db != source_db
    ch_query(f"DROP DATABASE IF EXISTS `{target_db}` SYNC")
    ch_query(f"CREATE DATABASE `{target_db}`")
    for t in tables:
        ch_query(f"CREATE TABLE `{target_db}`.`{t}` AS `{source_db}`.`{t}`")
        ch_query(f"INSERT INTO `{target_db}`.`{t}` SELECT * FROM `{source_db}`.`{t}`")


def tool_env(tools_root=None, extra_env=None, probe=False):
    env = os.environ.copy()
    # The tools must not pick up a developer's ~/.pgpass or PG* settings.
    for var in ("PGPASSWORD", "PGHOST", "PGPORT", "PGUSER", "PGDATABASE", "PGSERVICE",
                "PGTZ", "PGDATESTYLE", "PGOPTIONS"):
        env.pop(var, None)
    paths = ([str(PROBE_DIR)] if probe else []) + [str(tools_root or TOOLS_ROOT)]
    env["PYTHONPATH"] = os.pathsep.join(
        paths + ([env["PYTHONPATH"]] if env.get("PYTHONPATH") else [])
    )
    env["PYTHONUNBUFFERED"] = "1"
    env.update(extra_env or {})
    return env


def run_tool(module, args, tools_root=None, extra_env=None, probe=False):
    """Run ``python -m <module> <args>`` from *tools_root* (default TOOLS_ROOT).

    Returns (returncode, combined output). *extra_env* is applied last (for
    example PGTZ / PGDATESTYLE to give the tool hostile session defaults);
    *probe* puts the observation hook of probe/sitecustomize.py on the path.
    """
    root = Path(tools_root or TOOLS_ROOT)
    cmd = [sys.executable, "-m", module] + [str(a) for a in args]
    print(f"\n$ [{root}] {' '.join(cmd)}")
    try:
        proc = subprocess.run(
            cmd, cwd=str(root), env=tool_env(root, extra_env, probe),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            timeout=TOOL_TIMEOUT,
        )
    except subprocess.TimeoutExpired as e:
        raise TimeoutError(
            f"{module} did not finish within {TOOL_TIMEOUT}s; partial output:\n{e.output}"
        )
    print(proc.stdout)
    return proc.returncode, proc.stdout


# ---------------------------------------------------------------------------
# ch-checksum helpers
# ---------------------------------------------------------------------------

_SUMMARY_ROW = re.compile(
    r"^(?P<table>\S+)\s+(?P<tier>\d+)\s+(?P<pg>\S+)\s+(?P<ch>\S+)\s+(?P<delta>\S+)"
    r"\s+(?P<pct>\S+)\s+(?P<checksum>\S+)\s+(?P<status>PASS|WARN|FAIL|MISSING|EXTRA|ERROR)$"
)


def parse_checksum_summary(output):
    """Parse the ch-checksum summary table into {table: row dict}, RESULT, exit line."""
    rows, last = {}, None
    in_table = False
    result_line = exit_line = None
    for line in output.splitlines():
        if line.startswith("Table ") and "Checksum" in line and "Status" in line:
            in_table = True
            continue
        if line.startswith("RESULT:"):
            result_line = line
        if line.startswith("Exit code:"):
            exit_line = line
        if not in_table:
            continue
        if line.startswith("Tables:"):
            in_table = False
            continue
        m = _SUMMARY_ROW.match(line)
        if m:
            last = m.group("table")
            rows[last] = dict(m.groupdict(), detail="")
        elif line.strip().startswith("↳") and last:
            rows[last]["detail"] += line.strip()[1:].strip()
    return rows, result_line, exit_line


def write_checksum_config(path, ch_database, snapshot_mode=True, auto_diff_dir=None,
                          skip_tables=None, include_regex=INCLUDE_REGEX,
                          pg_user=PG_USER, pg_password=PG_PASSWORD, checksum_extra=None):
    checksum = {
        "snapshot_mode": snapshot_mode,
        "skip_tables": list(skip_tables or []),
        # Small enough that the 62-row table is checked in several PK chunks.
        "chunk_size": 25,
    }
    checksum.update(checksum_extra or {})
    if auto_diff_dir is not None:
        checksum["auto_diff"] = {
            "enabled": True,
            "output_dir": str(auto_diff_dir),
            "output_format": "json",
            "max_divergent_rows": 10,
            "num_chunks": 4,
            "per_row_threshold": 8,
        }
    config = {
        "source": {"postgres": {
            "host": PG_HOST, "port": PG_PORT, "database": PG_DATABASE,
            "schema": PG_SCHEMA, "user": pg_user, "password": pg_password,
            "table_include_list": include_regex,
        }},
        "clickhouse": {
            "host": CH_HOST, "port": CH_PORT, "database": ch_database,
            "user": CH_USER, "password": CH_PASSWORD,
        },
        # offset_table '' skips the LSN catch-up wait: the data was loaded by the
        # dumper, not streamed, so there is no connector offset to wait for.
        "connector": {"offset_db": OFFSET_DB, "offset_table": ""},
        "checksum": checksum,
    }
    Path(path).write_text(yaml.safe_dump(config, sort_keys=False))
    return Path(path)


def run_ch_checksum(config_path, *extra, probe=False):
    return run_tool("ch_sink_tools.db_compare.top_level_postgres_checksum",
                    ["--config", config_path, *extra], probe=probe)


def pg_tool_args():
    return ["--pg_host", PG_HOST, "--pg_port", PG_PORT, "--pg_user", PG_USER,
            "--pg_password", PG_PASSWORD, "--pg_database", PG_DATABASE,
            "--pg_schema", PG_SCHEMA]


# ---------------------------------------------------------------------------
# ch-pg-dump helpers
# ---------------------------------------------------------------------------

def connector_template():
    return yaml.safe_load(CONNECTOR_CONFIG_TEMPLATE.read_text())


def write_connector_config(path, slot_name, offset_table=OFFSET_TABLE, keys=None):
    """The stack's connector config (config_postgres.yml) pointed at this suite.

    ch-pg-dump reads it the way an operator would hand it over: endpoints,
    schema.include.list, slot.name, connector name and offset table all come
    from the connector's own keys.
    """
    config = connector_template()
    config.update({
        "name": CONNECTOR_NAME,
        "database.hostname": PG_HOST,
        "database.port": str(PG_PORT),
        "database.dbname": PG_DATABASE,
        "database.user": PG_USER,
        "database.password": PG_PASSWORD,
        "schema.include.list": PG_SCHEMA,
        "slot.name": slot_name,
        "clickhouse.server.url": CH_HOST,
        # Native port: the dumper's driver and clickhouse-client speak it.
        "clickhouse.server.port": str(CH_PORT),
        "clickhouse.server.user": CH_USER,
        "clickhouse.server.password": CH_PASSWORD,
        "offset.storage.jdbc.table.name": offset_table,
    })
    # Extra connector keys (they contain dots), e.g. {"table.include.list": ...}.
    config.update(keys or {})
    Path(path).write_text(yaml.safe_dump(config, sort_keys=False))
    return Path(path)


def run_pg_dump(config_path, ch_database, *extra, extra_env=None):
    return run_tool("ch_sink_tools.db_dump.postgres_dumper",
                    ["--config", config_path, "--ch_database", ch_database,
                     "--threads", "2", *extra],
                    tools_root=DUMP_TOOLS_ROOT, extra_env=extra_env)


def create_connector_slot(slot_name):
    """Create the logical slot exactly as the connector would (slot.name, plugin.name)."""
    plugin = connector_template().get("plugin.name", "pgoutput")
    drop_slot_if_exists(slot_name)
    return pg_query(
        "SELECT slot_name, lsn::text AS lsn FROM pg_create_logical_replication_slot(%s, %s)",
        (slot_name, plugin),
    )[0]


def lsn_to_int(lsn_text):
    hi, lo = lsn_text.split("/")
    return (int(hi, 16) << 32) + int(lo, 16)


# ---------------------------------------------------------------------------
# Stack lifecycle
# ---------------------------------------------------------------------------

def _compose_base():
    try:
        subprocess.run(["docker", "compose", "version"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return ["docker", "compose", "-f", str(COMPOSE_FILE)]
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ["docker-compose", "-f", str(COMPOSE_FILE)]


def _run_compose(*args, check=True):
    env = os.environ.copy()
    env["CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE"] = CONNECTOR_IMAGE
    return subprocess.run(_compose_base() + list(args), cwd=str(COMPOSE_DIR),
                          env=env, check=check)


def _wait_for_postgres(timeout=180):
    def ready():
        rows = pg_query("SHOW wal_level")
        if rows[0]["wal_level"] != "logical":
            raise RuntimeError(f"wal_level is {rows[0]['wal_level']}, need logical")
        return True
    _wait_for(ready, timeout, 3, "PostgreSQL (wal_level=logical) to accept connections")


def _wait_for_clickhouse(timeout=180):
    _wait_for(lambda: ch_query("SELECT 1")[0][0] == 1, timeout, 3,
              "ClickHouse to accept connections")


def _require_binaries():
    missing = [b for b in ("psql", "clickhouse-client") if shutil.which(b) is None]
    if missing:
        pytest.fail(
            f"ch-pg-dump needs {missing} on PATH (psql from a PostgreSQL client, "
            f"clickhouse-client from the ClickHouse image); refusing to run the suite "
            f"without them.", pytrace=False)


@pytest.fixture(scope="session")
def stack():
    """Bring up (or attach to) PostgreSQL + ClickHouse."""
    _require_binaries()
    started = False
    if not EXTERNAL_STACK:
        print(f"Starting compose services {COMPOSE_SERVICES} from {COMPOSE_FILE.name}")
        _run_compose("up", "-d", *COMPOSE_SERVICES)
        started = True
    try:
        _wait_for_postgres()
        _wait_for_clickhouse()
        yield
    finally:
        if started:
            if os.environ.get("KEEP_STACK") != "1":
                _run_compose("down", "-v", "--remove-orphans", check=False)
            else:
                print("KEEP_STACK=1 set; leaving compose stack running.")


@pytest.fixture(scope="session")
def seeded(stack):
    """Load the deterministic seed and clear anything a previous run left behind."""
    drop_slot_if_exists(SLOT_NAME)
    drop_slot_if_exists(MISSING_SLOT_NAME)
    drop_suite_ch_databases()
    conn = pg_connect()
    try:
        with conn.cursor() as cur:
            cur.execute(SEED_SQL.read_text())
            cur.execute(f"ANALYZE {PG_SCHEMA}.t_orders, {PG_SCHEMA}.t_events_nopk")
    finally:
        conn.close()
    yield
    drop_slot_if_exists(SLOT_NAME)


@pytest.fixture(scope="session")
def connector_slot(seeded):
    """The connector's logical replication slot, created before the snapshot."""
    return create_connector_slot(SLOT_NAME)


@pytest.fixture(scope="session")
def dump_run(connector_slot, tmp_path_factory):
    """Run ch-pg-dump once for the whole session (the snapshot scenario asserts on it)."""
    work = tmp_path_factory.mktemp("pg_dump")
    config = write_connector_config(work / "connector.yml", SLOT_NAME)
    lsn_before = lsn_to_int(pg_query("SELECT pg_current_wal_lsn()::text AS l")[0]["l"])
    rc, output = run_pg_dump(config, CH_DATABASE)
    lsn_after = lsn_to_int(pg_query("SELECT pg_current_wal_lsn()::text AS l")[0]["l"])
    return {
        "rc": rc, "output": output, "slot": connector_slot,
        "lsn_before": lsn_before, "lsn_after": lsn_after, "config": config,
    }


@pytest.fixture(scope="session")
def dumped(dump_run):
    """Verification scenarios need a successful dump; fail loudly otherwise."""
    if dump_run["rc"] != 0:
        pytest.fail(f"ch-pg-dump exited {dump_run['rc']}; verification scenarios "
                    f"cannot run. Output:\n{dump_run['output'][-4000:]}", pytrace=False)
    return dump_run


def load_json(path):
    return json.loads(Path(path).read_text())
