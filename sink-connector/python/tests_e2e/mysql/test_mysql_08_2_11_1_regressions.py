"""End-to-end regression suite for 2.11.1 replication, DDL, ordering, and toolset fixes.

Exercises the real MySQL -> lightweight sink connector -> ClickHouse pipeline for:
  1. Cross-database fully-qualified DDL (ALTER ADD COLUMN, TRUNCATE, RENAME, DROP)
     executed while the MySQL session's default database is another schema, followed
     by live DML without restarting the connector and full value-level checksum parity.
  2. ReplacingMergeTree UPDATE, DELETE, and primary-key UPDATE value parity.
  3. Concurrent late-committing transaction ordering (MySQL commit order preserved).
  4. Prometheus /metrics endpoint availability when metrics are exposed.
  5. Python toolset hardening: mysql_dumper --where, clickhouse_loader --truncate_tables,
     and ch-mysql-resync on ENUM / TINYINT UNSIGNED / BIT(1) columns.
"""
import decimal
import json
import os
import time
import urllib.request

import pytest

from mysql_e2e_support import (
    BINARY_ENCODING,
    CH_HOST,
    DB,
    DB2,
    MYSQL_HOST,
    MYSQL_PASSWORD,
    MYSQL_PORT,
    MYSQL_USER,
    OFFSET_TABLE,
    REPLICATION_TIMEOUT,
    SOURCE_TIMEZONE,
    ch_query,
    connector_offset_rows,
    mysql_connection,
    mysql_query,
    parse_verdicts,
    wait_for_clickhouse_value,
    wait_for_connector_offset_at_or_past,
    warning_lines,
)

RESYNC = ["-m", "ch_sink_tools.db_load.mysql_resync"]
LOADER = ["-m", "ch_sink_tools.db_load.clickhouse_loader"]


def _checksum_table(ws, table_name, config_name):
    config = ws.write_job_config(config_name, databases=[DB], where_overrides={})
    cmd = ws.job_command(
        config,
        partitioned=False,
        tables_regex=f"^{table_name}$",
        exclude_tables_regex="^$",
    )
    result = ws.run_job([cmd])
    assert result.returncode == 0, result
    assert warning_lines(result.all_logs) == [], result
    verdicts = parse_verdicts(result.all_logs)
    assert verdicts == {f"{DB}.{table_name}": "MATCH"}, (verdicts, result)


def _wait_table_exists(database, table, exists=1, timeout=REPLICATION_TIMEOUT):
    wait_for_clickhouse_value(
        f"EXISTS TABLE `{database}`.`{table}`",
        [(exists,)],
        timeout=timeout,
        description=f"EXISTS TABLE `{database}`.`{table}` == {exists}",
    )


def test_cross_db_fqn_ddl_and_live_dml_parity(ws):
    """Cross-DB FQN ALTER ADD COLUMN, TRUNCATE, RENAME, and DROP replicate cleanly
    from another current schema and live DML after ALTER populates the new column
    without restarting the connector."""
    t_orig = "temp_e2e_2111_ddl"
    t_renamed = "temp_e2e_2111_ddl_renamed"
    try:
        # DESTRUCTIVE: drops only this test's scratch tables on the disposable e2e stack.
        mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{t_orig}`")
        mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{t_renamed}`")

        mysql_query(
            f"CREATE TABLE `{DB}`.`{t_orig}` ("
            "id INT NOT NULL PRIMARY KEY, "
            "label VARCHAR(64) NULL)"
        )
        mysql_query(f"INSERT INTO `{DB}`.`{t_orig}` VALUES (1, 'before_alter')")
        _wait_table_exists(DB, t_orig, exists=1)
        wait_for_clickhouse_value(
            f"SELECT id, label FROM `{DB}`.`{t_orig}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(1, "before_alter")],
        )

        # Execute cross-database FQN ALTER TABLE while session default DB is DB2,
        # including a backticked column name containing a space.
        mysql_query(
            f"ALTER TABLE `{DB}`.`{t_orig}` ADD COLUMN extra INT NOT NULL DEFAULT 7, "
            "ADD COLUMN `extra note` VARCHAR(32) NULL",
            database=DB2,
        )
        # Immediately write rows carrying the new columns (no connector restart).
        mysql_query(
            f"INSERT INTO `{DB}`.`{t_orig}` (id, label, extra, `extra note`) VALUES (2, 'after_alter', 42, 'spaced')",
            database=DB2,
        )
        wait_for_clickhouse_value(
            f"SELECT id, label, extra, `extra note` FROM `{DB}`.`{t_orig}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(1, "before_alter", 7, None), (2, "after_alter", 42, "spaced")],
        )
        _checksum_table(ws, t_orig, "checksum_2111_ddl_alter.yaml")

        # DESTRUCTIVE: truncates only this test's scratch table on the disposable e2e stack to verify cross-DB FQN TRUNCATE.
        mysql_query(f"TRUNCATE TABLE `{DB}`.`{t_orig}`", database=DB2)
        mysql_query(
            f"INSERT INTO `{DB}`.`{t_orig}` (id, label, extra, `extra note`) VALUES (3, 'after_truncate', 99, 'kept')",
            database=DB2,
        )
        wait_for_clickhouse_value(
            f"SELECT id, label, extra, `extra note` FROM `{DB}`.`{t_orig}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(3, "after_truncate", 99, "kept")],
        )
        _checksum_table(ws, t_orig, "checksum_2111_ddl_truncate.yaml")

        # Cross-DB FQN RENAME TABLE.
        mysql_query(
            f"RENAME TABLE `{DB}`.`{t_orig}` TO `{DB}`.`{t_renamed}`",
            database=DB2,
        )
        mysql_query(
            f"INSERT INTO `{DB}`.`{t_renamed}` (id, label, extra, `extra note`) VALUES (4, 'after_rename', 100, 'moved')",
            database=DB2,
        )
        _wait_table_exists(DB, t_renamed, exists=1)
        wait_for_clickhouse_value(
            f"SELECT id, label, extra, `extra note` FROM `{DB}`.`{t_renamed}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(3, "after_truncate", 99, "kept"), (4, "after_rename", 100, "moved")],
        )
        _checksum_table(ws, t_renamed, "checksum_2111_ddl_rename.yaml")

        # DESTRUCTIVE: drops only this test's scratch table on the disposable e2e stack to verify cross-DB FQN DROP.
        mysql_query(f"DROP TABLE `{DB}`.`{t_renamed}`", database=DB2)
        _wait_table_exists(DB, t_renamed, exists=0)
    finally:
        # DESTRUCTIVE: cleans up only this test's scratch tables on the disposable e2e stack.
        for fn, sql in (
            (mysql_query, f"DROP TABLE IF EXISTS `{DB}`.`{t_orig}`"),
            (mysql_query, f"DROP TABLE IF EXISTS `{DB}`.`{t_renamed}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{DB}`.`{t_orig}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{DB}`.`{t_renamed}`"),
        ):
            try:
                fn(sql)
            except Exception:
                pass


def test_rmt_update_delete_and_pk_update_parity(ws):
    """ReplacingMergeTree UPDATE, DELETE, and primary-key UPDATE converge to exact MySQL parity."""
    table = "temp_e2e_2111_dml"
    try:
        # DESTRUCTIVE: drops only this test's scratch table on the disposable e2e stack.
        mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{table}`")
        mysql_query(
            f"CREATE TABLE `{DB}`.`{table}` ("
            "id INT NOT NULL PRIMARY KEY, "
            "val VARCHAR(64) NULL, "
            "amount DECIMAL(12,2) NOT NULL)"
        )
        mysql_query(
            f"INSERT INTO `{DB}`.`{table}` VALUES "
            "(1, 'a', 10.00), (2, 'b', 20.00), (3, 'c', 30.00)"
        )
        wait_for_clickhouse_value(
            f"SELECT count() FROM `{DB}`.`{table}` FINAL WHERE is_deleted = 0",
            [(3,)],
        )

        mysql_query(f"UPDATE `{DB}`.`{table}` SET val = 'a_updated', amount = 15.50 WHERE id = 1")
        # DESTRUCTIVE: deletes one test row in this test's scratch table to verify DELETE replication.
        mysql_query(f"DELETE FROM `{DB}`.`{table}` WHERE id = 2")
        mysql_query(f"UPDATE `{DB}`.`{table}` SET id = 30, val = 'c_rekeyed' WHERE id = 3")

        wait_for_clickhouse_value(
            f"SELECT id, val, amount FROM `{DB}`.`{table}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [
                (1, "a_updated", decimal.Decimal("15.50")),
                (30, "c_rekeyed", decimal.Decimal("30.00")),
            ],
        )
        _checksum_table(ws, table, "checksum_2111_dml.yaml")
    finally:
        # DESTRUCTIVE: cleans up only this test's scratch table on the disposable e2e stack.
        for fn, sql in (
            (mysql_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
        ):
            try:
                fn(sql)
            except Exception:
                pass


def test_late_commit_ordering_preserves_mysql_commit_order(ws):
    """A long-running transaction whose earlier statement executes before a fast
    transaction commits must still win in ClickHouse when it commits later."""
    table = "temp_e2e_2111_order"
    conn_slow = None
    conn_fast = None
    try:
        # DESTRUCTIVE: drops only this test's scratch table on the disposable e2e stack.
        mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{table}`")
        mysql_query(
            f"CREATE TABLE `{DB}`.`{table}` ("
            "id INT NOT NULL PRIMARY KEY, "
            "writer VARCHAR(64) NOT NULL)"
        )
        mysql_query(f"INSERT INTO `{DB}`.`{table}` VALUES (1, 'init'), (2, 'init')")
        wait_for_clickhouse_value(
            f"SELECT id, writer FROM `{DB}`.`{table}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(1, "init"), (2, "init")],
        )

        conn_slow = mysql_connection(DB)
        conn_slow.autocommit(False)
        conn_fast = mysql_connection(DB)
        conn_fast.autocommit(False)

        with conn_slow.cursor() as cur_slow, conn_fast.cursor() as cur_fast:
            # Slow transaction executes its first statement early (older statement timestamp).
            cur_slow.execute("BEGIN")
            cur_slow.execute(f"UPDATE `{DB}`.`{table}` SET writer = 'slow_row2' WHERE id = 2")

            time.sleep(1.2)

            # Fast transaction runs and commits with a newer statement timestamp.
            cur_fast.execute("BEGIN")
            cur_fast.execute(f"UPDATE `{DB}`.`{table}` SET writer = 'fast_first' WHERE id = 1")
            conn_fast.commit()

            # Now slow transaction updates row 1 and commits AFTER fast transaction.
            cur_slow.execute(f"UPDATE `{DB}`.`{table}` SET writer = 'slow_second' WHERE id = 1")
            conn_slow.commit()

        wait_for_clickhouse_value(
            f"SELECT id, writer FROM `{DB}`.`{table}` FINAL WHERE is_deleted = 0 ORDER BY id",
            [(1, "slow_second"), (2, "slow_row2")],
        )
        _checksum_table(ws, table, "checksum_2111_order.yaml")
    finally:
        for conn in (conn_slow, conn_fast):
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
        # DESTRUCTIVE: cleans up only this test's scratch table on the disposable e2e stack.
        for fn, sql in (
            (mysql_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
        ):
            try:
                fn(sql)
            except Exception:
                pass


def test_connector_prometheus_metrics_exposed():
    """Verify the connector's Prometheus metrics endpoint responds cleanly when configured."""
    metrics_url = os.environ.get("PYTOOLS_E2E_CONNECTOR_METRICS_URL")
    if not metrics_url:
        pytest.skip("PYTOOLS_E2E_CONNECTOR_METRICS_URL not set")
    with urllib.request.urlopen(metrics_url, timeout=10) as resp:
        assert resp.status == 200
        body = resp.read().decode("utf-8", errors="replace")
    assert len(body) > 0
    assert "# TYPE " in body, body[:500]


def test_pytools_2_11_1_hardening_e2e(ws):
    """Verify mysql_dumper --where, clickhouse_loader --truncate_tables, and ch-mysql-resync
    on ENUM, TINYINT UNSIGNED, and BIT(1) columns against live MySQL and ClickHouse."""
    table = "temp_e2e_2111_types"
    scratch_db = "pyops_e2e_2111_scratch"
    restore_db = f"{DB}_restore"
    try:
        # DESTRUCTIVE: drops only this test's scratch table/db on the disposable e2e stack.
        mysql_query(f"DROP TABLE IF EXISTS `{DB}`.`{table}`")
        ch_query(f"DROP DATABASE IF EXISTS `{scratch_db}`")
        mysql_query(
            f"CREATE TABLE `{DB}`.`{table}` ("
            "id INT NOT NULL PRIMARY KEY, "
            "status ENUM('new','active','closed') NOT NULL, "
            "tiny_u TINYINT UNSIGNED NOT NULL, "
            "flag BIT(1) NOT NULL, "
            "note VARCHAR(32) NULL)"
        )
        mysql_query(
            f"INSERT INTO `{DB}`.`{table}` VALUES "
            "(1, 'new', 0, b'0', NULL), "
            "(2, 'active', 200, b'1', 'second'), "
            "(3, 'closed', 255, b'1', 'third')"
        )
        wait_for_clickhouse_value(
            f"SELECT count() FROM `{DB}`.`{table}` FINAL WHERE is_deleted = 0",
            [(3,)],
        )
        _checksum_table(ws, table, "checksum_2111_types_initial.yaml")

        # 1. mysql_dumper --where dumps only matching rows (id >= 2).
        dump_where = ws.ws / "dump_2111_where"
        dump_res = ws.py(
            "db_dump/mysql_dumper.py",
            "--mysql_host", MYSQL_HOST,
            "--mysql_database", DB,
            "--defaults_file", ".my.cnf",
            "--dump_dir", dump_where,
            "--threads", "2",
            "--include_tables_regex", f"^{table}$",
            "--where", "id >= 2",
        )
        assert dump_res.returncode == 0, dump_res

        # 2. clickhouse_loader loads those 2 rows into scratch_db, and --truncate_tables reloads cleanly.
        load_argv = [
            "db_load/clickhouse_loader.py",
            "--clickhouse_host", CH_HOST,
            "--clickhouse_config_file", "clickhouse-client.xml",
            "--clickhouse_database", scratch_db,
            "--mysql_source_database", DB,
            "--dump_dir", dump_where,
            "--threads", "2",
            "--mysqlshell",
            "--rmt_delete_support",
            "--clickhouse_datetime_timezone", SOURCE_TIMEZONE,
            "--source_timezone", SOURCE_TIMEZONE,
            "--binary_handling_mode", BINARY_ENCODING,
        ]
        load1 = ws.py(*load_argv)
        assert load1.returncode == 0, load1
        assert ch_query(f"SELECT id FROM `{scratch_db}`.`{table}` FINAL ORDER BY id") == [(2,), (3,)]

        # DESTRUCTIVE: truncates only this test's scratch ClickHouse table to verify --truncate_tables.
        load2 = ws.py(*load_argv, "--data_only", "--truncate_tables")
        assert load2.returncode == 0, load2
        assert ch_query(f"SELECT count() FROM `{scratch_db}`.`{table}`") == [(2,)]

        # 3. Unlogged update on ENUM / TINYINT UNSIGNED / BIT(1), then ch-mysql-resync dump + patch --apply.
        mysql_query(
            f"UPDATE `{DB}`.`{table}` SET status = 'closed', tiny_u = 254, flag = b'0', note = 'repaired' WHERE id = 2",
            unlogged=True,
        )
        resync_base = ws.root / "resync_2111"
        stamp = "e2e2111"
        res_dump = ws.py(
            *RESYNC, "dump",
            "--dump-base", resync_base,
            "--stamp", stamp,
            "--mysql-uri", f"{MYSQL_USER}@{MYSQL_HOST}:{MYSQL_PORT}",
            "--schemas", DB,
            "--tables", f"^{table}$",
            "--consistent",
            "--threads", "2",
            env={"MYSQL_PWD": MYSQL_PASSWORD},
        )
        assert res_dump.returncode == 0, res_dump
        pos = json.loads((resync_base / f"binlog_position_{stamp}.json").read_text())
        wait_for_connector_offset_at_or_past(pos["file"], pos["pos"])

        offset_key = connector_offset_rows()[0][0]
        loader_cmd = f"{ws.python} {' '.join(LOADER)} --binary_handling_mode {BINARY_ENCODING}"
        res_patch = ws.py(
            *RESYNC, "patch",
            "--dump-base", resync_base,
            "--stamp", stamp,
            "--schemas", DB,
            "--tables", f"^{table}$",
            "--ch-host", CH_HOST,
            "--ch-config", ws.ch_config,
            "--apply",
            "--source_timezone", SOURCE_TIMEZONE,
            "--loader-cmd", loader_cmd,
            "--offset-table", OFFSET_TABLE,
            "--offset-key", offset_key,
        )
        assert res_patch.returncode == 0, res_patch
        assert f"{DB}.{table}: REPLACED_OK" in res_patch.output, res_patch
        _checksum_table(ws, table, "checksum_2111_types_repaired.yaml")
    finally:
        # DESTRUCTIVE: cleans up only this test's scratch table/db on the disposable e2e stack.
        for fn, sql in (
            (mysql_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{DB}`.`{table}`"),
            (ch_query, f"DROP TABLE IF EXISTS `{restore_db}`.`{table}`"),
            (ch_query, f"DROP DATABASE IF EXISTS `{scratch_db}`"),
        ):
            try:
                fn(sql)
            except Exception:
                pass
