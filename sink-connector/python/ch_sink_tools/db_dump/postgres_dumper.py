# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : postgres_dumper.py
# -- Date         :
# -- Summary      : Parallel PostgreSQL → ClickHouse snapshot loader.
# --
# --   Uses PostgreSQL COPY (SELECT …) TO STDOUT WITH (FORMAT CSV) piped
# --   directly to clickhouse-client INSERT … FORMAT CSV, so no disk I/O
# --   is needed for the data.
# --
# --   After all tables are loaded it writes the WAL LSN captured at the
# --   START of the snapshot into the ClickHouse offset table so the
# --   Java CDC connector can resume from the correct position without
# --   re-running a snapshot.
# --
# -- Usage example:
# --   python postgres_dumper.py \
# --       --pg_host postgres.host \
# --       --pg_port 5432 \
# --       --pg_database staging \
# --       --pg_user replicator \
# --       --pg_password secret \
# --       --pg_schema public \
# --       --ch_host clickhouse.host \
# --       --ch_port 9000 \
# --       --ch_database db_name \
# --       --ch_user default \
# --       --ch_password secret \
# --       --threads 8 \
# --       --tables '.' \
# --       --exclude_tables 'framework_migrations|framework_sessions' \
# --       --offset_table altinity_sink_connector.replica_source_info_db_name_dev
# --
"""

import logging
import argparse
import traceback
import sys
import os
import re
import time
import datetime
import subprocess
import concurrent.futures
from subprocess import Popen, PIPE

from ch_sink_tools.db.postgres import (
    get_postgres_connection,
    get_tables,
    get_table_columns,
    get_table_pk,
    get_table_row_count,
    get_standby_lsn,
    get_server_timezone,
    build_ch_create_table_ddl,
)
from ch_sink_tools.db.clickhouse import clickhouse_connection, clickhouse_execute_conn
from ch_sink_tools.db_load.postgres_type_mapper import (
    build_create_table,
    build_insert_structure,
    build_select_columns,
    build_offset_insert,
)

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")

# Heartbeat table used to keep CDC offsets fresh during idle periods
HEARTBEAT_TABLE = "public.sink_connector_heartbeat"

# ---------------------------------------------------------------------------
# Logging factory (mirrors mysql_dumper.py pattern)
# ---------------------------------------------------------------------------
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


# ---------------------------------------------------------------------------
# Subprocess helpers  (identical pattern to mysql_dumper.py / clickhouse_loader.py)
# ---------------------------------------------------------------------------

def check_program_exists(name):
    p = Popen(['/usr/bin/which', name], stdout=PIPE, stderr=PIPE)
    p.communicate()
    return p.returncode == 0


def run_command(cmd):
    """
    # -- ======================================================================
    # -- run the command that is passed as cmd and return True or False.
    # -- Uses /bin/bash so that 'set -o pipefail' works when callers
    # -- prepend it to detect failures in pipe left-hand sides.
    # -- ======================================================================
    """
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True,
                               executable='/bin/bash')
    for line in process.stdout:
        logging.info(line.decode().strip())
        time.sleep(0.02)
    process.wait()
    rc = str(process.returncode)
    logging.debug("return code = " + str(rc))
    return rc


def run_quick_command(cmd):
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True)
    stdout, stderr = process.communicate()
    rc = str(process.poll())
    if stdout:
        logging.info(str(stdout).strip())
    logging.debug("return code = " + rc)
    if rc != "0":
        logging.error("command failed : terminating")
    return rc, stdout


# ---------------------------------------------------------------------------
# Schema creation in ClickHouse
# ---------------------------------------------------------------------------

def ensure_ch_database(ch_conn, ch_database, dry_run=False):
    sql = f"CREATE DATABASE IF NOT EXISTS `{ch_database}`"
    logging.info(sql)
    if not dry_run:
        try:
            clickhouse_execute_conn(ch_conn, sql)
        except Exception as e:
            logging.error(f"Database create error: {e}")


def create_ch_table(ch_conn, ch_database, table_name, columns, pk_columns,
                    dry_run=False):
    ddl = build_create_table(ch_database, table_name, columns, pk_columns)
    logging.info(f"DDL for {table_name}:\n{ddl}")
    if not dry_run:
        clickhouse_execute_conn(ch_conn, ddl)


# ---------------------------------------------------------------------------
# Per-table COPY → clickhouse-client pipe
# ---------------------------------------------------------------------------

def build_psql_copy_cmd(pg_host, pg_port, pg_user, pg_password,
                        pg_database, pg_schema, table_name,
                        column_names, batch_size=None):
    """
    Build the psql command that streams CSV rows for *table_name* to stdout.

    Uses standard CSV NULL convention: NULL = empty unquoted field,
    empty string = quoted empty "".  FORCE_QUOTE * ensures every non-NULL
    value is quoted so the two cases are always distinguishable.

    On the ClickHouse side, format_csv_null_representation='' and
    input_format_csv_empty_as_default=0 must be set so CH correctly
    interprets empty unquoted fields as NULL (not default/empty string).

    NOTE: The COPY SQL is written to a temp file and passed via -f to avoid
    shell quoting issues with double-quoted identifiers.  When the COPY SQL
    was embedded in -c "...", the shell stripped the inner double-quotes,
    causing PostgreSQL to case-fold mixed-case table names to lowercase
    (e.g. "LiteLLM_AccessGroupTable" → litellm_accessgrouptable) and fail
    with 'relation does not exist'.

    Returns (cmd, tmp_file_path) — the caller must clean up the temp file.
    """
    import tempfile
    col_list = ", ".join(f'"{c}"' for c in column_names)
    copy_sql = (
        f"COPY (SELECT {col_list} FROM \"{pg_schema}\".\"{table_name}\") "
        f"TO STDOUT WITH (FORMAT CSV, HEADER false, FORCE_QUOTE *)"
    )

    # Write COPY SQL to a temp file to preserve double-quote identifiers
    qfile = tempfile.NamedTemporaryFile(
        mode='w', suffix='.sql', prefix='pg_copy_',
        delete=False, dir='/tmp'
    )
    qfile.write(copy_sql)
    qfile.close()

    # PGTZ=UTC ensures that timestamptz values are output in UTC,
    # matching what Debezium CDC sends to ClickHouse.
    cmd = (
        f"PGPASSWORD='{pg_password}' PGTZ=UTC psql"
        f" -h {pg_host}"
        f" -p {pg_port}"
        f" -U {pg_user}"
        f" -d \"{pg_database}\""
        f" -f {qfile.name}"
    )
    return cmd, qfile.name


def build_ch_insert_cmd(ch_host, ch_port, ch_user, ch_password,
                        ch_database, table_name, column_names,
                        columns_meta, ch_config_file=None, ch_secure=False):
    """
    Build the clickhouse-client command that reads CSV from stdin and inserts
    into the target table using the input() table function so that type
    conversions are applied correctly.

    NOTE: The INSERT query is written to a temp file and passed via
    --queries-file to avoid bash backtick command substitution that would
    strip quoted column names when --query "..." is used in a shell pipeline.
    """
    import tempfile
    structure = build_insert_structure(columns_meta)
    select_cols = build_select_columns(columns_meta)
    # Use double-quote identifiers — ClickHouse accepts both backtick and
    # double-quote, but double-quotes survive shell expansion safely.
    col_list = ", ".join(f'"{c}"' for c in column_names)

    password_opt = f"--password '{ch_password}'" if ch_password else ""
    config_opt = f"--config-file '{ch_config_file}'" if ch_config_file else ""
    secure_opt = "--secure" if ch_secure else ""

    query = (
        f'INSERT INTO "{ch_database}"."{table_name}"({col_list}) '
        f"SELECT {select_cols} "
        f"FROM input('{structure}') "
        # format_csv_null_representation='' → empty unquoted CSV field = NULL
        # input_format_csv_empty_as_default=0 → don't replace empty with column defaults
        # These match the psql FORCE_QUOTE * convention where:
        #   NULL  = empty unquoted field (nothing between commas)
        #   ''    = quoted empty ""
        f"SETTINGS format_csv_null_representation='', input_format_csv_empty_as_default=0 "
        f"FORMAT CSV"
    )

    # Write query to a temp file to avoid shell backtick interpretation
    qfile = tempfile.NamedTemporaryFile(
        mode='w', suffix='.sql', prefix='ch_insert_',
        delete=False, dir='/tmp'
    )
    qfile.write(query)
    qfile.close()

    cmd = (
        f"clickhouse-client"
        f" {config_opt}"
        f" -h {ch_host}"
        f" --port {ch_port}"
        f" -u {ch_user}"
        f" {password_opt}"
        f" {secure_opt}"
        f" --session_timezone=UTC"
        f" --throw_if_no_data_to_insert=0"
        f" --max_partitions_per_insert_block=1000"
        f" --queries-file {qfile.name}"
        f"; rm -f {qfile.name}"
    )
    return cmd


def load_table(
    table_name,
    pg_host, pg_port, pg_user, pg_password, pg_database, pg_schema,
    ch_host, ch_port, ch_user, ch_password, ch_database,
    ch_config_file=None, ch_secure=False,
    dry_run=False, batch_size=None,
    pg_server_timezone=None,
):
    """
    Stream one table from PostgreSQL COPY to ClickHouse INSERT via a shell pipe.
    Returns (table_name, rows_estimated, elapsed_seconds, success).
    """
    t_start = time.time()
    success = False

    try:
        # We need a fresh PG connection per thread (psycopg2 is not thread-safe)
        pg_conn = get_postgres_connection(pg_host, pg_user, pg_password,
                                          pg_port, pg_database)
        columns_meta = get_table_columns(pg_conn, pg_schema, table_name, pg_server_timezone=pg_server_timezone)
        pk_cols = get_table_pk(pg_conn, pg_schema, table_name)
        approx_rows = get_table_row_count(pg_conn, pg_schema, table_name)
        pg_conn.close()

        column_names = [c['column_name'] for c in columns_meta]

        psql_cmd, pg_tmp_file = build_psql_copy_cmd(
            pg_host, pg_port, pg_user, pg_password,
            pg_database, pg_schema, table_name, column_names, batch_size,
        )
        ch_cmd = build_ch_insert_cmd(
            ch_host, ch_port, ch_user, ch_password,
            ch_database, table_name, column_names, columns_meta,
            ch_config_file=ch_config_file, ch_secure=ch_secure,
        )

        # Use 'set -o pipefail' so that a failure in psql (left side of
        # the pipe) propagates as the exit code.  Without this, only the
        # exit code of the last command (clickhouse-client) is returned,
        # masking psql COPY errors.
        pipe_cmd = f"set -o pipefail; {psql_cmd} | {ch_cmd}"
        logging.info(f"[{table_name}] Starting load (~{approx_rows:,} rows)")
        logging.debug(f"[{table_name}] pipe cmd: {pipe_cmd}")

        if not dry_run:
            try:
                rc = run_command(pipe_cmd)
            finally:
                # Clean up the psql temp SQL file
                try:
                    os.unlink(pg_tmp_file)
                except OSError:
                    pass
            if rc != "0":
                raise RuntimeError(
                    f"Pipe command failed for {table_name} (rc={rc})"
                )

        elapsed = time.time() - t_start
        rate = approx_rows / elapsed if elapsed > 0 else 0
        logging.info(
            f"[{table_name}] Done in {elapsed:.1f}s "
            f"(~{approx_rows:,} rows, ~{rate:,.0f} rows/s)"
        )
        success = True

    except Exception as e:
        elapsed = time.time() - t_start
        logging.error(
            f"[{table_name}] FAILED after {elapsed:.1f}s: {e}"
        )
        logging.error(traceback.format_exc())

    return (table_name, approx_rows, time.time() - t_start, success)


# ---------------------------------------------------------------------------
# LSN offset writer
# ---------------------------------------------------------------------------

def ensure_offset_database_and_table(ch_conn, offset_table, dry_run=False):
    """
    Ensure the offset database and table exist in ClickHouse before writing
    the LSN offset.  Parses the database name from the fully-qualified
    offset_table argument (e.g. 'altinity_sink_connector.replica_source_info_x').

    The table schema matches what the Java CDC connector expects
    (DebeziumOffsetStorage).
    """
    parts = offset_table.split('.', 1)
    if len(parts) == 2:
        offset_db = parts[0]
        table_only = parts[1]
    else:
        # No database prefix — nothing to auto-create
        logging.warning(
            f"offset_table '{offset_table}' has no database prefix; "
            f"skipping auto-create of database/table"
        )
        return

    # 1. Create the offset database
    create_db_sql = f"CREATE DATABASE IF NOT EXISTS `{offset_db}`"
    logging.info(f"Ensuring offset database exists: {create_db_sql}")
    if not dry_run:
        try:
            clickhouse_execute_conn(ch_conn, create_db_sql)
        except Exception as e:
            logging.error(f"Failed to create offset database '{offset_db}': {e}")
            raise

    # 2. Create the offset table with the schema the Java connector expects
    create_tbl_sql = (
        f"CREATE TABLE IF NOT EXISTS `{offset_db}`.`{table_only}` (\n"
        f"    `id` String,\n"
        f"    `offset_key` String,\n"
        f"    `offset_val` String,\n"
        f"    `record_insert_ts` DateTime,\n"
        f"    `record_insert_seq` UInt64\n"
        f") ENGINE = ReplacingMergeTree(record_insert_seq)\n"
        f"ORDER BY offset_key"
    )
    logging.info(f"Ensuring offset table exists: {create_tbl_sql}")
    if not dry_run:
        try:
            clickhouse_execute_conn(ch_conn, create_tbl_sql)
        except Exception as e:
            logging.error(f"Failed to create offset table '{offset_table}': {e}")
            raise


def write_lsn_offset(ch_conn, offset_table, lsn_int, connector_name, dry_run=False):
    """
    Insert the snapshot starting LSN into the ClickHouse offset table so that
    the Java CDC connector can start from the correct WAL position.

    connector_name must match the "name" property in the Java connector's config.yml,
    e.g. "sink-connector-dev".  The Java DebeziumOffsetStorage.getOffsetKey()
    uses it to construct the lookup key:
        [\"<connector_name>\",{"server":"embeddedconnector"}]
    If the key does not match, the Java connector will not find the offset and will
    trigger a new snapshot even with snapshot.mode=never.
    """
    sql = build_offset_insert(offset_table, lsn_int, connector_name=connector_name)
    logging.info(f"Writing LSN offset: {sql}")
    if not dry_run:
        clickhouse_execute_conn(ch_conn, sql)
    else:
        logging.info("dry-run: offset not written")


# ---------------------------------------------------------------------------
# Heartbeat table creation on source PostgreSQL
# ---------------------------------------------------------------------------

def ensure_heartbeat_table(pg_conn, pg_user):
    """
    Create the heartbeat table on the source PostgreSQL database if it does
    not already exist.  The heartbeat table is used by the sink connector to
    keep CDC offsets fresh during idle periods.

    This function is fault-tolerant: if the table cannot be created (e.g.
    insufficient privileges), it logs a WARNING and continues without
    raising an exception.
    """
    try:
        cur = pg_conn.cursor()

        # Check whether the table already exists
        cur.execute(
            "SELECT 1 FROM information_schema.tables "
            "WHERE table_schema = 'public' "
            "  AND table_name = 'sink_connector_heartbeat'"
        )
        exists = cur.fetchone() is not None

        if exists:
            logging.info(
                f"Heartbeat table {HEARTBEAT_TABLE} already exists"
            )
            cur.close()
            return

        # Create the heartbeat table
        cur.execute(
            f"CREATE TABLE IF NOT EXISTS {HEARTBEAT_TABLE} ("
            f"  id INTEGER PRIMARY KEY DEFAULT 1, "
            f"  ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()"
            f")"
        )

        # Insert the initial row
        cur.execute(
            f"INSERT INTO {HEARTBEAT_TABLE} (id, ts) "
            f"VALUES (1, now()) ON CONFLICT (id) DO NOTHING"
        )

        pg_conn.commit()
        logging.info(f"Created heartbeat table {HEARTBEAT_TABLE}")

        # Grant privileges to the sink connector user
        try:
            cur.execute(
                f"GRANT SELECT, INSERT, UPDATE ON {HEARTBEAT_TABLE} "
                f"TO \"{pg_user}\""
            )
            pg_conn.commit()
            logging.info(
                f"Granted SELECT, INSERT, UPDATE on {HEARTBEAT_TABLE} "
                f"to user '{pg_user}'"
            )
        except Exception as grant_err:
            pg_conn.rollback()
            logging.warning(
                f"Could not GRANT privileges on {HEARTBEAT_TABLE} "
                f"to '{pg_user}': {grant_err}"
            )

        cur.close()

    except Exception as e:
        # Roll back any partial transaction so the connection stays usable
        try:
            pg_conn.rollback()
        except Exception:
            pass
        logging.warning(
            f"Could not create heartbeat table {HEARTBEAT_TABLE}: {e}. "
            f"Continuing without heartbeat table."
        )


# ---------------------------------------------------------------------------
# PostgreSQL privilege validation
# ---------------------------------------------------------------------------

def validate_postgres_privileges(pg_conn, pg_user, schema, tables, config):
    """
    Validate that the PostgreSQL user has all the privileges required for the
    sink connector to operate correctly.

    Parameters
    ----------
    pg_conn  : psycopg2 connection to PostgreSQL
    pg_user  : str — the PostgreSQL user name
    schema   : str — the source schema (e.g. 'public')
    tables   : list[str] — table names that will be dumped
    config   : dict — additional config keys:
                  'connector_name' — Java connector name (for replication slot lookup)

    Returns
    -------
    bool — True if all *critical* privileges are present, False otherwise.
    """
    logger = logging.getLogger(__name__)
    cur = pg_conn.cursor()
    critical_missing = []
    warnings_list = []

    # ── 1. wal_level must be 'logical' ────────────────────────────────────
    try:
        cur.execute("SHOW wal_level")
        wal_level = cur.fetchone()[0]
        if wal_level == "logical":
            logger.info("✓ wal_level = 'logical' — OK")
        else:
            msg = (
                f"wal_level is '{wal_level}', must be 'logical' for CDC. "
                f"Set wal_level = logical in postgresql.conf and restart."
            )
            logger.error(f"✗ wal_level — MISSING: {msg}")
            critical_missing.append(msg)
    except Exception as e:
        msg = f"Could not check wal_level: {e}"
        logger.error(f"✗ wal_level — ERROR: {msg}")
        critical_missing.append(msg)
        pg_conn.rollback()

    # ── 2. LOGIN role attribute ───────────────────────────────────────────
    try:
        cur.execute(
            "SELECT rolcanlogin FROM pg_roles WHERE rolname = %s",
            (pg_user,)
        )
        row = cur.fetchone()
        if row is None:
            msg = f"Role '{pg_user}' not found in pg_roles"
            logger.error(f"✗ LOGIN — MISSING: {msg}")
            critical_missing.append(msg)
        elif row[0]:
            logger.info("✓ LOGIN role attribute — OK")
        else:
            msg = (
                f"Role '{pg_user}' does not have LOGIN. "
                f"Run: ALTER ROLE \"{pg_user}\" LOGIN;"
            )
            logger.error(f"✗ LOGIN — MISSING: {msg}")
            critical_missing.append(msg)
    except Exception as e:
        msg = f"Could not check LOGIN attribute: {e}"
        logger.error(f"✗ LOGIN — ERROR: {msg}")
        critical_missing.append(msg)
        pg_conn.rollback()

    # ── 3. REPLICATION role attribute ─────────────────────────────────────
    try:
        cur.execute(
            "SELECT rolreplication FROM pg_roles WHERE rolname = %s",
            (pg_user,)
        )
        row = cur.fetchone()
        if row is None:
            msg = f"Role '{pg_user}' not found in pg_roles"
            logger.error(f"✗ REPLICATION — MISSING: {msg}")
            critical_missing.append(msg)
        elif row[0]:
            logger.info("✓ REPLICATION role attribute — OK")
        else:
            msg = (
                f"Role '{pg_user}' does not have REPLICATION. "
                f"Run: ALTER ROLE \"{pg_user}\" REPLICATION;"
            )
            logger.error(f"✗ REPLICATION — MISSING: {msg}")
            critical_missing.append(msg)
    except Exception as e:
        msg = f"Could not check REPLICATION attribute: {e}"
        logger.error(f"✗ REPLICATION — ERROR: {msg}")
        critical_missing.append(msg)
        pg_conn.rollback()

    # ── 4. USAGE on schema ────────────────────────────────────────────────
    try:
        cur.execute(
            "SELECT has_schema_privilege(%s, %s, 'USAGE')",
            (pg_user, schema)
        )
        has_usage = cur.fetchone()[0]
        if has_usage:
            logger.info(f"✓ USAGE on schema '{schema}' — OK")
        else:
            msg = (
                f"Role '{pg_user}' lacks USAGE on schema '{schema}'. "
                f"Run: GRANT USAGE ON SCHEMA \"{schema}\" TO \"{pg_user}\";"
            )
            logger.error(f"✗ USAGE on schema — MISSING: {msg}")
            critical_missing.append(msg)
    except Exception as e:
        msg = f"Could not check USAGE on schema '{schema}': {e}"
        logger.error(f"✗ USAGE on schema — ERROR: {msg}")
        critical_missing.append(msg)
        pg_conn.rollback()

    # ── 5. SELECT on each table to be dumped ──────────────────────────────
    tables_missing_select = []
    for table_name in tables:
        fq_table = f'"{schema}"."{table_name}"'
        try:
            cur.execute(
                "SELECT has_table_privilege(%s, %s, 'SELECT')",
                (pg_user, f"{schema}.{table_name}")
            )
            has_sel = cur.fetchone()[0]
            if has_sel:
                logger.info(f"✓ SELECT on {fq_table} — OK")
            else:
                tables_missing_select.append(table_name)
                logger.error(
                    f"✗ SELECT on {fq_table} — MISSING"
                )
        except Exception as e:
            tables_missing_select.append(table_name)
            logger.error(
                f"✗ SELECT on {fq_table} — ERROR: {e}"
            )
            pg_conn.rollback()

    if tables_missing_select:
        msg = (
            f"Role '{pg_user}' lacks SELECT on {len(tables_missing_select)} table(s): "
            f"{tables_missing_select}. "
            f"Run: GRANT SELECT ON ALL TABLES IN SCHEMA \"{schema}\" TO \"{pg_user}\";"
        )
        critical_missing.append(msg)

    # ── 6. CREATE on schema (optional — heartbeat table) ──────────────────
    try:
        cur.execute(
            "SELECT has_schema_privilege(%s, %s, 'CREATE')",
            (pg_user, schema)
        )
        has_create = cur.fetchone()[0]
        if has_create:
            logger.info(f"✓ CREATE on schema '{schema}' — OK")
        else:
            msg = (
                f"Role '{pg_user}' lacks CREATE on schema '{schema}'. "
                f"The heartbeat table cannot be auto-created. "
                f"Run: GRANT CREATE ON SCHEMA \"{schema}\" TO \"{pg_user}\";"
            )
            logger.warning(f"✗ CREATE on schema — WARNING: {msg}")
            warnings_list.append(msg)
    except Exception as e:
        msg = f"Could not check CREATE on schema '{schema}': {e}"
        logger.warning(f"✗ CREATE on schema — WARNING: {msg}")
        warnings_list.append(msg)
        pg_conn.rollback()

    # ── 7. SELECT on pg_catalog tables ────────────────────────────────────
    pg_catalog_tables = ['pg_index', 'pg_attribute', 'pg_class', 'pg_namespace']
    for cat_table in pg_catalog_tables:
        try:
            cur.execute(
                "SELECT has_table_privilege(%s, %s, 'SELECT')",
                (pg_user, f"pg_catalog.{cat_table}")
            )
            has_sel = cur.fetchone()[0]
            if has_sel:
                logger.info(f"✓ SELECT on pg_catalog.{cat_table} — OK")
            else:
                msg = (
                    f"Role '{pg_user}' lacks SELECT on pg_catalog.{cat_table}. "
                    f"This is needed for primary key discovery."
                )
                logger.error(f"✗ SELECT on pg_catalog.{cat_table} — MISSING: {msg}")
                critical_missing.append(msg)
        except Exception as e:
            msg = f"Could not check SELECT on pg_catalog.{cat_table}: {e}"
            logger.error(f"✗ SELECT on pg_catalog.{cat_table} — ERROR: {msg}")
            critical_missing.append(msg)
            pg_conn.rollback()

    # ── 8. Replication slot existence ─────────────────────────────────────
    try:
        cur.execute(
            "SELECT slot_name, active FROM pg_replication_slots"
        )
        slots = cur.fetchall()
        if slots:
            slot_info = ", ".join(
                f"{s[0]} (active={s[1]})" for s in slots
            )
            logger.info(f"✓ Replication slot(s) found: {slot_info}")
        else:
            msg = (
                "No replication slots found. The connector will attempt to "
                "create one (requires REPLICATION privilege, checked above)."
            )
            logger.info(f"ℹ Replication slots — {msg}")
    except Exception as e:
        msg = f"Could not query pg_replication_slots: {e}"
        logger.warning(f"✗ Replication slots — WARNING: {msg}")
        warnings_list.append(msg)
        pg_conn.rollback()

    # ── 9. Publication existence ──────────────────────────────────────────
    try:
        cur.execute("SELECT pubname FROM pg_publication")
        pubs = [row[0] for row in cur.fetchall()]
        if pubs:
            logger.info(f"✓ Publication(s) found: {pubs}")
        else:
            msg = (
                "No publications found. Debezium requires a publication for "
                "logical replication. Create one with: "
                "CREATE PUBLICATION dbz_publication FOR ALL TABLES;"
            )
            logger.warning(f"✗ Publication — WARNING: {msg}")
            warnings_list.append(msg)
    except Exception as e:
        msg = f"Could not query pg_publication: {e}"
        logger.warning(f"✗ Publication — WARNING: {msg}")
        warnings_list.append(msg)
        pg_conn.rollback()

    cur.close()

    # ── Summary ───────────────────────────────────────────────────────────
    if critical_missing:
        logger.error(
            "Privilege validation FAILED — %d critical issue(s):",
            len(critical_missing),
        )
        for i, issue in enumerate(critical_missing, 1):
            logger.error("  %d. %s", i, issue)
    if warnings_list:
        logger.warning(
            "Privilege validation produced %d warning(s):",
            len(warnings_list),
        )
        for i, w in enumerate(warnings_list, 1):
            logger.warning("  %d. %s", i, w)
    if not critical_missing and not warnings_list:
        logger.info("All privilege checks passed.")
    elif not critical_missing:
        logger.info("All critical privilege checks passed (with warnings above).")

    return len(critical_missing) == 0


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Parallel PostgreSQL → ClickHouse snapshot loader.\n"
                    "Pipes COPY CSV output directly to clickhouse-client."
    )

    # -- PostgreSQL connection ------------------------------------------------
    parser.add_argument('--pg_host', required=True,
                        help='PostgreSQL host')
    parser.add_argument('--pg_port', type=int, default=5432,
                        help='PostgreSQL port (default: 5432)')
    parser.add_argument('--pg_database', required=True,
                        help='PostgreSQL database name')
    parser.add_argument('--pg_user', required=False,
                        help='PostgreSQL user')
    parser.add_argument('--pg_password', required=False, default=None,
                        help='PostgreSQL password (discouraged; prefer ~/.pgpass)')
    parser.add_argument('--pg_schema', required=False, default='public',
                        help='PostgreSQL schema (default: public)')

    # -- ClickHouse connection ------------------------------------------------
    parser.add_argument('--ch_host', required=True,
                        help='ClickHouse host')
    parser.add_argument('--ch_port', type=int, default=9000,
                        help='ClickHouse native port (default: 9000)')
    parser.add_argument('--ch_database', required=True,
                        help='ClickHouse target database')
    parser.add_argument('--ch_user', required=False, default='default',
                        help='ClickHouse user (default: default)')
    parser.add_argument('--ch_password', required=False, default=None,
                        help='ClickHouse password')
    parser.add_argument('--ch_config_file', required=False, default=None,
                        help='ClickHouse client config file (xml or yaml)')
    parser.add_argument('--ch_secure', dest='ch_secure',
                        action='store_true', default=False,
                        help='Use TLS for ClickHouse connection')

    # -- Table selection ------------------------------------------------------
    parser.add_argument('--tables', required=False, default='.',
                        help='Regex to include tables (default: all)')
    parser.add_argument('--exclude_tables', required=False, default=None,
                        help='Regex to exclude tables')

    # -- Load options ---------------------------------------------------------
    parser.add_argument('--threads', type=int, default=4,
                        help='Number of parallel table threads (default: 4)')
    parser.add_argument('--batch_size', type=int, default=None,
                        help='Batch size hint (not enforced in direct-pipe mode)')

    # -- Offset table (for CDC hand-off) -------------------------------------
    parser.add_argument('--offset_table', required=False, default=None,
                        help=(
                            'Fully-qualified ClickHouse offset table, e.g. '
                            'altinity_sink_connector.replica_source_info_db_name_dev. '
                            'When set, the pre-snapshot WAL LSN is written here '
                            'after all tables are loaded.'
                        ))
    parser.add_argument('--connector_name', required=False,
                        default='sink-connector',
                        help=(
                            'The "name" property from the Java connector config.yml. '
                            'Used to build the correct offset_key for the CDC hand-off. '
                            'E.g. "sink-connector-dev". '
                            'Must match exactly or the Java connector will re-snapshot.'
                        ))

    # -- Misc -----------------------------------------------------------------
    parser.add_argument('--schema_only', dest='schema_only',
                        action='store_true', default=False,
                        help='Create CH tables but do not load data')
    parser.add_argument('--data_only', dest='data_only',
                        action='store_true', default=False,
                        help='Load data only (tables must already exist in CH)')
    parser.add_argument('--dry_run', dest='dry_run',
                        action='store_true', default=False,
                        help='Print commands but do not execute')
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)

    global args
    args = parser.parse_args()

    # -- Logging setup (mirrors mysql_dumper.py) ------------------------------
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter(
        '%(asctime)s - %(levelname)s - %(threadName)s - %(message)s'
    )
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if args.debug:
        root.setLevel(logging.DEBUG)
        handler.setLevel(logging.DEBUG)

    # -- Dependency checks ----------------------------------------------------
    assert check_program_exists('psql'), \
        "psql should be in the PATH"
    assert check_program_exists('clickhouse-client'), \
        "clickhouse-client should be in the PATH"

    # -- Credential resolution ------------------------------------------------
    pg_user = args.pg_user
    pg_password = args.pg_password
    if pg_password is None:
        # try ~/.pgpass
        from ch_sink_tools.db.postgres import resolve_credentials_from_pgpass
        (pg_user_pg, pg_pass_pg) = resolve_credentials_from_pgpass()
        if pg_user_pg is not None:
            pg_user = pg_user_pg if pg_user is None else pg_user
            pg_password = pg_pass_pg
    if pg_password is None:
        logging.error(
            "No PostgreSQL password provided. Use --pg_password or ~/.pgpass"
        )
        sys.exit(1)

    ch_user = args.ch_user
    ch_password = args.ch_password
    if ch_password is None and args.ch_config_file is not None:
        from ch_sink_tools.db.clickhouse import resolve_credentials_from_config
        (ch_user, ch_password) = resolve_credentials_from_config(
            args.ch_config_file
        )

    try:
        # --------------------------------------------------------------------
        # Step 1: Capture pre-snapshot WAL LSN
        # --------------------------------------------------------------------
        logging.info("=== Step 1: Capturing current WAL LSN ===")
        pg_conn_main = get_postgres_connection(
            args.pg_host, pg_user, pg_password, args.pg_port, args.pg_database
        )
        (lsn_str, lsn_int) = get_standby_lsn(pg_conn_main)
        logging.info(f"Pre-snapshot LSN: {lsn_str}  (integer: {lsn_int})")

        # Detect PG server timezone once for explicit CH column type annotation
        pg_server_timezone = get_server_timezone(pg_conn_main)
        logging.info(f"PG server timezone detected: {pg_server_timezone}")

        # --------------------------------------------------------------------
        # Step 2: Discover tables
        # --------------------------------------------------------------------
        logging.info("=== Step 2: Discovering tables ===")
        tables = get_tables(
            pg_conn_main,
            args.pg_schema,
            include_regex=args.tables,
            exclude_regex=args.exclude_tables,
        )

        if not tables:
            logging.error(
                f"No tables found in schema '{args.pg_schema}' "
                f"matching '{args.tables}'"
            )
            pg_conn_main.close()
            sys.exit(1)

        logging.info(f"Tables to process ({len(tables)}): {tables}")

        # --------------------------------------------------------------------
        # Step 2b: Validate PostgreSQL user privileges
        # --------------------------------------------------------------------
        logging.info("=== Step 2b: Validating PostgreSQL user privileges ===")
        privs_ok = validate_postgres_privileges(
            pg_conn_main,
            pg_user,
            args.pg_schema,
            tables,
            config={'connector_name': args.connector_name},
        )
        if not privs_ok:
            logging.error(
                "Aborting: PostgreSQL user '%s' is missing critical privileges. "
                "See errors above for details.",
                pg_user,
            )
            pg_conn_main.close()
            sys.exit(1)

        # Create the heartbeat table on the source PostgreSQL database
        logging.info("=== Step 2c: Ensuring heartbeat table exists ===")
        ensure_heartbeat_table(pg_conn_main, pg_user)

        pg_conn_main.close()

        # --------------------------------------------------------------------
        # Step 3: Create ClickHouse database and tables
        # NOTE: clickhouse_driver Connection is NOT a context manager — do NOT
        # use "with clickhouse_connection(...) as ch_conn:" — it has no __enter__.
        # Always call .close() explicitly in a finally block.
        # --------------------------------------------------------------------
        if not args.data_only:
            logging.info("=== Step 3: Creating ClickHouse schema ===")
            ch_conn_default = clickhouse_connection(
                args.ch_host,
                database='default',
                user=ch_user,
                password=ch_password,
                port=args.ch_port,
                secure=args.ch_secure,
            )
            try:
                ensure_ch_database(ch_conn_default, args.ch_database,
                                   dry_run=args.dry_run)
            finally:
                ch_conn_default.close()

            ch_conn_schema = clickhouse_connection(
                args.ch_host,
                database=args.ch_database,
                user=ch_user,
                password=ch_password,
                port=args.ch_port,
                secure=args.ch_secure,
            )
            try:
                for table_name in tables:
                    pg_conn_t = get_postgres_connection(
                        args.pg_host, pg_user, pg_password,
                        args.pg_port, args.pg_database
                    )
                    columns_meta = get_table_columns(
                        pg_conn_t, args.pg_schema, table_name,
                        pg_server_timezone=pg_server_timezone,
                    )
                    pk_cols = get_table_pk(
                        pg_conn_t, args.pg_schema, table_name
                    )
                    pg_conn_t.close()
                    create_ch_table(
                        ch_conn_schema,
                        args.ch_database,
                        table_name,
                        columns_meta,
                        pk_cols,
                        dry_run=args.dry_run,
                    )
            finally:
                ch_conn_schema.close()

        # --------------------------------------------------------------------
        # Step 4: Load data in parallel
        # --------------------------------------------------------------------
        if not args.schema_only:
            logging.info(
                f"=== Step 4: Loading {len(tables)} tables "
                f"with {args.threads} threads ==="
            )
            results = []
            with concurrent.futures.ThreadPoolExecutor(
                max_workers=args.threads, thread_name_prefix='pg_loader'
            ) as executor:
                futures = {
                    executor.submit(
                        load_table,
                        table_name,
                        args.pg_host, args.pg_port,
                        pg_user, pg_password,
                        args.pg_database, args.pg_schema,
                        args.ch_host, args.ch_port,
                        ch_user, ch_password,
                        args.ch_database,
                        ch_config_file=args.ch_config_file,
                        ch_secure=args.ch_secure,
                        dry_run=args.dry_run,
                        batch_size=args.batch_size,
                        pg_server_timezone=pg_server_timezone,
                    ): table_name
                    for table_name in tables
                }

                for future in concurrent.futures.as_completed(futures):
                    try:
                        result = future.result()
                        results.append(result)
                    except Exception as exc:
                        tbl = futures[future]
                        logging.error(f"[{tbl}] raised an exception: {exc}")
                        logging.error(traceback.format_exc())
                        results.append((tbl, -1, 0, False))

            # Summary report
            logging.info("=== Load Summary ===")
            failed = []
            total_rows = 0
            for (tbl, rows, elapsed, ok) in sorted(results, key=lambda r: r[0]):
                status = "OK" if ok else "FAILED"
                rate = rows / elapsed if elapsed > 0 and rows > 0 else 0
                logging.info(
                    f"  {status:6s}  {tbl:50s} "
                    f"~{rows:>12,} rows  {elapsed:>7.1f}s  ~{rate:>10,.0f} rows/s"
                )
                if not ok:
                    failed.append(tbl)
                else:
                    total_rows += rows

            if failed:
                logging.error(f"FAILED tables: {failed}")
                sys.exit(1)

            logging.info(f"All tables loaded successfully (~{total_rows:,} rows total)")

        # --------------------------------------------------------------------
        # Step 5: Write LSN offset so CDC connector starts from right position
        # NOTE: clickhouse_driver Connection is NOT a context manager.
        # Connect to 'default' first because the offset database may not
        # exist yet — we create it (and the offset table) before writing.
        # --------------------------------------------------------------------
        if args.offset_table and not args.schema_only:
            logging.info("=== Step 5: Writing WAL LSN offset to ClickHouse ===")
            ch_conn_offset = clickhouse_connection(
                args.ch_host,
                database='default',
                user=ch_user,
                password=ch_password,
                port=args.ch_port,
                secure=args.ch_secure,
            )
            try:
                ensure_offset_database_and_table(
                    ch_conn_offset,
                    args.offset_table,
                    dry_run=args.dry_run,
                )
                write_lsn_offset(
                    ch_conn_offset,
                    args.offset_table,
                    lsn_int,
                    connector_name=args.connector_name,
                    dry_run=args.dry_run,
                )
            finally:
                ch_conn_offset.close()
            logging.info(
                f"LSN offset written: {lsn_str} (low32={lsn_int}) → {args.offset_table}. "
                f"connector_name='{args.connector_name}'. "
                f"Start the Java connector with snapshot.mode=never in config.yml"
            )
        elif args.offset_table is None and not args.schema_only:
            logging.warning(
                "No --offset_table specified. "
                "You must manually write the LSN offset before starting CDC. "
                f"Pre-snapshot LSN was: {lsn_str} (low32 int: {lsn_int})"
            )

    except (KeyboardInterrupt, SystemExit):
        logging.info("Received interrupt")
        os._exit(1)
    except Exception as e:
        logging.error("Exception in main thread : " + str(e))
        logging.error(traceback.format_exc())
        sys.exit(1)

    logging.info("postgres_dumper finished successfully")
    sys.exit(0)


if __name__ == '__main__':
    main()
