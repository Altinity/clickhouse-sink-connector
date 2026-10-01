"""Settings and helpers of the Python toolset MySQL end-to-end suite (fixtures in conftest.py).

The suite runs the MySQL tools of sink-connector/python the way they are used in
production, against a real MySQL, a real ClickHouse and the lightweight connector:

* the scheduled checksum job (a bash script with ``set -euo pipefail`` that
  sources ``install.sh``, runs ``db_compare/top_level_table_checksum.py`` twice
  through ``tee`` and fails on any log line containing WARNING);
* the owning DBA's manual recipes (the two side scripts with ``--no_wc
  --debug_output`` and a diff of their per-row files, the two count runners);
* the snapshot path (``db_dump/mysql_dumper.py`` -> ``snapshot_position.json``
  -> ``db_load/clickhouse_loader.py`` into a fresh database) and the ad hoc
  patching path (``ch-mysql-resync dump`` / ``patch --apply`` / ``rewind-sql``).

Every tool runs from a **workspace**: a copy of the tool tree in a temporary
directory, with ``.my.cnf`` and ``clickhouse-client.xml`` written in it, exactly
as the job runs it. ``PYTOOLS_E2E_TOOLS_ROOT`` selects the tool tree (default:
this repository's ``sink-connector/python``); JUSTIFICATION.md uses it to run the
suite against the pre-fix tools.

Two modes:

* CI mode (default). ``docker compose`` starts the stack of this directory's
  ``docker-compose.yml`` (MySQL, ClickHouse and ZooKeeper from
  ``sink-connector-lightweight/docker``, the connector with
  ``connector-config.yml``): MySQL first, then the seed, then the connector,
  which snapshots the seed. ``KEEP_STACK=1`` leaves the stack up (the workflow
  collects its logs and tears it down). ``clickhouse-client`` is copied out of
  the ClickHouse container and ``mysqlsh`` runs in the MySQL image
  (``mysqlsh-docker.sh``).
* External-stack mode (``PYTOOLS_E2E_EXTERNAL_STACK=1``). Nothing is started;
  the endpoints come from the environment, ``clickhouse-client`` and ``mysqlsh``
  from ``PYTOOLS_E2E_CLICKHOUSE_CLIENT`` / ``PYTOOLS_E2E_MYSQLSH`` or ``PATH``.
  ``PYTOOLS_E2E_SEED=1`` seeds MySQL first (default 0: the stack was seeded
  before its connector started, as in CI).

The tools are installed by ``source ./install.sh`` in the workspace (a venv with
``requirements.txt``). ``PYTOOLS_E2E_PYTHON=<venv>/bin/python`` uses that venv
instead, with the ``PYTHONPATH`` install.sh sets (used to run the suite under a
given SQLAlchemy version).

The checksum drivers forward neither the MySQL nor the ClickHouse port to their
side scripts (spec 13.06 D-13.06-15): MySQL must be on 3306 and ClickHouse native
on 9000 at the configured hosts, as in production; the session refuses to start
otherwise.

Environment (defaults match the compose stack): ``PYTOOLS_E2E_MYSQL_HOST``
(127.0.0.1), ``PYTOOLS_E2E_MYSQL_PORT`` (3306), ``PYTOOLS_E2E_MYSQL_USER`` /
``PYTOOLS_E2E_MYSQL_PASSWORD`` (root/root), ``PYTOOLS_E2E_CH_HOST``
(localhost: a host string other than MySQL's, see spec 13.06 D-13.06-4),
``PYTOOLS_E2E_CH_PORT`` (9000), ``PYTOOLS_E2E_CH_USER`` /
``PYTOOLS_E2E_CH_PASSWORD`` (root/root), ``PYTOOLS_E2E_CH_CONTAINER``
(clickhouse), ``PYTOOLS_E2E_MYSQL_IMAGE`` (mysql:8.0),
``CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE`` (connector image for compose).
"""

import contextlib
import datetime as dt
import decimal
import json
import os
import re
import shlex
import shutil
import string
import subprocess
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

import pymysql
import pytest
from clickhouse_driver import Client

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[3]
PYTHON_DIR = REPO_ROOT / "sink-connector" / "python"
COMPOSE_FILE = HERE / "docker-compose.yml"
SEED_SQL = HERE / "seed_mysql.sql"
MYSQLSH_WRAPPER = HERE / "mysqlsh-docker.sh"

TOOLS_ROOT = Path(os.environ.get("PYTOOLS_E2E_TOOLS_ROOT", str(PYTHON_DIR))).resolve()
TOOLS_PYTHON = os.environ.get("PYTOOLS_E2E_PYTHON")

EXTERNAL_STACK = os.environ.get("PYTOOLS_E2E_EXTERNAL_STACK") == "1"
SEED = os.environ.get("PYTOOLS_E2E_SEED", "0" if EXTERNAL_STACK else "1") == "1"

MYSQL_HOST = os.environ.get("PYTOOLS_E2E_MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = int(os.environ.get("PYTOOLS_E2E_MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("PYTOOLS_E2E_MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("PYTOOLS_E2E_MYSQL_PASSWORD", "root")
CH_HOST = os.environ.get("PYTOOLS_E2E_CH_HOST", "localhost")
CH_PORT = int(os.environ.get("PYTOOLS_E2E_CH_PORT", "9000"))
CH_USER = os.environ.get("PYTOOLS_E2E_CH_USER", "root")
CH_PASSWORD = os.environ.get("PYTOOLS_E2E_CH_PASSWORD", "root")
CH_CONTAINER = os.environ.get("PYTOOLS_E2E_CH_CONTAINER", "clickhouse")
MYSQL_IMAGE = os.environ.get("PYTOOLS_E2E_MYSQL_IMAGE", "mysql:8.0")
CONNECTOR_IMAGE = os.environ.get("CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE", "altinity/clickhouse-sink-connector:latest-lt")

# What connector-config.yml replicates and how; the tools are told the same.
DB = "pyops"                      # written to ClickHouse under the same name
DB2 = "pyref"                     # written to ClickHouse as DB2_CH
DB2_CH = "pyref_ch"               # clickhouse.database.override.map "pyref:pyref_ch"
SOURCE_TIMEZONE = "UTC"           # database.connectionTimeZone
BINARY_ENCODING = "base64"        # binary.handling.mode
OFFSET_TABLE = "altinity_sink_connector.replica_source_info"   # offset.storage.jdbc.table.name
SNAPSHOT_DB = "pyops_snapshot"    # the fresh database clickhouse_loader fills from the dump
WINDOW_ZONE = ZoneInfo("America/Chicago")   # the bitemporal where's CONVERT_TZ zone

# (database, table) seeded and replicated; ClickHouse database via ch_database().
PARTITIONED = [(DB, "fills"), (DB, "positions_bt"), (DB, "quotes"), (DB, "temp_x"),
               (DB, "temp_events_by_days"), (DB2, "daily_marks")]
NON_PARTITIONED = [(DB, "instruments"), (DB, "keyless_events"), (DB, "ledger"), (DB, "temp_fx$rates"),
                   (DB, "temp_checksum_named"), (DB, "fills_p1"), (DB, "heartbeat"), (DB, "temp_resync_guard"),
                   (DB2, "accounts")]
SEEDED_TABLES = PARTITIONED + NON_PARTITIONED
# The connector creates `temp_fx$rates` in ClickHouse but writes its rows to
# `temp_fx_rates` (the name with `$` replaced): the `$` table stays empty there,
# which the checksum must report (test_mysql_05_justification).
DOLLAR_TABLE = "temp_fx$rates"
DOLLAR_TABLE_REGEX = "^temp_fx[$]rates$"
REPLICATED_TABLES = [(d, t) for (d, t) in SEEDED_TABLES if t != DOLLAR_TABLE]

# What the scheduled jobs cover: the partitioned run (--include_partitions_regex
# "p.*", exclude "(temp|no_partition|heartbeat)") and the non-partitioned run
# (exclude "(temp|_p[0-9]|no_partition|heartbeat)").
JOB_PARTITIONED = [f"{DB}.fills", f"{DB}.positions_bt", f"{DB}.quotes", f"{DB2}.daily_marks"]
JOB_NON_PARTITIONED = [f"{DB}.instruments", f"{DB}.keyless_events", f"{DB}.ledger", f"{DB2}.accounts"]
JOB_EXCLUDED = [f"{DB}.temp_x", f"{DB}.temp_events_by_days", f"{DB}.temp_fx$rates", f"{DB}.temp_checksum_named",
                f"{DB}.temp_resync_guard", f"{DB}.heartbeat", f"{DB}.fills_p1"]
IGNORED_COLUMNS = [f"{DB}.instruments.attrs", f"{DB}.fills.note"]
BITEMPORAL_WHERE = ("db_from >= /*!50000 CONVERT_TZ( */ TIMESTAMP(CONCAT(DATE_SUB({partition_expression}, "
                    "INTERVAL 1 DAY), ' 16:30:00')) /*!50000 , 'America/Chicago', 'UTC') */")

TOOL_TIMEOUT = int(os.environ.get("PYTOOLS_E2E_TOOL_TIMEOUT", "900"))
REPLICATION_TIMEOUT = int(os.environ.get("PYTOOLS_E2E_REPLICATION_TIMEOUT", "600"))

COMPOSE_SERVICES_MYSQL = ["mysql-master"]
COMPOSE_SERVICES_CONNECTOR = ["clickhouse-sink-connector-lt"]

JOB_LOG_PARTITIONED = "top_level_table_checksum_pa.log"
JOB_LOG_NON_PARTITIONED = "top_level_table_checksum_non_partititioned.log"


def ch_database(database):
    return DB2_CH if database == DB2 else database


# --------------------------------------------------------------------------------------------------
# Run dates: one RANGE COLUMNS partition per day, named p<yyyymmdd>
# --------------------------------------------------------------------------------------------------
TODAY = dt.date.today()
YESTERDAY = TODAY - dt.timedelta(days=1)
TOMORROW = TODAY + dt.timedelta(days=1)


def bitemporal_window_open(partition_day):
    """The UTC instant the bitemporal where opens for a partition day: (day - 1) 16:30 America/Chicago."""
    local = dt.datetime.combine(partition_day - dt.timedelta(days=1), dt.time(16, 30), tzinfo=WINDOW_ZONE)
    return local.astimezone(dt.timezone.utc).replace(tzinfo=None)


def seed_values():
    fmt6 = "%Y-%m-%d %H:%M:%S.%f"
    opens = bitemporal_window_open(YESTERDAY)
    return {
        "Y": YESTERDAY.isoformat(), "T": TODAY.isoformat(), "N": TOMORROW.isoformat(),
        "N2": (TOMORROW + dt.timedelta(days=1)).isoformat(), "Y3": (YESTERDAY - dt.timedelta(days=3)).isoformat(),
        "YK": f"{YESTERDAY:%Y%m%d}", "TK": f"{TODAY:%Y%m%d}", "NK": f"{TOMORROW:%Y%m%d}",
        # inside the window: the first microsecond, and two hours later
        "BT_INSIDE_FIRST": opens.strftime(fmt6),
        "BT_INSIDE_LATER": (opens + dt.timedelta(hours=2)).strftime(fmt6),
        # outside: the last microsecond before it, and 16:30 read as UTC (inside only
        # if a side ignored the America/Chicago conversion)
        "BT_OUTSIDE_LAST": (opens - dt.timedelta(microseconds=1)).strftime(fmt6),
        "BT_OUTSIDE_UTC_WALL": dt.datetime.combine(YESTERDAY - dt.timedelta(days=1), dt.time(16, 30, 0, 500000)).strftime(fmt6),
    }


# Rows of pyops.positions_bt in partition YESTERDAY that the where keeps.
BITEMPORAL_ROWS_IN_WINDOW = 3


def render_seed():
    return string.Template(SEED_SQL.read_text(encoding="utf-8")).safe_substitute(seed_values())


def seed_statements():
    lines = [line for line in render_seed().splitlines() if not line.lstrip().startswith("--")]
    return [s.strip() for s in re.split(r";\s*$", "\n".join(lines), flags=re.M) if s.strip()]


# --------------------------------------------------------------------------------------------------
# Connections and bounded waits
# --------------------------------------------------------------------------------------------------
def mysql_connection(database=None):
    return pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER, password=MYSQL_PASSWORD,
                           database=database, connect_timeout=5, autocommit=True, charset="utf8mb4")


def mysql_query(sql, database=None, unlogged=False):
    """Rows of one statement. ``unlogged`` runs it with sql_log_bin=0: the change
    never reaches the binlog, so the connector never sees it."""
    conn = mysql_connection(database)
    try:
        with conn.cursor() as cur:
            cur.execute("SET SESSION time_zone = '+00:00'")
            if unlogged:
                cur.execute("SET SESSION sql_log_bin = 0")
            cur.execute(sql)
            return cur.fetchall()
    finally:
        conn.close()


def ch_query(sql, params=None, settings=None):
    client = Client(host=CH_HOST, port=CH_PORT, user=CH_USER, password=CH_PASSWORD, connect_timeout=5)
    try:
        return client.execute(sql, params, settings=settings or {})
    finally:
        client.disconnect()


def ch_mutate(sql):
    """An ALTER ... UPDATE/DELETE, waited for (the data is changed when it returns)."""
    return ch_query(sql, settings={"mutations_sync": 2})


def ch_insert(sql, rows):
    """An INSERT ... VALUES with the rows sent by the native protocol."""
    return ch_query(sql, params=rows)


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
    raise TimeoutError(f"Timed out after {timeout}s waiting for {description}. Last error: {last_err}")


def _wait_for_mysql(timeout=300):
    _wait_for(lambda: mysql_query("SELECT 1") == ((1,),), timeout, 3,
              f"MySQL at {MYSQL_HOST}:{MYSQL_PORT} to accept connections")


def _wait_for_clickhouse(timeout=300):
    _wait_for(lambda: ch_query("SELECT 1") == [(1,)], timeout, 3,
              f"ClickHouse at {CH_HOST}:{CH_PORT} to accept connections")


def seed_mysql():
    conn = mysql_connection()
    try:
        with conn.cursor() as cur:
            for statement in seed_statements():
                cur.execute(statement)
    finally:
        conn.close()


def mysql_count(database, table):
    return mysql_query(f"SELECT count(*) FROM `{database}`.`{table}`")[0][0]


def clickhouse_live_count(database, table):
    return ch_query(f"SELECT count() FROM `{database}`.`{table}` FINAL WHERE is_deleted = 0")[0][0]


def wait_for_replication(tables=REPLICATED_TABLES, timeout=REPLICATION_TIMEOUT, interval=5):
    """Block until every (database, table) exists in ClickHouse with its MySQL row count."""
    expected = {(d, t): mysql_count(d, t) for (d, t) in tables}

    def replicated():
        pending = {}
        for (d, t) in tables:
            chd = ch_database(d)
            if ch_query(f"EXISTS TABLE `{chd}`.`{t}`")[0][0] != 1:
                pending[f"{chd}.{t}"] = "table-missing"
                continue
            count = clickhouse_live_count(chd, t)
            if count != expected[(d, t)]:
                pending[f"{chd}.{t}"] = f"{count}/{expected[(d, t)]}"
        if pending:
            print(f"[wait_for_replication] pending: {pending}")
        return not pending

    _wait_for(replicated, timeout, interval, f"replication parity of {tables}")


def binlog_position_key(file_name, position):
    """Sortable (sequence, position) of a binlog coordinate (binlog.000042, 157)."""
    return (int(file_name.rsplit(".", 1)[1]), int(position))


def mysql_binlog_position():
    row = mysql_query("SHOW MASTER STATUS")[0]
    return row[0], int(row[1])


def connector_offset_rows():
    """[(offset_key, offset_val dict)] of the connector's durable offsets."""
    rows = ch_query(f"SELECT offset_key, offset_val FROM {OFFSET_TABLE} FINAL ORDER BY offset_key")
    return [(key, json.loads(val)) for (key, val) in rows]


def wait_for_connector_offset_at_or_past(file_name, position, timeout=REPLICATION_TIMEOUT):
    """Write heartbeats until the connector's durable offset reaches the binlog coordinate; return its row."""
    target = binlog_position_key(file_name, position)

    def reached():
        mysql_query(f"INSERT INTO `{DB}`.heartbeat (note) VALUES ('offset')")
        rows = connector_offset_rows()
        return len(rows) == 1 and "file" in rows[0][1] and \
            binlog_position_key(rows[0][1]["file"], rows[0][1]["pos"]) >= target

    _wait_for(reached, timeout, 3, f"the connector offset in {OFFSET_TABLE} to reach {file_name}:{position}")
    return connector_offset_rows()[0]


def wait_for_clickhouse_value(sql, expected, timeout=REPLICATION_TIMEOUT, description=None):
    _wait_for(lambda: ch_query(sql) == expected, timeout, 2, description or f"{sql} -> {expected}")


@contextlib.contextmanager
def planted(database, table, column, where, value):
    """Change one ClickHouse value behind the connector's back (a real divergence the
    checksum must report), and put the original value back afterwards."""
    original = ch_query(f"SELECT {column} FROM `{database}`.`{table}` FINAL WHERE {where}")
    assert len(original) == 1, f"{database}.{table} WHERE {where} must select one row, got {original}"
    ch_mutate(f"ALTER TABLE `{database}`.`{table}` UPDATE {column} = {sql_literal(value)} WHERE {where}")
    try:
        yield original[0][0]
    finally:
        ch_mutate(f"ALTER TABLE `{database}`.`{table}` UPDATE {column} = {sql_literal(original[0][0])} WHERE {where}")


def sql_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, (int, float, decimal.Decimal)) and not isinstance(value, bool):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


# --------------------------------------------------------------------------------------------------
# Compose (CI mode)
# --------------------------------------------------------------------------------------------------
def _compose_base():
    try:
        subprocess.run(["docker", "compose", "version"], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
        return ["docker", "compose", "-f", str(COMPOSE_FILE)]
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ["docker-compose", "-f", str(COMPOSE_FILE)]


def _run_compose(*args, check=True):
    env = dict(os.environ, CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=CONNECTOR_IMAGE)
    return subprocess.run(_compose_base() + list(args), cwd=str(HERE), env=env, check=check)


# --------------------------------------------------------------------------------------------------
# Tool output parsers
# --------------------------------------------------------------------------------------------------
VERDICT_PATTERNS = [
    ("MATCH", re.compile(r" - INFO - .* - No difference for (?P<table>\S+)\s*$")),
    # "Checksum difference : <replica result> to <source result>"; the source names the MySQL table.
    ("DIFFERENT", re.compile(r" - WARNING - .* - Checksum difference : \(.*\) to \('[^']*', '(?P<table>[^']+)'")),
    ("EMPTY", re.compile(r" - INFO - .* - EMPTY on both sides for (?P<table>\S+): 0 rows compared")),
    ("ERROR", re.compile(r" - ERROR - .* - Checksum ERROR for (?P<table>\S+): ")),
]
# "('<host>', '<db>.<table>', '<md5>', <count>) in 0.123 seconds": one side's result.
SIDE_RESULT_RE = re.compile(r" - INFO - .* - \('(?P<host>[^']*)', '(?P<table>[^']+)', '(?P<md5>[0-9a-f]{32})', "
                            r"(?P<count>\d+)\) in [0-9.]+ seconds")
CHECKSUM_LINE_RE = re.compile(r"Checksum for table (?P<table>\S+) = (?P<md5>[0-9a-f]{32}) count (?P<count>\d+)\s*$")
COUNT_RE = re.compile(r"Count for table (?P<table>\S+) = (?P<count>\d+)\s*$")


def parse_verdicts(output):
    """{<db.table>: verdict} from a checksum driver's log (last verdict per table wins)."""
    verdicts = {}
    for line in output.splitlines():
        for verdict, pattern in VERDICT_PATTERNS:
            match = pattern.search(line)
            if match:
                verdicts[match.group("table")] = verdict
    return verdicts


def parse_side_results(output):
    """[(host, <db.table>, md5, count)] of every side result the driver logged."""
    return [(m.group("host"), m.group("table"), m.group("md5"), int(m.group("count")))
            for m in (SIDE_RESULT_RE.search(line) for line in output.splitlines()) if m]


def parse_checksum_lines(output):
    return [(m.group("table"), m.group("md5"), int(m.group("count")))
            for m in (CHECKSUM_LINE_RE.search(line) for line in output.splitlines()) if m]


def parse_counts(output):
    counts = {}
    for line in output.splitlines():
        match = COUNT_RE.search(line)
        if match:
            counts[match.group("table")] = int(match.group("count"))
    return counts


def checksummed_tables(output):
    """Tables the driver started on ("Checksumming <db.table>")."""
    return sorted(set(re.findall(r" - Checksumming (\S+)\s*$", output, flags=re.M)))


def warning_lines(output):
    """The lines the scheduled job's `grep -Hn "WARNING"` would print."""
    return [line for line in output.splitlines() if "WARNING" in line]


# --------------------------------------------------------------------------------------------------
# Tool processes
# --------------------------------------------------------------------------------------------------
class ToolResult:
    def __init__(self, argv, returncode, output):
        self.argv = argv
        self.returncode = returncode
        self.output = output

    def __repr__(self):
        return f"ToolResult(rc={self.returncode}, argv={self.argv})\n{self.output[-6000:]}"


class JobResult(ToolResult):
    """A scheduled-job script run: its exit code, console output and the tee'd logs."""

    def __init__(self, argv, returncode, output, logs):
        super().__init__(argv, returncode, output)
        self.logs = logs

    def log(self, name):
        return self.logs.get(name, "")

    @property
    def all_logs(self):
        return "\n".join(self.logs.values())

    def __repr__(self):
        return f"JobResult(rc={self.returncode})\n{self.output[-8000:]}"


IGNORE_IN_WORKSPACE = shutil.ignore_patterns(".venv", "__pycache__", "*.pyc", "tests_e2e", "*.egg-info", ".pytest_cache")


class Workspace:
    """A copy of the tool tree with the job's credential files, the way production runs the tools.

    ``root/ws`` is the copied tree (the job's cwd), ``root/bin`` holds
    clickhouse-client and mysqlsh, ``root/tmp`` is TMPDIR (mysql_dumper writes its
    MySQL Shell statement file there, inside the directory the mysqlsh container sees)."""

    def __init__(self, root):
        self.root = Path(root)
        self.ws = self.root / "ws"
        shutil.copytree(TOOLS_ROOT, self.ws, ignore=IGNORE_IN_WORKSPACE)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        (self.root / "tmp").mkdir()
        self.env = dict(os.environ)
        self.env.pop("PYTHONPATH", None)
        self.env["TMPDIR"] = str(self.root / "tmp")
        self._install_programs()
        self.env["PATH"] = os.pathsep.join([str(self.bin_dir), self.env.get("PATH", "")])
        self.my_cnf = self._write(".my.cnf", f"[client]\nuser={MYSQL_USER}\npassword={MYSQL_PASSWORD}\n")
        self.ch_config = self.write_clickhouse_client_config("clickhouse-client.xml", CH_PASSWORD)
        self.venv_bin = self._install_tools()
        self.python = str(Path(self.venv_bin) / "python")
        self.tool_env = dict(self.env, PATH=os.pathsep.join([self.venv_bin, self.env["PATH"]]), PYTHONPATH=".")
        for program in ("clickhouse-client", "mysqlsh", "zstd"):
            if shutil.which(program, path=self.tool_env["PATH"]) is None:
                pytest.fail(f"{program} is not on PATH for the tools ({self.tool_env['PATH']})")

    # --- set-up ---------------------------------------------------------------------------------
    def _write(self, name, text):
        path = self.ws / name
        path.write_text(text, encoding="utf-8")
        return path

    def write_clickhouse_client_config(self, name, password):
        return self._write(name, f"<config>\n  <user>{CH_USER}</user>\n  <password>{password}</password>\n</config>\n")

    def _install_programs(self):
        client = os.environ.get("PYTOOLS_E2E_CLICKHOUSE_CLIENT")
        if client:
            os.symlink(client, self.bin_dir / "clickhouse-client")
        elif not EXTERNAL_STACK:
            # The server image's multi-call binary, so the client matches the server.
            subprocess.run(["docker", "cp", f"{CH_CONTAINER}:/usr/bin/clickhouse", str(self.bin_dir / "clickhouse")],
                           check=True, timeout=TOOL_TIMEOUT)
            os.symlink("clickhouse", self.bin_dir / "clickhouse-client")
        mysqlsh = os.environ.get("PYTOOLS_E2E_MYSQLSH")
        if mysqlsh:
            os.symlink(mysqlsh, self.bin_dir / "mysqlsh")
        elif not EXTERNAL_STACK:
            os.symlink(MYSQLSH_WRAPPER, self.bin_dir / "mysqlsh")
            self.env.setdefault("PYTOOLS_E2E_MYSQL_IMAGE", MYSQL_IMAGE)
            self.env.setdefault("PYTOOLS_E2E_MOUNT", str(self.root))

    def job_prelude(self):
        """How the job gets the tools: ``source ./install.sh`` (a venv with
        requirements.txt and PYTHONPATH=.), or a given venv with the same PYTHONPATH."""
        if TOOLS_PYTHON:
            return (f'export PATH={shlex.quote(str(Path(TOOLS_PYTHON).parent))}:"$PATH"\n'
                    'export PYTHONPATH="${PYTHONPATH:-}":.\n')
        return "source ./install.sh\n"

    def _install_tools(self):
        if TOOLS_PYTHON:
            return str(Path(TOOLS_PYTHON).parent)
        result = self.bash("set -euo pipefail\n" + self.job_prelude() + "python -c 'import sqlalchemy, yaml, pymysql'\n",
                           timeout=TOOL_TIMEOUT)
        if result.returncode != 0:
            pytest.fail(f"source ./install.sh failed in the workspace:\n{result.output[-4000:]}")
        return str(self.ws / ".venv" / "bin")

    # --- running ---------------------------------------------------------------------------------
    def bash(self, script, timeout=TOOL_TIMEOUT, env=None):
        run_env = dict(self.env)
        run_env.update(env or {})
        print(f"\n$ (cd {self.ws}) bash <<'EOF'\n{script}EOF")
        proc = subprocess.run(["bash", "-c", script], cwd=str(self.ws), env=run_env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout, text=True)
        print(proc.stdout[-20000:])
        print(f"[exit {proc.returncode}]")
        return ToolResult(["bash", "-c", script], proc.returncode, proc.stdout)

    def run(self, argv, cwd=None, env=None, timeout=TOOL_TIMEOUT, stdin=None):
        run_env = dict(self.tool_env)
        run_env.update(env or {})
        argv = [str(a) for a in argv]
        print(f"\n$ (cd {cwd or self.ws}) {shlex.join(argv)}")
        proc = subprocess.run(argv, cwd=str(cwd or self.ws), env=run_env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout, input=stdin, text=True)
        print(proc.stdout[-20000:])
        print(f"[exit {proc.returncode}]")
        return ToolResult(argv, proc.returncode, proc.stdout)

    def py(self, *argv, **kwargs):
        """``python <argv>`` from the workspace, with the environment install.sh sets."""
        return self.run([self.python, *argv], **kwargs)

    def supports(self, flag, *program):
        """True when ``python <program> --help`` lists ``flag``. The pre-fix tools lack
        some flags the fixes added; a test leaves such a flag out there, so the
        pre-fix tool's own behaviour is measured rather than an argparse error."""
        help_text = self.py(*program, "--help").output
        return flag in help_text

    # --- the scheduled checksum job --------------------------------------------------------------
    def write_job_config(self, name, databases=(DB, DB2), mysql_host=MYSQL_HOST, ch_host=CH_HOST,
                         override_map=f"{DB2}:{DB2_CH}", table_include_list=None, ignored_columns=IGNORED_COLUMNS,
                         where_overrides=None):
        """The job's YAML config (JSON is valid YAML)."""
        mysql = {"host": mysql_host, "databases": list(databases), "source_timezone": SOURCE_TIMEZONE,
                 "ignored_columns": list(ignored_columns)}
        if table_include_list:
            mysql["table_include_list"] = table_include_list
        overrides = {f"{DB}.positions_bt": BITEMPORAL_WHERE} if where_overrides is None else where_overrides
        mysql["tables"] = [{table: {"where": where}} for table, where in overrides.items()]
        replica = {"host": ch_host}
        if override_map:
            replica["database_override_map"] = override_map
        config = {"source": {"mysql": mysql}, "replicas": [{"clickhouse": replica}]}
        self._write(name, json.dumps(config, indent=2) + "\n")
        return name

    def job_command(self, config, partitioned, partition_date=YESTERDAY, mysql_database=None, debug=False,
                    tables_regex=".", exclude_tables_regex=None, log_name=None):
        """One of the job's two checksum commands, piped through tee as the job does."""
        argv = ["python", "db_compare/top_level_table_checksum.py", "--defaults_file", ".my.cnf", "--config", config,
                "--binary_encoding", BINARY_ENCODING, "--source_timezone", SOURCE_TIMEZONE, "--tables_regex", tables_regex]
        if partitioned:
            argv += ["--include_partitions_regex", "p.*"]
            if partition_date is not None:
                argv += ["--partition_date", f"{partition_date:%Y/%m/%d}"]
            argv += ["--threads", "8", "--threads_per_table", "16",
                     "--exclude_tables_regex", exclude_tables_regex or "(temp|no_partition|heartbeat)"]
            log_name = log_name or JOB_LOG_PARTITIONED
        else:
            argv += ["--non_partitioned_tables_only", "--lock_tables_on_source", "--sleep_after_lock", "3",
                     "--threads", "8", "--threads_per_table", "16",
                     "--exclude_tables_regex", exclude_tables_regex or "(temp|_p[0-9]|no_partition|heartbeat)"]
            log_name = log_name or JOB_LOG_NON_PARTITIONED
        if debug:
            argv.append("--debug")
        if mysql_database:
            argv += ["--mysql_database", mysql_database]
        return f"{shlex.join(argv)} | tee {log_name}\n"

    def run_job(self, commands, verdict=True):
        """Run the job script: set -euo pipefail, the tools' environment, the commands, then
        the job's verdict (fail on any WARNING line of the tee'd logs)."""
        for stale in self.ws.glob("top_level_table_checksum_*.log"):
            stale.unlink()
        script = "set -euo pipefail\n" + self.job_prelude() + "".join(commands)
        if verdict:
            script += 'if grep -Hn "WARNING" top_level_table_checksum_*.log; then exit 1; fi\n'
        result = self.bash(script)
        logs = {p.name: p.read_text(encoding="utf-8", errors="replace")
                for p in sorted(self.ws.glob("top_level_table_checksum_*.log"))}
        return JobResult(result.argv, result.returncode, result.output, logs)
