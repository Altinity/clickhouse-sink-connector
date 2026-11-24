"""Live streaming contracts, using the existing disposable compose stack.

Specification: MySQL committed rows define the replica's FINAL state. Late
commits must remain visible; rolled-back writes must not appear;
offline update/delete events must survive connector restarts;
and binary values in an added column must survive a schema-cache refresh.
Every checkpoint verifies named values and a nonempty, explicitly selected table
checksum, not merely row counts. Restart recovery does NOT prove that a specific
binlog event was redelivered, nor reproduce all ordering defects.

Run only in the disposable E2E workflow or an approved isolated Docker sandbox.
Tables are uniquely owned by each test and left for replicated_stack teardown.
"""

import base64
import json
import os
import re
import subprocess
import time
import uuid
from contextlib import ExitStack
from pathlib import Path

import pytest
import yaml

import conftest as stack
from test_checksum_replication import _clickhouse_checksums, _mysql_checksums


CONNECTOR = "clickhouse-sink-connector-lt"  # Existing compose service name.
SERVICES = ("mysql-master", "clickhouse", CONNECTOR)
QUERY_TIMEOUT = 10
WAIT_TIMEOUT = 120


def _require(condition, message):
    if not condition:
        pytest.fail(message, pytrace=False)


def _local_settings():
    """Refuse endpoint overrides before even requesting replicated_stack."""
    for host in (stack.MYSQL_HOST, stack.CLICKHOUSE_HOST):
        _require(host in ("localhost", "127.0.0.1", "::1"),
                 "Streaming writes require loopback endpoints of the managed stack")
    _require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", stack.DATABASE),
             "Streaming tests require a simple captured database identifier")
    _require(not os.environ.get("DOCKER_HOST")
             and not os.environ.get("DOCKER_CONTEXT"),
             "Streaming tests refuse Docker endpoint/context overrides")


def _process(command, timeout=30):
    # Do not print Docker config/inspect output: environment entries carry secrets.
    try:
        result = subprocess.run(command, cwd=stack.COMPOSE_DIR,
                                env=stack._compose_env(), capture_output=True,
                                text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pytest.fail("Managed-stack command failed or timed out", pytrace=False)
    _require(result.returncode == 0, "Managed-stack command failed")
    return result.stdout


def _compose(*args, timeout=30):
    return _process(["docker", "compose", "-f", str(stack.COMPOSE_FILE), *args],
                    timeout=timeout)


def _preflight():
    _local_settings()
    context = json.loads(_process(["docker", "context", "inspect"]))
    _require(len(context) == 1
             and context[0]["Endpoints"]["docker"]["Host"].startswith("unix://"),
             "Streaming tests require a local Docker daemon")
    config = json.loads(_compose("config", "--format", "json"))
    services = config["services"]
    _require(all(name in services for name in SERVICES),
             "Managed compose services are missing")
    _require(services["mysql-master"]["environment"]["MYSQL_DATABASE"] == stack.DATABASE
             and services["clickhouse"]["environment"]["CLICKHOUSE_DB"] == stack.DATABASE,
             "Checksum database is not the database owned by this stack")
    with (stack.COMPOSE_DIR / "config.yml").open() as source:
        connector = yaml.safe_load(source)
    _require(connector["database.include.list"] == stack.DATABASE
             and not connector.get("table.include.list")
             and not connector.get("table.exclude.list")
             and not connector.get("clickhouse.database.override.map")
             and connector["database.hostname"] == "mysql-master"
             and connector["clickhouse.server.url"] == "clickhouse",
             "Streaming tables must be captured into the same managed database")
    # Checksums below use the existing default base64 representation, not an
    # invented binary convention. Refuse configurations with different semantics.
    _require(str(connector.get("binary.handling.mode", "base64")) == "base64"
             and str(connector.get("persist.raw.bytes", "false")).lower() == "false"
             and str(connector.get("ignore_delete", "false")).lower() == "false",
             "Streaming tests require base64 binary encoding and delete propagation")
    for service, port in (("mysql-master", stack.MYSQL_PORT),
                          ("clickhouse", stack.CLICKHOUSE_NATIVE_PORT)):
        bindings = services[service].get("ports", [])
        _require(any(int(binding.get("published", 0)) == port
                     and int(binding["target"]) == port for binding in bindings),
                 "Client port does not match the managed compose binding")
    return config


def _containers(config):
    """Prove running container ownership and published-port identity."""
    result = {}
    for service in SERVICES:
        ids = _compose("ps", "-q", service).split()
        _require(len(ids) == 1, "Expected exactly one managed service container")
        info = json.loads(_process(["docker", "inspect", ids[0]]))[0]
        labels = info["Config"]["Labels"]
        _require(info["State"]["Running"]
                 and labels.get("com.docker.compose.project") == config["name"]
                 and labels.get("com.docker.compose.service") == service
                 and Path(labels.get("com.docker.compose.project.working_dir", "")).resolve()
                 == stack.COMPOSE_DIR.resolve(),
                 "Container does not belong to this running compose stack")
        _require(str(stack.COMPOSE_FILE.resolve()) in labels.get(
            "com.docker.compose.project.config_files", "").split(","),
            "Container was not started from the managed compose file")
        if service == CONNECTOR:
            _require(any(mount.get("Destination") == "/config.yml"
                         and Path(mount.get("Source", "")).resolve()
                         == (stack.COMPOSE_DIR / "config.yml").resolve()
                         for mount in info.get("Mounts", [])),
                     "Connector is not using the checked managed configuration")
        result[service] = info
    for service, port in (("mysql-master", stack.MYSQL_PORT),
                          ("clickhouse", stack.CLICKHOUSE_NATIVE_PORT)):
        bindings = result[service]["NetworkSettings"]["Ports"].get(f"{port}/tcp") or []
        _require(any(int(binding["HostPort"]) == port for binding in bindings),
                 "Managed container does not own the requested client port")
    return result


def _mysql():
    conn = stack.mysql_connection()
    # Reuse the fixture's credential/endpoint factory, but bound all subsequent IO.
    conn._read_timeout = QUERY_TIMEOUT
    conn._write_timeout = QUERY_TIMEOUT
    try:
        conn.autocommit(True)
        with conn.cursor() as cursor:
            cursor.execute("SET SESSION innodb_lock_wait_timeout = 5")
            cursor.execute("SET SESSION max_execution_time = 10000")
        return conn
    except BaseException:
        conn.close()
        raise


def _clickhouse():
    client = stack.clickhouse_client()
    client.connection.connect_timeout = QUERY_TIMEOUT
    client.connection.send_receive_timeout = QUERY_TIMEOUT
    return client


def _query(client, sql):
    return client.execute(sql, settings={"max_execution_time": QUERY_TIMEOUT})


@pytest.fixture(scope="module")
def managed_stack(request):
    config = _preflight()
    request.getfixturevalue("replicated_stack")
    containers = _containers(config)
    conn = _mysql()
    client = _clickhouse()
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT @@hostname, DATABASE()")
            hostname, database = cursor.fetchone()
        _require(hostname == containers["mysql-master"]["Config"]["Hostname"]
                 and database == stack.DATABASE,
                 "MySQL endpoint is not the managed container/database")
        _require(_query(client, "SELECT hostName(), currentDatabase()") == [
            (containers["clickhouse"]["Config"]["Hostname"], stack.DATABASE)],
            "ClickHouse endpoint is not the managed container/database")
    finally:
        conn.close()
        client.disconnect()
    return config


@pytest.fixture
def streaming_table(managed_stack):
    # CREATE (not IF NOT EXISTS) proves ownership; never reuse a seeded table.
    table = "stream_order_" + uuid.uuid4().hex
    qualified = f"`{stack.DATABASE}`.`{table}`"
    with ExitStack() as resources:
        conn = _mysql()
        resources.callback(conn.close)
        client = _clickhouse()
        resources.callback(client.disconnect)
        with conn.cursor() as cursor:
            cursor.execute(f"CREATE TABLE {qualified} (id INT PRIMARY KEY, "
                           "value VARCHAR(80) NOT NULL) ENGINE=InnoDB")
        yield table, qualified, conn, client
    # No DROP: the stack fixture owns disposal of the entire isolated stack.


def _write(conn, sql, parameters=None):
    with conn.cursor() as cursor:
        cursor.execute(sql, parameters)
        return cursor.rowcount


def _wait_rows(client, qualified, projection, expected):
    deadline = time.monotonic() + WAIT_TIMEOUT
    last_rows = None
    last_error = None
    while time.monotonic() < deadline:
        try:
            last_rows = _query(client, f"SELECT {projection} FROM {qualified} "
                               "FINAL WHERE is_deleted = 0 ORDER BY id")
            if last_rows == expected:
                return
        except Exception as error:  # CREATE/ALTER may still be in flight.
            last_error = type(error).__name__
        time.sleep(1)
    pytest.fail(f"FINAL values did not match {expected!r}; last rows={last_rows!r}; "
                f"last error type={last_error}", pytrace=False)


def _assert_checksum_results(table, expected_count, mysql, clickhouse):
    assert expected_count > 0, "Empty scenarios do not prove checksum agreement"
    assert set(mysql) == {table}, "Missing or unexpected MySQL checksum tables"
    assert set(clickhouse) == {table}, "Missing or unexpected ClickHouse checksum tables"
    assert mysql[table][1] == expected_count
    assert clickhouse[table][1] == expected_count
    assert mysql[table] == clickhouse[table], "Value-level checksum mismatch"


def _checkpoint(state, expected, projection="id, value", source_projection=None):
    table, qualified, conn, client = state
    with conn.cursor() as cursor:
        cursor.execute(f"SELECT {source_projection or projection} FROM {qualified} ORDER BY id")
        assert list(cursor.fetchall()) == expected, "Unexpected committed MySQL values"
    _wait_rows(client, qualified, projection, expected)
    regex = "^" + re.escape(table) + "$"
    mysql = _mysql_checksums(regex, ("--mysql_port", str(stack.MYSQL_PORT)))
    clickhouse = _clickhouse_checksums(regex, (
        "--clickhouse_port", str(stack.CLICKHOUSE_NATIVE_PORT),
        "--where", "is_deleted = 0",
    ))
    _assert_checksum_results(table, len(expected), mysql, clickhouse)


def _restart():
    _compose("restart", "--timeout", "20", CONNECTOR, timeout=60)


def test_late_commit_preserves_transaction_visibility(streaming_table):
    """Statements execute in one order and transactions commit in another.

    The transactions touch distinct keys to avoid InnoDB row-lock serialization.
    This checks commit-order visibility, not a measured source.ts_ms regression
    or competing out-of-order versions of the same key.
    """
    state = streaming_table
    _, qualified, conn, _ = state
    _write(conn, f"INSERT INTO {qualified} VALUES (1, %s), (2, %s)",
           ("long-before", "short-before"))
    _checkpoint(state, [(1, "long-before"), (2, "short-before")])
    older = _mysql()
    try:
        older.begin()
        _write(older, f"UPDATE {qualified} SET value = %s WHERE id = 1", ("late-commit",))
        _write(conn, f"UPDATE {qualified} SET value = %s WHERE id = 2", ("early-commit",))
        _checkpoint(state, [(1, "long-before"), (2, "early-commit")])
        older.commit()
        _checkpoint(state, [(1, "late-commit"), (2, "early-commit")])
        _write(conn, f"UPDATE {qualified} SET value = %s WHERE id = 1", ("newest-commit",))
        _checkpoint(state, [(1, "newest-commit"), (2, "early-commit")])
    finally:
        older.rollback()
        older.close()


def test_rollback_does_not_publish_uncommitted_values(streaming_table):
    state = streaming_table
    _, qualified, conn, _ = state
    _write(conn, f"INSERT INTO {qualified} VALUES (1, %s), (2, %s)",
           ("retained", "before"))
    _checkpoint(state, [(1, "retained"), (2, "before")])
    uncommitted = _mysql()
    try:
        uncommitted.begin()
        _write(uncommitted, f"UPDATE {qualified} SET value = %s WHERE id = 1", ("rolled-back",))
        _write(uncommitted, f"INSERT INTO {qualified} VALUES (3, %s)", ("never-committed",))
        _write(conn, f"UPDATE {qualified} SET value = %s WHERE id = 2", ("committed",))
        _checkpoint(state, [(1, "retained"), (2, "committed")])
        uncommitted.rollback()
        # A later committed marker establishes progress beyond the rollback.
        _write(conn, f"INSERT INTO {qualified} VALUES (4, %s)", ("after-rollback",))
        _checkpoint(state, [(1, "retained"), (2, "committed"), (4, "after-rollback")])
    finally:
        uncommitted.rollback()
        uncommitted.close()


def test_update_delete_recovery_across_connector_restarts(streaming_table):
    """Recover offline writes, then prove FINAL state remains stable on restart.

    This is restart/catch-up coverage, not deterministic offset rewind/replay.
    """
    state = streaming_table
    _, qualified, conn, _ = state
    _write(conn, f"INSERT INTO {qualified} VALUES (1, %s), (2, %s), (3, %s)",
           ("update-before", "delete-me", "survivor"))
    _checkpoint(state, [(1, "update-before"), (2, "delete-me"), (3, "survivor")])
    _compose("stop", "--timeout", "20", CONNECTOR, timeout=60)
    try:
        _write(conn, f"UPDATE {qualified} SET value = %s WHERE id = 1", ("update-after",))
        # DESTRUCTIVE: deletes only id=2 in this test's freshly CREATEd UUID
        # table, after managed-stack endpoint ownership checks; no shared data.
        assert _write(conn, f"DELETE FROM {qualified} WHERE id = %s", (2,)) == 1
        _write(conn, f"INSERT INTO {qualified} VALUES (4, %s)", ("offline-insert",))
    finally:
        _compose("start", CONNECTOR, timeout=60)
    expected = [(1, "update-after"), (3, "survivor"), (4, "offline-insert")]
    _checkpoint(state, expected)
    _restart()
    # Do not accept the already-correct rows as proof that the restarted
    # connector is running. A new marker must make it through first.
    _write(conn, f"INSERT INTO {qualified} VALUES (5, %s)", ("after-restart",))
    _checkpoint(state, expected + [(5, "after-restart")])


def test_binary_added_column_after_schema_refresh(streaming_table):
    state = streaming_table
    _, qualified, conn, _ = state
    _write(conn, f"INSERT INTO {qualified} VALUES (1, %s)", ("before-alter",))
    _checkpoint(state, [(1, "before-alter")])
    _write(conn, f"ALTER TABLE {qualified} ADD COLUMN payload VARBINARY(64) NULL")
    # Refresh the connector's column cache explicitly. This case does not claim
    # that ADD COLUMN is supported without restart or automatically backfills.
    _restart()
    first = b"\x00\xff\x80'\\\n\x00"
    second = bytes(range(32))
    _write(conn, f"UPDATE {qualified} SET value = %s, payload = %s WHERE id = 1",
           ("binary-updated", first))
    _write(conn, f"INSERT INTO {qualified} (id, value, payload) VALUES (2, %s, %s), (3, %s, %s)",
           ("binary-inserted", second, "binary-null", None))
    # HEX(source) vs HEX(encoded replica) would compare different semantics.
    # Compare the connector's declared base64 representation, including NULL.
    expected = [(1, "binary-updated", base64.b64encode(first).decode()),
                (2, "binary-inserted", base64.b64encode(second).decode()),
                (3, "binary-null", None)]
    _checkpoint(state, expected, projection="id, value, payload",
                source_projection="id, value, TO_BASE64(payload)")
