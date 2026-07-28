"""Pytest fixtures for the lightweight connector checksum integration test.

Brings up the sink-connector-lightweight docker-compose stack (MySQL + ClickHouse +
connector), waits until the seeded ``test`` database has snapshot-replicated into
ClickHouse, and tears the stack down afterwards.
"""

import os
import subprocess
import time
from pathlib import Path

import pymysql
import pytest
from clickhouse_driver import Client

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
