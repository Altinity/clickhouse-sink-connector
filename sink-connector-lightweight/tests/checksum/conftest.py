"""Pytest fixtures for the lightweight connector checksum integration test.

Brings up the sink-connector-lightweight docker-compose stack (MySQL + ClickHouse +
connector), waits until the seeded ``test`` database has snapshot-replicated into
ClickHouse, and tears the stack down afterwards.
"""

import os
import platform
import subprocess
import time
from pathlib import Path

import pymysql
import pytest
from clickhouse_driver import Client

_HOST_ARCH = platform.machine().lower()
_IS_ARM = _HOST_ARCH in ("arm64", "aarch64")

# --- Connection settings (host-side; ports are published by docker-compose) ---
MYSQL_HOST = os.environ.get("MYSQL_HOST", "localhost")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "root")

CLICKHOUSE_HOST = os.environ.get("CLICKHOUSE_HOST", "localhost")
CLICKHOUSE_NATIVE_PORT = int(os.environ.get("CLICKHOUSE_NATIVE_PORT", "9000"))
CLICKHOUSE_USER = os.environ.get("CLICKHOUSE_USER", "root")
CLICKHOUSE_PASSWORD = os.environ.get("CLICKHOUSE_PASSWORD", "root")

DATABASE = os.environ.get("CHECKSUM_DATABASE", "test")

# Connector image; overridable so CI can point at the freshly built -lt image.
CONNECTOR_IMAGE = os.environ.get(
    "CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE", "altinity/clickhouse-sink-connector:latest-lt"
)

# Tables from sink-connector-lightweight/sql/init_mysql.sql that are verified.
# employees_predated / sbtest1 / ferrari are intentionally excluded to keep the
# comparison deterministic.
REPLICATED_TABLES = [
    "employees",
    "customers",
    "products",
    "orders",
    "orderdetails",
    "payments",
    "offices",
    "productlines",
]

# Only these compose services are started (their depends_on pulls in clickhouse +
# zookeeper), which skips the prometheus/grafana/jmx monitoring services.
COMPOSE_SERVICES = ["mysql-master", "clickhouse-sink-connector-lt"]

# --- sysbench data-generation settings (used by the sysbench checksum test) ---
# Default image is severalnines/sysbench (sysbench 1.0.17, oltp_legacy) as requested.
# That image is amd64-only and segfaults under qemu on Apple Silicon, so on arm64
# hosts we fall back to a multi-arch image with modern sysbench flags. Override
# any of these via env (SYSBENCH_IMAGE / SYSBENCH_PLATFORM / SYSBENCH_LEGACY).
if os.environ.get("SYSBENCH_IMAGE"):
    SYSBENCH_IMAGE = os.environ["SYSBENCH_IMAGE"]
elif _IS_ARM:
    SYSBENCH_IMAGE = "zyclonite/sysbench:1.0.21"
else:
    SYSBENCH_IMAGE = "severalnines/sysbench"

# severalnines is amd64-only and needs an explicit platform pin on multi-arch hosts.
# Set SYSBENCH_PLATFORM="" to disable the pin (required for native arm64 images).
_DEFAULT_PLATFORM = "linux/amd64" if "severalnines" in SYSBENCH_IMAGE else ""
SYSBENCH_PLATFORM = os.environ.get("SYSBENCH_PLATFORM", _DEFAULT_PLATFORM)

# Legacy = oltp_legacy parallel_prepare.lua + --oltp-tables-count/--oltp-table-size.
# Modern = oltp_read_write + --tables/--table-size (sysbench >= 1.0.20).
if os.environ.get("SYSBENCH_LEGACY") is not None:
    SYSBENCH_LEGACY = os.environ.get("SYSBENCH_LEGACY", "0") == "1"
else:
    SYSBENCH_LEGACY = "severalnines" in SYSBENCH_IMAGE

SYSBENCH_TABLE_COUNT = int(os.environ.get("SYSBENCH_TABLE_COUNT", "4"))
SYSBENCH_TABLE_SIZE = int(os.environ.get("SYSBENCH_TABLE_SIZE", "10000"))
SYSBENCH_THREADS = int(os.environ.get("SYSBENCH_THREADS", "4"))
# sysbench writes into the `test` database so the connector's database.include.list
# (test) captures the tables with no config change. Tables are sbtest1..sbtestN.
SYSBENCH_TABLES = [f"sbtest{i}" for i in range(1, SYSBENCH_TABLE_COUNT + 1)]

REPO_ROOT = Path(__file__).resolve().parents[3]
COMPOSE_DIR = REPO_ROOT / "sink-connector-lightweight" / "docker"
COMPOSE_FILE = COMPOSE_DIR / "docker-compose.yml"


def _compose_base():
    """Return the docker compose invocation prefix (v2 preferred, v1 fallback)."""
    try:
        subprocess.run(
            ["docker", "compose", "version"],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return ["docker", "compose", "-f", str(COMPOSE_FILE)]
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ["docker-compose", "-f", str(COMPOSE_FILE)]


def _compose_env():
    env = os.environ.copy()
    env["CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE"] = CONNECTOR_IMAGE
    return env


def _run_compose(*args, check=True):
    return subprocess.run(
        _compose_base() + list(args),
        cwd=str(COMPOSE_DIR),
        env=_compose_env(),
        check=check,
    )


def mysql_connection():
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=DATABASE,
        connect_timeout=5,
    )


def clickhouse_client():
    return Client(
        host=CLICKHOUSE_HOST,
        port=CLICKHOUSE_NATIVE_PORT,
        user=CLICKHOUSE_USER,
        password=CLICKHOUSE_PASSWORD,
        database=DATABASE,
    )


def mysql_count(conn, table):
    with conn.cursor() as cur:
        cur.execute(f"SELECT count(*) FROM `{DATABASE}`.`{table}`")
        return cur.fetchone()[0]


def clickhouse_table_exists(client, table):
    return client.execute(f"EXISTS TABLE `{DATABASE}`.`{table}`")[0][0] == 1


def clickhouse_count(client, table):
    """Deduplicated live-row count. Falls back to plain FINAL if is_deleted absent."""
    try:
        return client.execute(
            f"SELECT count() FROM `{DATABASE}`.`{table}` FINAL WHERE is_deleted = 0"
        )[0][0]
    except Exception:
        return client.execute(
            f"SELECT count() FROM `{DATABASE}`.`{table}` FINAL"
        )[0][0]


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


def _wait_for_mysql(timeout=180):
    def ready():
        conn = mysql_connection()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
            return True
        finally:
            conn.close()

    _wait_for(ready, timeout, 5, "MySQL to accept connections")


def _wait_for_clickhouse(timeout=180):
    def ready():
        client = clickhouse_client()
        try:
            return client.execute("SELECT 1")[0][0] == 1
        finally:
            client.disconnect()

    _wait_for(ready, timeout, 5, "ClickHouse to accept connections")


def wait_for_replication(tables, timeout=600, interval=10):
    """Block until every table exists in ClickHouse with row count == MySQL."""
    mysql_conn = mysql_connection()
    client = clickhouse_client()
    try:
        expected = {t: mysql_count(mysql_conn, t) for t in tables}

        def replicated():
            pending = {}
            for t in tables:
                if not clickhouse_table_exists(client, t):
                    pending[t] = "table-missing"
                    continue
                ch = clickhouse_count(client, t)
                if ch != expected[t]:
                    pending[t] = f"{ch}/{expected[t]}"
            if pending:
                print(f"[wait_for_replication] pending: {pending}")
                return False
            return True

        _wait_for(
            replicated,
            timeout,
            interval,
            f"replication parity of tables {tables} (expected counts {expected})",
        )
    finally:
        mysql_conn.close()
        client.disconnect()


@pytest.fixture(scope="session")
def replicated_stack():
    """Start the compose stack, wait for snapshot replication, then tear down."""
    print(f"Starting compose stack with connector image: {CONNECTOR_IMAGE}")
    _run_compose("up", "-d", *COMPOSE_SERVICES)
    try:
        _wait_for_mysql()
        _wait_for_clickhouse()
        wait_for_replication(REPLICATED_TABLES)
        yield
    finally:
        if os.environ.get("KEEP_STACK") != "1":
            _run_compose("down", "-v", "--remove-orphans", check=False)
        else:
            print("KEEP_STACK=1 set; leaving compose stack running.")


def _sysbench_cmd(action):
    """Build a `docker run ... sysbench <lua> <action>` command.

    Joins the running mysql-master container's network namespace so the DB is
    reachable at 127.0.0.1 regardless of the compose project/network name.
    """
    # Force entrypoint to `sysbench` so this works for both severalnines
    # (Entrypoint=null / Cmd=bash) and images that already ENTRYPOINT sysbench
    # (e.g. zyclonite) without double-invoking the binary.
    cmd = ["docker", "run", "--rm", "--entrypoint", "sysbench"]
    if SYSBENCH_PLATFORM:
        cmd.extend(["--platform", SYSBENCH_PLATFORM])
    cmd.extend([
        "--network", "container:mysql-master",
        SYSBENCH_IMAGE,
    ])
    if SYSBENCH_LEGACY:
        # severalnines/sysbench 1.0.17: oltp_legacy parallel_prepare.lua.
        # One event creates ALL oltp_tables_count tables (sbtest1..N) in a single
        # pass. With --events>1 the next pass re-CREATEs sbtest1 (MySQL 1050).
        # Match the Hub example: single-threaded prepare, exactly one event, and
        # disable the time limit so prepare stops after that one pass.
        cmd.extend([
            "--db-driver=mysql",
            "--mysql-host=127.0.0.1",
            "--mysql-port=3306",
            f"--mysql-user={MYSQL_USER}",
            f"--mysql-password={MYSQL_PASSWORD}",
            f"--mysql-db={DATABASE}",
            f"--oltp-tables-count={SYSBENCH_TABLE_COUNT}",
            f"--oltp-table-size={SYSBENCH_TABLE_SIZE}",
            "--threads=1",
            "--events=1",
            "--max-requests=1",
            "--time=0",
            "/usr/share/sysbench/tests/include/oltp_legacy/parallel_prepare.lua",
            action,
        ])
    else:
        # Modern sysbench (>=1.0.20): oltp_read_write prepare/cleanup
        cmd.extend([
            "oltp_read_write",
            "--db-driver=mysql",
            "--mysql-host=127.0.0.1",
            "--mysql-port=3306",
            f"--mysql-user={MYSQL_USER}",
            f"--mysql-password={MYSQL_PASSWORD}",
            f"--mysql-db={DATABASE}",
            f"--tables={SYSBENCH_TABLE_COUNT}",
            f"--table-size={SYSBENCH_TABLE_SIZE}",
            f"--threads={SYSBENCH_THREADS}",
            action,
        ])
    return cmd


def _run_sysbench_prepare():
    """Create + populate sbtest1..N in the `test` DB via the sysbench image.

    A cleanup pass runs first (ignoring failures) so reruns against a reused
    mysql-master container are idempotent. Legacy severalnines uses ``run`` as
    the prepare action; modern sysbench uses ``prepare``.
    """
    prepare_action = "run" if SYSBENCH_LEGACY else "prepare"
    print(
        f"Running sysbench prepare: image={SYSBENCH_IMAGE} "
        f"platform={SYSBENCH_PLATFORM or 'native'} legacy={SYSBENCH_LEGACY} "
        f"tables={SYSBENCH_TABLE_COUNT} size={SYSBENCH_TABLE_SIZE} db={DATABASE}"
    )
    subprocess.run(_sysbench_cmd("cleanup"), check=False)
    proc = subprocess.run(_sysbench_cmd(prepare_action), check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(
            f"sysbench {prepare_action} failed ({proc.returncode}):\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    if proc.stdout:
        print(proc.stdout)


@pytest.fixture(scope="session")
def sysbench_stack():
    """Generate data with sysbench (snapshot path), then start the connector.

    Order: mysql-master -> sysbench prepare into `test` -> connector (pulls
    clickhouse + zookeeper) -> wait for the sbtest tables to replicate. This
    mirrors the deterministic snapshot semantics of ``replicated_stack`` but
    sources the data from sysbench instead of init_mysql.sql.
    """
    print(f"Starting sysbench stack with connector image: {CONNECTOR_IMAGE}")
    _run_compose("up", "-d", "mysql-master")
    try:
        _wait_for_mysql()
        _run_sysbench_prepare()
        _run_compose("up", "-d", "clickhouse-sink-connector-lt")
        _wait_for_clickhouse()
        wait_for_replication(SYSBENCH_TABLES)
        yield
    finally:
        if os.environ.get("KEEP_STACK") != "1":
            _run_compose("down", "-v", "--remove-orphans", check=False)
        else:
            print("KEEP_STACK=1 set; leaving compose stack running.")
