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
import gzip
import shutil
from pathlib import Path
from subprocess import Popen, PIPE

from ch_sink_tools.db.postgres import (
    get_postgres_connection,
    get_schemas,
    get_tables,
    get_table_columns,
    get_table_pk,
    get_table_row_count,
    get_standby_lsn,
    get_server_timezone,
    build_ch_create_table_ddl,
)
from ch_sink_tools.db.clickhouse import clickhouse_connection, clickhouse_execute_conn
from ch_sink_tools.db_dump.naming import validate_template, resolve_ch_names
from ch_sink_tools.db_load.postgres_type_mapper import (
    build_create_table,
    build_insert_structure,
    build_select_columns,
    build_offset_insert,
)
from ch_sink_tools.config.column_type_overrides import ColumnTypeOverrideConfig
from ch_sink_tools.config.override_reconciler import (
    ch_table_exists,
    reconcile_overrides_with_existing_table,
    ColumnTypeOverrideMismatchError,
)

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")

# Heartbeat table used to keep CDC offsets fresh during idle periods
HEARTBEAT_TABLE = "public.sink_connector_heartbeat"

# ---------------------------------------------------------------------------
# PG binary resolution  (supports --pg_bin_dir / PG_BIN_DIR env var)
# ---------------------------------------------------------------------------
PG_BIN_DIR = os.environ.get('PG_BIN_DIR', '')


def pg_bin(name: str) -> str:
    """Resolve a PostgreSQL binary (psql, pg_dump, pg_restore) using PG_BIN_DIR."""
    if PG_BIN_DIR:
        return os.path.join(PG_BIN_DIR, name)
    return name

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
# Table regex filtering
# ---------------------------------------------------------------------------

def filter_tables_by_regex(tables, include_pattern=None, exclude_pattern=None):
    """Filter a list of table names by include/exclude regex patterns.

    Args:
        tables: List of table name strings.
        include_pattern: Optional regex — only tables matching are kept.
        exclude_pattern: Optional regex — tables matching are removed.

    Returns:
        Filtered list of table names.
    """
    if include_pattern:
        include_re = re.compile(include_pattern)
        tables = [t for t in tables if include_re.search(t)]
    if exclude_pattern:
        exclude_re = re.compile(exclude_pattern)
        tables = [t for t in tables if not exclude_re.search(t)]
    return tables


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
                    dry_run=False, override_config=None, schema=None,
                    pg_database=None):
    ddl = build_create_table(
        ch_database, table_name, columns, pk_columns,
        override_config=override_config, schema=schema,
        database=pg_database,
    )
    logging.info(f"DDL for {table_name}:\n{ddl}")
    if not dry_run:
        clickhouse_execute_conn(ch_conn, ddl)


# ---------------------------------------------------------------------------
# PK-range segmentation helpers
# ---------------------------------------------------------------------------

def get_pk_range_boundaries(pg_conn, pg_schema, table_name, pk_column, num_segments):
    """
    Discover evenly-spaced boundary values for *pk_column* so the table can
    be loaded in *num_segments* parallel COPY streams.

    Uses ntile() windowing to find the segment boundaries.  Returns a list of
    (lower_bound_inclusive, upper_bound_exclusive) tuples.  The first tuple
    has lower_bound=None and the last has upper_bound=None so the full range
    is covered.

    Parameters
    ----------
    pg_conn      : psycopg2 connection
    pg_schema    : PG schema name
    table_name   : PG table name
    pk_column    : column name to range-partition on (must be sortable)
    num_segments : desired number of segments

    Returns
    -------
    list of (lower, upper) tuples — len == num_segments
    """
    from ch_sink_tools.db.postgres import execute_pg

    # Find the boundary values using ntile
    sql = f"""
        WITH ranked AS (
            SELECT "{pk_column}",
                   ntile({num_segments}) OVER (ORDER BY "{pk_column}") AS seg
            FROM "{pg_schema}"."{table_name}"
        )
        SELECT seg, MIN("{pk_column}") AS seg_min, MAX("{pk_column}") AS seg_max
        FROM ranked
        GROUP BY seg
        ORDER BY seg
    """
    rows = execute_pg(pg_conn, sql)

    if not rows:
        return [(None, None)]

    boundaries = []
    for i, row in enumerate(rows):
        lower = row['seg_min'] if i > 0 else None
        upper = rows[i + 1]['seg_min'] if i < len(rows) - 1 else None
        boundaries.append((lower, upper))

    return boundaries


def get_pk_type_is_segmentable(pg_conn, pg_schema, table_name, pk_columns):
    """
    Check if the primary key is suitable for range-based segmentation.

    A PK is segmentable if:
    - It has exactly one column (compound PKs are too complex)
    - The column type supports range comparisons (int, bigint, text, varchar,
      timestamp, uuid, etc.)

    Returns (is_segmentable: bool, pk_column: str or None)
    """
    if len(pk_columns) != 1:
        return False, None

    pk_col = pk_columns[0]

    # Check the column type
    from ch_sink_tools.db.postgres import execute_pg
    sql = f"""
        SELECT data_type, udt_name
        FROM information_schema.columns
        WHERE table_schema = '{pg_schema}'
          AND table_name = '{table_name}'
          AND column_name = '{pk_col}'
    """
    rows = execute_pg(pg_conn, sql)
    if not rows:
        return False, None

    data_type = rows[0]['data_type'].lower()
    # Most types support range comparisons in PostgreSQL
    segmentable_types = {
        'integer', 'bigint', 'smallint', 'serial', 'bigserial',
        'text', 'character varying', 'varchar', 'character', 'char',
        'uuid', 'timestamp without time zone', 'timestamp with time zone',
        'date', 'real', 'double precision', 'numeric',
    }
    if data_type in segmentable_types:
        return True, pk_col

    return False, None


# ---------------------------------------------------------------------------
# Per-table COPY → clickhouse-client pipe
# ---------------------------------------------------------------------------

def build_psql_copy_cmd(pg_host, pg_port, pg_user, pg_password,
                        pg_database, pg_schema, table_name,
                        column_names, batch_size=None,
                        where_clause=None):
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

    Parameters
    ----------
    where_clause : Optional SQL WHERE clause (without the WHERE keyword) to
                   filter rows.  Used for PK-range segmented loading.
                   Example: '"request_id" >= 'abc' AND "request_id" < 'def''

    Returns (cmd, tmp_file_path) — the caller must clean up the temp file.
    """
    import tempfile
    col_list = ", ".join(f'"{c}"' for c in column_names)
    where_part = f" WHERE {where_clause}" if where_clause else ""
    copy_sql = (
        f"COPY (SELECT {col_list} FROM \"{pg_schema}\".\"{table_name}\"{where_part}) "
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
        f"PGPASSWORD='{pg_password}' PGTZ=UTC {pg_bin('psql')}"
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
    ch_table_name=None,
    where_clause=None,
    segment_label=None,
):
    """
    Stream one table (or a segment of it) from PostgreSQL COPY to ClickHouse
    INSERT via a shell pipe.
    Returns (label, rows_estimated, elapsed_seconds, success).

    Parameters
    ----------
    table_name     : PG table name (used for COPY from PostgreSQL)
    ch_table_name  : ClickHouse destination table name.  Defaults to *table_name*
                     when not provided (backward compatible pass-through).
    where_clause   : Optional SQL WHERE clause (without WHERE keyword) to filter
                     rows.  Used for PK-range segmented loading of large tables.
    segment_label  : Human-readable label for this segment (e.g. "seg 1/4").
                     Used in log messages.
    """
    # Resolve CH table name — default to PG table name for backward compat
    if ch_table_name is None:
        ch_table_name = table_name
    label = ch_table_name
    if segment_label:
        label = f"{ch_table_name} [{segment_label}]"

    t_start = time.time()
    success = False

    try:
        # We need a fresh PG connection per thread (psycopg2 is not thread-safe)
        pg_conn = get_postgres_connection(pg_host, pg_user, pg_password,
                                          pg_port, pg_database)
        columns_meta = get_table_columns(pg_conn, pg_schema, table_name, pg_server_timezone=pg_server_timezone)
        pk_cols = get_table_pk(pg_conn, pg_schema, table_name)
        approx_rows = get_table_row_count(pg_conn, pg_schema, table_name)
        if where_clause:
            # For segments we don't have an exact row count for the segment;
            # estimate evenly (caller can override via segment_label).
            approx_rows = -1
        pg_conn.close()

        column_names = [c['column_name'] for c in columns_meta]

        psql_cmd, pg_tmp_file = build_psql_copy_cmd(
            pg_host, pg_port, pg_user, pg_password,
            pg_database, pg_schema, table_name, column_names, batch_size,
            where_clause=where_clause,
        )
        ch_cmd = build_ch_insert_cmd(
            ch_host, ch_port, ch_user, ch_password,
            ch_database, ch_table_name, column_names, columns_meta,
            ch_config_file=ch_config_file, ch_secure=ch_secure,
        )

        # Use 'set -o pipefail' so that a failure in psql (left side of
        # the pipe) propagates as the exit code.  Without this, only the
        # exit code of the last command (clickhouse-client) is returned,
        # masking psql COPY errors.
        pipe_cmd = f"set -o pipefail; {psql_cmd} | {ch_cmd}"
        if approx_rows >= 0:
            logging.info(f"[{label}] Starting load (~{approx_rows:,} rows)")
        else:
            logging.info(f"[{label}] Starting segment load")
        logging.debug(f"[{label}] pipe cmd: {pipe_cmd}")

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
                    f"Pipe command failed for {label} (rc={rc})"
                )

        elapsed = time.time() - t_start
        if approx_rows > 0:
            rate = approx_rows / elapsed if elapsed > 0 else 0
            logging.info(
                f"[{label}] Done in {elapsed:.1f}s "
                f"(~{approx_rows:,} rows, ~{rate:,.0f} rows/s)"
            )
        else:
            logging.info(f"[{label}] Done in {elapsed:.1f}s")
        success = True

    except Exception as e:
        elapsed = time.time() - t_start
        logging.error(
            f"[{label}] FAILED after {elapsed:.1f}s: {e}"
        )
        logging.error(traceback.format_exc())

    return (label, approx_rows, time.time() - t_start, success)


def build_segment_where_clause(pk_column, lower_bound, upper_bound):
    """
    Build a SQL WHERE clause for a PK-range segment.

    Parameters
    ----------
    pk_column    : the primary key column name
    lower_bound  : inclusive lower bound (None = no lower limit)
    upper_bound  : exclusive upper bound (None = no upper limit)

    Returns
    -------
    str — WHERE clause without the WHERE keyword, e.g.
          '"request_id" >= 'abc' AND "request_id" < 'def''
    """
    conditions = []
    if lower_bound is not None:
        # Escape single quotes in the value
        val = str(lower_bound).replace("'", "''")
        conditions.append(f'"{pk_column}" >= \'{val}\'')
    if upper_bound is not None:
        val = str(upper_bound).replace("'", "''")
        conditions.append(f'"{pk_column}" < \'{val}\'')
    return " AND ".join(conditions) if conditions else "TRUE"


def drop_ch_table(ch_conn, ch_database, table_name, dry_run=False):
    """Drop a ClickHouse table if it exists."""
    sql = f"DROP TABLE IF EXISTS `{ch_database}`.`{table_name}`"
    logging.info(f"DROP TABLE: {sql}")
    if not dry_run:
        clickhouse_execute_conn(ch_conn, sql)


def truncate_ch_table(ch_conn, ch_database, table_name, dry_run=False):
    """Truncate a ClickHouse table if it exists (keeps schema, removes data)."""
    sql = f"TRUNCATE TABLE IF EXISTS `{ch_database}`.`{table_name}`"
    logging.info(f"TRUNCATE TABLE: {sql}")
    if not dry_run:
        clickhouse_execute_conn(ch_conn, sql)


def check_ch_table_has_data(ch_conn, ch_database, table_name):
    """Check if a ClickHouse table exists and has rows > 0.

    Returns (exists: bool, row_count: int).  If the table does not exist,
    returns (False, 0).
    """
    try:
        if not ch_table_exists(ch_conn, ch_database, table_name):
            return False, 0
        result = clickhouse_execute_conn(
            ch_conn,
            f"SELECT count() FROM `{ch_database}`.`{table_name}`"
        )
        row_count = int(result[0][0]) if result and result[0] else 0
        return True, row_count
    except Exception as e:
        logging.debug(f"check_ch_table_has_data({ch_database}.{table_name}): {e}")
        return False, 0


def get_pg_approx_row_count(pg_conn, pg_schema, table_name):
    """Get approximate row count from pg_stat_user_tables.n_live_tup.

    This is faster than COUNT(*) and good enough for ordering tables by size.
    Falls back to pg_class.reltuples if pg_stat is unavailable.
    """
    try:
        from ch_sink_tools.db.postgres import execute_pg
        rows = execute_pg(
            pg_conn,
            f"SELECT n_live_tup FROM pg_stat_user_tables "
            f"WHERE schemaname = '{pg_schema}' AND relname = '{table_name}'"
        )
        if rows and rows[0].get('n_live_tup') is not None:
            return int(rows[0]['n_live_tup'])
    except Exception:
        pass
    # Fallback to get_table_row_count from the existing module
    return get_table_row_count(pg_conn, pg_schema, table_name)


# ---------------------------------------------------------------------------
# Two-phase disk-based strategies
# ---------------------------------------------------------------------------

def psqlcopy_dump_table(table_name, column_names, pg_host, pg_port, pg_user,
                        pg_password, pg_database, pg_schema, dump_dir,
                        limit=None):
    """Dump a single table to a gzipped CSV file using psql COPY TO STDOUT.

    Phase 1 of the 'psql-copy' strategy.

    Returns (csv_file_path, elapsed_seconds).
    """
    dump_path = Path(dump_dir)
    dump_path.mkdir(parents=True, exist_ok=True)
    csv_file = dump_path / f"{table_name}.csv.gz"

    col_list = ", ".join(f'"{c}"' for c in column_names)
    query = f'SELECT {col_list} FROM "{pg_schema}"."{table_name}"'
    if limit:
        query += f' LIMIT {limit}'

    copy_sql = (
        f"COPY ({query}) TO STDOUT WITH "
        f"(FORMAT CSV, HEADER true, FORCE_QUOTE *)"
    )

    import tempfile
    qfile = tempfile.NamedTemporaryFile(
        mode='w', suffix='.sql', prefix='pg_dump_copy_',
        delete=False, dir='/tmp'
    )
    qfile.write(copy_sql)
    qfile.close()

    cmd = (
        f"PGPASSWORD='{pg_password}' PGTZ=UTC {pg_bin('psql')}"
        f" -h {pg_host}"
        f" -p {pg_port}"
        f" -U {pg_user}"
        f" -d \"{pg_database}\""
        f" -f {qfile.name}"
        f" | gzip -1 > {csv_file}"
    )

    t0 = time.time()
    logging.info(f"[{table_name}] psql-copy Phase 1: dumping to {csv_file}")
    logging.debug(f"[{table_name}] dump cmd: {cmd}")

    process = subprocess.Popen(
        cmd, shell=True, executable='/bin/bash',
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    for line in process.stdout:
        logging.debug(line.decode().strip())
    process.wait()

    try:
        os.unlink(qfile.name)
    except OSError:
        pass

    if process.returncode != 0:
        raise RuntimeError(
            f"psql-copy dump failed for {table_name} (rc={process.returncode})"
        )

    elapsed = time.time() - t0
    size_mb = csv_file.stat().st_size / (1024 * 1024) if csv_file.exists() else 0
    logging.info(
        f"[{table_name}] CSV dumped in {elapsed:.1f}s ({size_mb:.1f} MB)"
    )
    return str(csv_file), elapsed


def psqlcopy_load_table(table_name, csv_file, ch_host, ch_port, ch_user,
                        ch_password, ch_database, ch_table_name,
                        column_names, columns_meta,
                        ch_config_file=None, ch_secure=False,
                        dry_run=False):
    """Load a single table from a gzipped CSV file into ClickHouse.

    Phase 2 of the 'psql-copy' strategy.  Uses the existing
    build_ch_insert_cmd() to ensure type-safe insertion via input().

    Returns (label, rows_estimate, elapsed_seconds, success).
    """
    label = ch_table_name or table_name
    t_start = time.time()
    success = False

    try:
        ch_cmd = build_ch_insert_cmd(
            ch_host, ch_port, ch_user, ch_password,
            ch_database, ch_table_name or table_name, column_names, columns_meta,
            ch_config_file=ch_config_file, ch_secure=ch_secure,
        )

        # gunzip → clickhouse-client
        # The CSV has a header line; clickhouse-client with FORMAT CSV will
        # skip it when input_format_csv_detect_header=1 (the default).
        # However our build_ch_insert_cmd() uses FORMAT CSV without header
        # awareness, so strip the header with tail.
        load_cmd = f"set -o pipefail; gunzip -c {csv_file} | tail -n +2 | {ch_cmd}"

        logging.info(f"[{label}] psql-copy Phase 2: loading from {csv_file}")
        logging.debug(f"[{label}] load cmd: {load_cmd}")

        if not dry_run:
            rc = run_command(load_cmd)
            if rc != "0":
                raise RuntimeError(
                    f"psql-copy load failed for {label} (rc={rc})"
                )

        elapsed = time.time() - t_start
        logging.info(f"[{label}] Loaded in {elapsed:.1f}s")
        success = True

    except Exception as e:
        elapsed = time.time() - t_start
        logging.error(f"[{label}] psql-copy load FAILED after {elapsed:.1f}s: {e}")
        logging.error(traceback.format_exc())

    return (label, -1, time.time() - t_start, success)


def pgdump_dump_tables(pg_host, pg_port, pg_user, pg_password, pg_database,
                       pg_schema, dump_dir, tables=None, jobs=4):
    """Dump tables using pg_dump --format=directory.

    Phase 1 of the 'pgdump' strategy.

    Returns (dump_dir_path, elapsed_seconds).
    """
    dump_path = Path(dump_dir)
    # Clean and recreate
    if dump_path.exists():
        shutil.rmtree(dump_path)

    env = os.environ.copy()
    env['PGPASSWORD'] = pg_password

    cmd = [
        pg_bin('pg_dump'),
        '-h', pg_host, '-p', str(pg_port), '-U', pg_user, '-d', pg_database,
        '--format=directory', f'--jobs={jobs}',
        '--no-owner', '--no-privileges',
        '--data-only', '--schema', pg_schema,
        '-f', str(dump_path),
    ]
    if tables:
        for t in tables:
            cmd.extend(['-t', f'{pg_schema}."{t}"'])

    logging.info(
        f"pg_dump: dumping to {dump_path} with {jobs} jobs ..."
    )
    t0 = time.time()
    result = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"pg_dump failed (exit {result.returncode}): {result.stderr}"
        )
    elapsed = time.time() - t0

    # List dump files
    dat_files = sorted(dump_path.glob("*.dat.gz"))
    total_mb = sum(f.stat().st_size for f in dat_files) / (1024 * 1024)
    logging.info(
        f"pg_dump completed in {elapsed:.1f}s — "
        f"{len(dat_files)} files, {total_mb:.1f} MB total"
    )
    return str(dump_path), elapsed


def pgdump_load_table(table_name, dump_dir, ch_host, ch_port, ch_user,
                      ch_password, ch_database, ch_table_name,
                      column_names, columns_meta,
                      ch_config_file=None, ch_secure=False,
                      dry_run=False, approx_rows=-1):
    """Load a single table from a pg_dump directory into ClickHouse.

    Phase 2 of the 'pgdump' strategy.  Uses pg_restore to extract COPY-text
    data, then pipes through a Python transform to clickhouse-client.

    pg_restore COPY-text format:
      - Tab-separated fields
      - \\N for NULL
      - No header line

    Returns (label, rows_loaded, elapsed_seconds, success).
    """
    label = ch_table_name or table_name
    t_start = time.time()
    success = False
    row_count = 0

    try:
        ch_cmd = build_ch_insert_cmd(
            ch_host, ch_port, ch_user, ch_password,
            ch_database, ch_table_name or table_name, column_names, columns_meta,
            ch_config_file=ch_config_file, ch_secure=ch_secure,
        )

        # pg_restore outputs COPY-text (tab-separated).
        # We need to convert to CSV for our clickhouse-client INSERT.
        # Use a shell pipeline: pg_restore | python csv-converter | ch-client
        #
        # For simplicity, we use a helper script approach:
        # pg_restore --data-only -t table dump_dir → pipe to clickhouse-client
        # with TabSeparated format instead of CSV.

        # Build a simpler approach: pipe pg_restore output through awk to convert
        # tab-separated COPY-text to CSV, filtering out SQL lines.
        pg_restore_cmd = (
            f"{pg_bin('pg_restore')} --data-only -t {table_name} {dump_dir}"
        )

        # Extract only data lines (skip COPY/SQL lines), convert TSV to CSV
        # This awk script:
        #  - Skips lines starting with COPY or ending with \.
        #  - Skips SET/SELECT/-- lines
        #  - Converts tab-separated COPY-text to CSV
        #  - Handles NULL (\N) → empty unquoted field
        convert_cmd = (
            f"{pg_restore_cmd} 2>/dev/null"
            f" | awk -F'\\t' '"
            f"   /^COPY /{{next}} /^\\\\\\./{{next}} /^SET /{{next}} /^SELECT /{{next}} /^--/{{next}} /^$/{{next}}"
            f"   {{for(i=1;i<=NF;i++){{"
            f"     if($i==\"\\\\N\")printf \"\"; "
            f"     else{{gsub(/\"/,\"\\\"\\\"\",$i); printf \"\\\"%s\\\"\",$i}}"
            f"     if(i<NF)printf \",\"; else print \"\""
            f"   }}}}'"
        )

        pipe_cmd = f"set -o pipefail; {convert_cmd} | {ch_cmd}"

        logging.info(f"[{label}] pgdump Phase 2: loading from {dump_dir}")
        logging.debug(f"[{label}] pipe cmd: {pipe_cmd}")

        if not dry_run:
            rc = run_command(pipe_cmd)
            if rc != "0":
                raise RuntimeError(
                    f"pgdump load failed for {label} (rc={rc})"
                )

        elapsed = time.time() - t_start
        logging.info(f"[{label}] Loaded in {elapsed:.1f}s")
        success = True

    except Exception as e:
        elapsed = time.time() - t_start
        logging.error(f"[{label}] pgdump load FAILED after {elapsed:.1f}s: {e}")
        logging.error(traceback.format_exc())

    return (label, approx_rows, time.time() - t_start, success)


# ---------------------------------------------------------------------------
# Benchmark mode
# ---------------------------------------------------------------------------

def run_benchmark(args, pg_user, pg_password, ch_user, ch_password,
                  pg_server_timezone, override_config):
    """Benchmark loading strategies on a single table.

    Creates temporary CH tables, runs each strategy, compares throughput,
    and reports results.
    """
    table_name = args.benchmark_table
    if not table_name:
        logging.error("--benchmark requires --benchmark_table")
        sys.exit(1)

    bench_limit = getattr(args, 'benchmark_limit', 1_000_000)
    pg_schema = args.pg_schema[0] if isinstance(args.pg_schema, list) else args.pg_schema

    logging.info("=" * 72)
    logging.info(f"BENCHMARK: {table_name} (limit {bench_limit:,} rows)")
    logging.info("=" * 72)

    # Get table metadata
    pg_conn = get_postgres_connection(
        args.pg_host, pg_user, pg_password, args.pg_port, args.pg_database
    )
    columns_meta = get_table_columns(
        pg_conn, pg_schema, table_name,
        pg_server_timezone=pg_server_timezone,
        override_config=override_config,
        pg_database=args.pg_database,
    )
    pk_cols = get_table_pk(pg_conn, pg_schema, table_name)
    approx_rows = get_table_row_count(pg_conn, pg_schema, table_name)
    pg_conn.close()

    column_names = [c['column_name'] for c in columns_meta]

    # Resolve CH database name
    if args.ch_database:
        ch_database = args.ch_database
    else:
        from ch_sink_tools.db_dump.naming import resolve_ch_names
        ch_database, _ = resolve_ch_names(
            args.pg_database, pg_schema, table_name,
            args.ch_database_template, args.ch_table_template,
        )

    strategies = ['streaming', 'psql-copy']
    results = {}

    for strategy in strategies:
        logging.info("")
        logging.info("-" * 72)
        logging.info(f"Strategy: {strategy}")
        logging.info("-" * 72)

        bench_table = f"_benchmark_{strategy.replace('-', '_')}_{table_name}"

        # Ensure CH database exists
        ch_conn = clickhouse_connection(
            args.ch_host, database='default',
            user=ch_user, password=ch_password,
            port=args.ch_port, secure=args.ch_secure,
        )
        try:
            ensure_ch_database(ch_conn, ch_database)
            # Drop existing benchmark table
            try:
                clickhouse_execute_conn(
                    ch_conn,
                    f"DROP TABLE IF EXISTS `{ch_database}`.`{bench_table}`"
                )
            except Exception:
                pass
        finally:
            ch_conn.close()

        # Create benchmark table
        ch_conn = clickhouse_connection(
            args.ch_host, database=ch_database,
            user=ch_user, password=ch_password,
            port=args.ch_port, secure=args.ch_secure,
        )
        try:
            create_ch_table(
                ch_conn, ch_database, bench_table, columns_meta, pk_cols,
                override_config=override_config,
                schema=pg_schema, pg_database=args.pg_database,
            )
        finally:
            ch_conn.close()

        t0 = time.time()

        if strategy == 'streaming':
            # Direct pipe: psql COPY → clickhouse-client
            result = load_table(
                table_name=table_name,
                pg_host=args.pg_host, pg_port=args.pg_port,
                pg_user=pg_user, pg_password=pg_password,
                pg_database=args.pg_database, pg_schema=pg_schema,
                ch_host=args.ch_host, ch_port=args.ch_port,
                ch_user=ch_user, ch_password=ch_password,
                ch_database=ch_database,
                ch_config_file=args.ch_config_file,
                ch_secure=args.ch_secure,
                pg_server_timezone=pg_server_timezone,
                ch_table_name=bench_table,
            )
            total_elapsed = time.time() - t0
            results[strategy] = {
                'dump_time': 0, 'load_time': total_elapsed,
                'total_time': total_elapsed,
                'rows': approx_rows,
                'status': 'OK' if result[3] else 'ERROR',
            }

        elif strategy == 'psql-copy':
            dump_dir = f"/tmp/benchmark_psql_copy"
            # Phase 1: dump
            try:
                csv_file, dump_elapsed = psqlcopy_dump_table(
                    table_name, column_names,
                    args.pg_host, args.pg_port, pg_user, pg_password,
                    args.pg_database, pg_schema, dump_dir,
                    limit=bench_limit,
                )
                # Phase 2: load
                result = psqlcopy_load_table(
                    table_name, csv_file,
                    args.ch_host, args.ch_port, ch_user, ch_password,
                    ch_database, bench_table,
                    column_names, columns_meta,
                    ch_config_file=args.ch_config_file,
                    ch_secure=args.ch_secure,
                )
                total_elapsed = time.time() - t0
                results[strategy] = {
                    'dump_time': dump_elapsed,
                    'load_time': result[2],
                    'total_time': total_elapsed,
                    'rows': bench_limit,
                    'status': 'OK' if result[3] else 'ERROR',
                }
            except Exception as e:
                total_elapsed = time.time() - t0
                results[strategy] = {
                    'dump_time': 0, 'load_time': 0,
                    'total_time': total_elapsed,
                    'rows': 0,
                    'status': 'ERROR',
                    'error': str(e),
                }

        # Verify row count
        ch_conn = clickhouse_connection(
            args.ch_host, database=ch_database,
            user=ch_user, password=ch_password,
            port=args.ch_port, secure=args.ch_secure,
        )
        try:
            r = clickhouse_execute_conn(
                ch_conn,
                f"SELECT count() FROM `{ch_database}`.`{bench_table}`"
            )
            ch_count = int(r[0][0]) if r and r[0] else 0
            logging.info(f"[{strategy}] CH row count: {ch_count:,}")
        except Exception as e:
            logging.warning(f"[{strategy}] Could not verify count: {e}")
        finally:
            # Cleanup benchmark table
            try:
                clickhouse_execute_conn(
                    ch_conn,
                    f"DROP TABLE IF EXISTS `{ch_database}`.`{bench_table}`"
                )
            except Exception:
                pass
            ch_conn.close()

    # --- Print comparison ---
    logging.info("")
    logging.info("=" * 72)
    logging.info(f"BENCHMARK RESULTS — {table_name}")
    logging.info("=" * 72)
    logging.info(
        f"{'STRATEGY':15s} {'DUMP(s)':>10s} {'LOAD(s)':>10s} "
        f"{'TOTAL(s)':>10s} {'ROWS':>12s} {'ROWS/S':>10s}"
    )
    logging.info("-" * 72)
    for strat, r in results.items():
        rows_per_sec = r['rows'] / r['total_time'] if r['total_time'] > 0 and r['rows'] > 0 else 0
        error_msg = f" ERROR: {r.get('error', '')}" if r['status'] == 'ERROR' else ""
        logging.info(
            f"{strat:15s} {r['dump_time']:10.1f} {r['load_time']:10.1f} "
            f"{r['total_time']:10.1f} {r['rows']:12,} {rows_per_sec:10.0f}{error_msg}"
        )
    logging.info("=" * 72)

    # Recommend the winner
    valid = {k: v for k, v in results.items() if v['status'] == 'OK'}
    if valid:
        winner = min(valid, key=lambda k: valid[k]['total_time'])
        rate = valid[winner]['rows'] / valid[winner]['total_time'] if valid[winner]['total_time'] > 0 else 0
        logging.info(
            f"WINNER: {winner} ({valid[winner]['total_time']:.1f}s total, "
            f"{rate:.0f} rows/s)"
        )


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
                (pg_user, f'"{schema}"."{table_name}"')
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
# Config file loading
# ---------------------------------------------------------------------------

logger = logging.getLogger(__name__)


def _debezium_list_to_regex(value: str) -> str:
    """Convert a Debezium comma-separated list to a regex pattern.

    If the value looks like a plain comma-separated list (no regex metacharacters),
    convert 'a,b,c' to 'a|b|c'. For fully-qualified names like 'schema.table',
    extract the table part.

    If the value already contains regex metacharacters, return as-is.
    """
    regex_chars = {'.*', '^', '$', '[', ']', '(', ')', '+', '?'}
    if any(ch in value for ch in regex_chars):
        return value  # Already a regex

    # Split by comma, strip whitespace
    parts = [p.strip() for p in value.split(',') if p.strip()]

    # If parts contain dots (schema.table), extract the last component
    extracted = []
    for part in parts:
        if '.' in part:
            extracted.append(part.split('.')[-1])  # Take table name only
        else:
            extracted.append(part)

    return '|'.join(extracted)


def _debezium_schema_list_to_regex(value: str) -> str:
    """Convert a Debezium comma-separated schema list to a regex pattern.

    Similar to _debezium_list_to_regex but keeps full values since
    schema names are simple identifiers (no dotted notation).

    If the value already contains regex metacharacters, return as-is.
    """
    regex_chars = {'.*', '^', '$', '[', ']', '(', ')', '+', '?'}
    if any(ch in value for ch in regex_chars):
        return value  # Already a regex

    # Split by comma, strip whitespace
    parts = [p.strip() for p in value.split(',') if p.strip()]
    return '|'.join(parts)


def parse_sink_connector_config(config: dict) -> dict:
    """Parse a sink-connector YAML config and map to dumper CLI arg names.

    The sink-connector config uses Java property-style keys like 'database.hostname'.
    This maps them to the Python dumper's argparse namespace keys.
    """
    mapping = {}

    # PostgreSQL connection
    if 'database.hostname' in config:
        mapping['pg_host'] = config['database.hostname']
    if 'database.port' in config:
        mapping['pg_port'] = int(config['database.port'])
    if 'database.dbname' in config:
        mapping['pg_database'] = config['database.dbname']
    if 'database.user' in config:
        mapping['pg_user'] = config['database.user']
    if 'database.password' in config:
        mapping['pg_password'] = config['database.password']

    # ClickHouse connection
    if 'clickhouse.server.url' in config:
        mapping['ch_host'] = config['clickhouse.server.url']
    if 'clickhouse.server.port' in config:
        cfg_port = int(config['clickhouse.server.port'])
        # The Java connector uses the HTTP port (8123/8443).  The Python
        # dumper uses clickhouse-client (native protocol, default 9000/9440).
        # Auto-convert well-known HTTP ports to their native equivalents.
        http_to_native = {8123: 9000, 8443: 9440}
        mapping['ch_port'] = http_to_native.get(cfg_port, cfg_port)
    if 'clickhouse.server.user' in config:
        mapping['ch_user'] = config['clickhouse.server.user']
    if 'clickhouse.server.password' in config:
        mapping['ch_password'] = config['clickhouse.server.password']

    # Database/schema/table naming
    if 'clickhouse.server.database' in config:
        db_value = config['clickhouse.server.database']
        # If it contains {{ }}, treat as a template; otherwise as literal ch_database
        if '{{' in str(db_value) and '}}' in str(db_value):
            mapping['ch_database_template'] = db_value
        else:
            mapping['ch_database'] = db_value

    # Schema filtering
    if 'schema.include.list' in config:
        mapping['pg_schema_include'] = _debezium_schema_list_to_regex(
            str(config['schema.include.list']))
    if 'schema.exclude.list' in config:
        mapping['pg_schema_exclude'] = _debezium_schema_list_to_regex(
            str(config['schema.exclude.list']))

    # Table filtering
    if 'table.include.list' in config:
        mapping['pg_table_include'] = _debezium_list_to_regex(
            str(config['table.include.list']))
    if 'table.exclude.list' in config:
        mapping['pg_table_exclude'] = _debezium_list_to_regex(
            str(config['table.exclude.list']))

    # Database filtering
    if 'database.include.list' in config:
        mapping['pg_database_include'] = _debezium_list_to_regex(
            str(config['database.include.list']))
    if 'database.exclude.list' in config:
        mapping['pg_database_exclude'] = _debezium_list_to_regex(
            str(config['database.exclude.list']))

    # ClickHouse database prefix / schema suffix (naming convention)
    # These config keys control how the Java connector builds its CH database
    # name.  We translate them into a ch_database_template so that
    # resolve_ch_names() produces the same result.
    #
    #   clickhouse.common.database.prefix  →  prepended to {{ database }}
    #   clickhouse.common.schema.template  →  appended (contains {{ schema }})
    #   clickhouse.database.schema.suffix  →  "true" to enable the above
    #
    # Example:  prefix="litellm_prod_", schema_template="__{{ schema }}"
    #   → ch_database_template = "litellm_prod_{{ database }}__{{ schema }}"
    #   → for db=app, schema=public  →  litellm_prod_app__public
    db_prefix = config.get('clickhouse.common.database.prefix', '')
    schema_template = config.get('clickhouse.common.schema.template', '')
    use_schema_suffix = str(config.get('clickhouse.database.schema.suffix', 'false')).lower() == 'true'

    if db_prefix or (use_schema_suffix and schema_template):
        tmpl = f"{db_prefix}{{{{ database }}}}"
        if use_schema_suffix and schema_template:
            tmpl += schema_template
        mapping['ch_database_template'] = tmpl
        # If ch_database was already set as a literal, remove it — the template
        # is more specific and will produce the correct per-schema names.
        mapping.pop('ch_database', None)

    # Connector name (used for offset key)
    # Priority: "name" (top-level connector name) > "database.server.name"
    # The Java DebeziumOffsetStorage.getOffsetKey() uses the connector "name"
    # property to build the offset key, NOT the database.server.name.
    if 'name' in config:
        mapping['connector_name'] = config['name']
    elif 'database.server.name' in config:
        mapping['connector_name'] = config['database.server.name']

    # Offset storage — support both key variants
    if 'offset.storage.jdbc.table.name' in config:
        mapping['offset_table'] = config['offset.storage.jdbc.table.name']
    elif 'offset.storage.jdbc.offset.table.name' in config:
        mapping['offset_table'] = config['offset.storage.jdbc.offset.table.name']

    return mapping


def load_config_file(config_path: str) -> dict:
    """Load a YAML config file and return as a dumper-compatible dict.

    Auto-detects if the file is a sink-connector config (with keys like
    'database.hostname') or a dumper-specific config (with keys like 'pg_host').
    Sink-connector configs are mapped to dumper CLI arg names.

    When a sink-connector config is detected, any ``column_type_override.*``
    entries are also parsed via
    :meth:`ColumnTypeOverrideConfig.from_connector_properties` and stored
    under the special key ``_column_type_override_config`` in the returned
    dict.
    """
    import yaml
    with open(config_path, 'r') as f:
        config = yaml.safe_load(f)
    if not config:
        return {}

    # Auto-detect: if config has sink-connector style keys, parse them
    sink_connector_keys = {'database.hostname', 'database.port', 'database.dbname',
                           'clickhouse.server.url', 'database.server.name'}
    if sink_connector_keys & set(config.keys()):
        logger.info("Detected sink-connector config format, mapping to dumper parameters")
        mapping = parse_sink_connector_config(config)

        # Also extract column type override entries from the raw config
        connector_override_config = ColumnTypeOverrideConfig.from_connector_properties(config)
        if connector_override_config:
            mapping['_column_type_override_config'] = connector_override_config

        return mapping

    # Otherwise treat as dumper-native config
    return config


def merge_config_with_args(args, config: dict):
    """Merge config file values into args namespace.

    CLI arguments that were *explicitly* passed on the command line always win.
    Config file values fill in everything else — even args whose argparse
    ``default`` is not None (e.g. ``--ch_database_template`` defaults to
    ``'{{ database }}'``).

    We rely on the ``_explicitly_set`` attribute stamped onto the namespace
    by ``main()`` right after ``parse_args()``.  Any key listed there was
    provided on the CLI and must not be overwritten.
    """
    explicitly_set = getattr(args, '_explicitly_set', set())
    for key, value in config.items():
        if key in explicitly_set:
            continue            # CLI wins
        if hasattr(args, key):
            setattr(args, key, value)
    return args


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Parallel PostgreSQL → ClickHouse snapshot loader.\n"
                    "Pipes COPY CSV output directly to clickhouse-client."
    )

    # -- PostgreSQL connection (env var fallbacks) ----------------------------
    parser.add_argument('--pg_host', required=False,
                        default=os.environ.get('PG_HOST'),
                        help='PostgreSQL host (env: PG_HOST)')
    parser.add_argument('--pg_port', type=int,
                        default=int(os.environ.get('PG_PORT', '5432')),
                        help='PostgreSQL port (default: 5432, env: PG_PORT)')
    parser.add_argument('--pg_database', required=False,
                        default=os.environ.get('PG_DATABASE'),
                        help='PostgreSQL database name (env: PG_DATABASE)')
    parser.add_argument('--pg_user', required=False,
                        default=os.environ.get('PG_USER'),
                        help='PostgreSQL user (env: PG_USER)')
    parser.add_argument('--pg_password', required=False,
                        default=os.environ.get('PG_PASSWORD'),
                        help='PostgreSQL password (env: PG_PASSWORD; prefer ~/.pgpass)')
    parser.add_argument('--pg_schema', type=str, nargs='+', default=['public'],
                        help='PostgreSQL schema(s) to dump (default: public)')

    # -- Schema filtering -----------------------------------------------------
    parser.add_argument('--pg_schema_include', type=str, default=None,
                        help='Regex pattern to include schemas (e.g., "public|analytics")')
    parser.add_argument('--pg_schema_exclude', type=str, default=None,
                        help='Regex pattern to exclude schemas (e.g., "pg_.*|information_schema")')

    # -- ClickHouse connection (env var fallbacks) ----------------------------
    parser.add_argument('--ch_host', required=False,
                        default=os.environ.get('CH_HOST'),
                        help='ClickHouse host (env: CH_HOST)')
    parser.add_argument('--ch_port', type=int,
                        default=int(os.environ.get('CH_PORT', '9000')),
                        help='ClickHouse native port (default: 9000, env: CH_PORT)')
    parser.add_argument('--ch_database', required=False,
                        default=os.environ.get('CH_DATABASE'),
                        help='ClickHouse target database (env: CH_DATABASE; overrides --ch_database_template if set)')
    parser.add_argument('--ch_user', required=False,
                        default=os.environ.get('CH_USER', 'default'),
                        help='ClickHouse user (default: default, env: CH_USER)')
    parser.add_argument('--ch_password', required=False,
                        default=os.environ.get('CH_PASSWORD'),
                        help='ClickHouse password (env: CH_PASSWORD)')
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
    parser.add_argument('--pg_table_include', type=str, default=None,
                        help='Regex pattern to include tables (e.g., "users|orders")')
    parser.add_argument('--pg_table_exclude', type=str, default=None,
                        help='Regex pattern to exclude tables (e.g., "temp_.*|_backup$")')

    # -- Naming templates -----------------------------------------------------
    parser.add_argument('--ch_database_template', type=str, default='{{ database }}',
                        help='Jinja-style template for ClickHouse database name (default: "{{ database }}" = pass-through)')
    parser.add_argument('--ch_table_template', type=str, default='{{ table }}',
                        help='Jinja-style template for ClickHouse table name (default: "{{ table }}" = pass-through)')

    # -- Config file ----------------------------------------------------------
    parser.add_argument('--config', type=str, default=None,
                        help='Path to YAML config file (CLI args override config file values)')

    # -- Column type overrides ------------------------------------------------
    parser.add_argument('--column_type_overrides_file', type=str, default=None,
                        help='Path to YAML file with column type override configuration')
    parser.add_argument('--column_type_overrides', type=str, default=None,
                        help=(
                            'Inline column type overrides in CLI format. '
                            'Format: "direct:schema.table.col=CHType,'
                            'alias:schema.table.col=CHType|expression"'
                        ))

    # -- Load options ---------------------------------------------------------
    parser.add_argument('--threads', type=int, default=4,
                        help='Number of parallel table threads (default: 4)')
    parser.add_argument('--batch_size', type=int, default=None,
                        help='Batch size hint (not enforced in direct-pipe mode)')
    parser.add_argument('--drop_existing', dest='drop_existing',
                        action='store_true', default=False,
                        help='DROP existing ClickHouse tables before recreating them')
    parser.add_argument('--truncate', dest='truncate',
                        action='store_true', default=False,
                        help='Truncate existing CH tables instead of dropping them')
    parser.add_argument('--skip_existing', dest='skip_existing',
                        action='store_true', default=False,
                        help='Skip tables that already have data in ClickHouse')
    parser.add_argument('--order_by_size', dest='order_by_size',
                        action='store_true', default=True,
                        help='Sort tables by approx row count ascending (small first, default: True)')
    parser.add_argument('--no_order_by_size', dest='order_by_size',
                        action='store_false',
                        help='Disable size-based table ordering')
    parser.add_argument('--segment_threshold', type=int, default=1_000_000,
                        help=(
                            'Row count above which a table is split into parallel '
                            'PK-range segments for loading (default: 1,000,000). '
                            'Set to 0 to disable segmentation.'
                        ))
    parser.add_argument('--segments_per_table', type=int, default=4,
                        help=(
                            'Number of parallel segments per large table '
                            '(default: 4). Only applies to tables exceeding '
                            '--segment_threshold rows.'
                        ))

    # -- Strategy (streaming vs two-phase disk-based) -------------------------
    parser.add_argument('--strategy', type=str, default='streaming',
                        choices=['streaming', 'psql-copy', 'pgdump'],
                        help=(
                            'Data loading strategy (default: streaming). '
                            '"streaming" pipes psql COPY directly to clickhouse-client. '
                            '"psql-copy" dumps to gzipped CSV files first, then loads. '
                            '"pgdump" uses pg_dump --format=directory, then loads.'
                        ))
    parser.add_argument('--dump_dir', type=str, default='/tmp/pg_dump',
                        help='Directory for intermediate dump files (default: /tmp/pg_dump)')
    parser.add_argument('--dump_only', dest='dump_only',
                        action='store_true', default=False,
                        help='Only dump data to disk, do not load into ClickHouse')
    parser.add_argument('--load_only', dest='load_only',
                        action='store_true', default=False,
                        help='Only load from existing dump files, do not dump from PG')
    parser.add_argument('--dump_jobs', type=int, default=4,
                        help='Number of parallel dump workers for pg_dump (default: 4)')

    # -- PG binary path -------------------------------------------------------
    parser.add_argument('--pg_bin_dir', type=str,
                        default=os.environ.get('PG_BIN_DIR', ''),
                        help='Path to PG binaries (psql, pg_dump, pg_restore). Env: PG_BIN_DIR')

    # -- Benchmark mode -------------------------------------------------------
    parser.add_argument('--benchmark', dest='benchmark',
                        action='store_true', default=False,
                        help='Benchmark loading strategies on a single table')
    parser.add_argument('--benchmark_table', type=str, default=None,
                        help='Table name to benchmark (required with --benchmark)')
    parser.add_argument('--benchmark_limit', type=int, default=1_000_000,
                        help='Row limit for benchmark (default: 1,000,000)')

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

    # Track which CLI args were explicitly provided (not just defaulted).
    # This lets merge_config_with_args() know which values the user typed
    # on the command line so they are never overwritten by the config file.
    _explicitly_set = set()
    for action in parser._actions:
        if action.dest == 'help':
            continue
        # If the current value differs from the argparse default,
        # the user explicitly provided it.
        if getattr(args, action.dest) != action.default:
            _explicitly_set.add(action.dest)
    args._explicitly_set = _explicitly_set

    # -- Config file loading (CLI args override config values) ----------------
    if args.config:
        config = load_config_file(args.config)
        logger.info(f"Loaded {len(config)} parameters from config file: {args.config}")
        merge_config_with_args(args, config)

    # -- Column type override config ------------------------------------------
    # Priority: CLI args > connector config file > None
    override_config = ColumnTypeOverrideConfig.from_cli_args(
        overrides_file=args.column_type_overrides_file,
        overrides_string=args.column_type_overrides,
    )
    if not override_config and args.config:
        # Fall back to overrides embedded in the sink-connector config file
        override_config = config.pop('_column_type_override_config', None)
    if override_config:
        logging.info(f"Column type overrides: {override_config}")

    # -- PG_BIN_DIR setup (CLI > env var) -------------------------------------
    global PG_BIN_DIR
    if getattr(args, 'pg_bin_dir', ''):
        PG_BIN_DIR = args.pg_bin_dir

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

    # -- Template validation --------------------------------------------------
    validate_template(args.ch_database_template, 'ch_database_template')
    validate_template(args.ch_table_template, 'ch_table_template',
                      required_vars=frozenset({'table'}))

    # -- Post-config required-arg validation ----------------------------------
    missing = []
    if not args.pg_host:
        missing.append('--pg_host')
    if not args.pg_database:
        missing.append('--pg_database')
    if not args.ch_host:
        missing.append('--ch_host')
    if missing:
        parser.error(
            f"the following arguments are required (via CLI or config file): "
            f"{', '.join(missing)}"
        )

    # -- Dependency checks ----------------------------------------------------
    assert check_program_exists(pg_bin('psql')), \
        f"psql should be in the PATH (looked for: {pg_bin('psql')})"
    assert check_program_exists('clickhouse-client'), \
        "clickhouse-client should be in the PATH"
    if getattr(args, 'strategy', 'streaming') == 'pgdump':
        assert check_program_exists(pg_bin('pg_dump')), \
            f"pg_dump should be in the PATH (looked for: {pg_bin('pg_dump')})"
        assert check_program_exists(pg_bin('pg_restore')), \
            f"pg_restore should be in the PATH (looked for: {pg_bin('pg_restore')})"

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

    # -- Benchmark early exit ------------------------------------------------
    if args.benchmark:
        if not args.benchmark_table:
            logging.error("--benchmark requires --benchmark_table")
            sys.exit(1)
        run_benchmark(
            args,
            pg_user=pg_user,
            pg_password=pg_password,
            ch_user=ch_user,
            ch_password=ch_password,
            pg_server_timezone=get_server_timezone(
                get_postgres_connection(
                    args.pg_host, pg_user, pg_password,
                    args.pg_port, args.pg_database
                )
            ),
            override_config=override_config,
        )
        sys.exit(0)

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
        # Step 2: Discover schemas
        # --------------------------------------------------------------------
        logging.info("=== Step 2: Discovering schemas ===")
        if args.pg_schema_include or args.pg_schema_exclude:
            # Use regex discovery from DB
            schemas = get_schemas(pg_conn_main,
                                  include_regex=args.pg_schema_include,
                                  exclude_regex=args.pg_schema_exclude)
            logging.info(f"Schemas discovered via regex ({len(schemas)}): {schemas}")
        else:
            # Use explicit list from --pg_schema (default: ['public'])
            schemas = args.pg_schema
            logging.info(f"Schemas from --pg_schema ({len(schemas)}): {schemas}")

        if not schemas:
            logging.error("No schemas found matching the specified criteria")
            pg_conn_main.close()
            sys.exit(1)

        # --------------------------------------------------------------------
        # Step 2b: Discover tables per schema and build work items
        # --------------------------------------------------------------------
        logging.info("=== Step 2b: Discovering tables per schema ===")
        # work_items: list of (schema_name, table_name, ch_database, ch_table)
        work_items = []
        for schema_name in schemas:
            # Discover tables for this schema using legacy --tables/--exclude_tables
            schema_tables = get_tables(
                pg_conn_main,
                schema_name,
                include_regex=args.tables,
                exclude_regex=args.exclude_tables,
            )
            # Apply additional --pg_table_include / --pg_table_exclude regex filters
            schema_tables = filter_tables_by_regex(
                schema_tables,
                include_pattern=args.pg_table_include,
                exclude_pattern=args.pg_table_exclude,
            )

            for table_name in schema_tables:
                # Resolve ClickHouse database name
                if args.ch_database:
                    # Explicit --ch_database flag takes highest priority (backward compat)
                    ch_database = args.ch_database
                else:
                    ch_database, _ = resolve_ch_names(
                        args.pg_database, schema_name, table_name,
                        args.ch_database_template, args.ch_table_template,
                    )

                # Resolve ClickHouse table name
                _, ch_table = resolve_ch_names(
                    args.pg_database, schema_name, table_name,
                    args.ch_database_template, args.ch_table_template,
                )

                logging.info(
                    f"Mapping PG {args.pg_database}.{schema_name}.{table_name} "
                    f"\u2192 CH {ch_database}.{ch_table}"
                )
                work_items.append((schema_name, table_name, ch_database, ch_table))

        if not work_items:
            logging.error(
                f"No tables found in schemas {schemas} "
                f"matching '{args.tables}'"
            )
            pg_conn_main.close()
            sys.exit(1)

        logging.info(f"Total tables to process: {len(work_items)}")

        # -- Order by size (ascending) so small tables finish first ----------
        if args.order_by_size and len(work_items) > 1:
            logging.info("Sorting tables by approximate row count (ascending)...")
            pg_conn_sort = get_postgres_connection(
                args.pg_host, pg_user, pg_password,
                args.pg_port, args.pg_database
            )
            try:
                size_map = {}
                for schema_name, table_name, _, _ in work_items:
                    key = (schema_name, table_name)
                    if key not in size_map:
                        size_map[key] = get_pg_approx_row_count(
                            pg_conn_sort, schema_name, table_name
                        )
                work_items.sort(
                    key=lambda item: size_map.get(
                        (item[0], item[1]), 0
                    )
                )
                logging.info(
                    "Table order (smallest first): %s",
                    ", ".join(
                        f"{t[1]}(~{size_map.get((t[0], t[1]), 0):,})"
                        for t in work_items
                    ),
                )
            finally:
                pg_conn_sort.close()

        # Collect unique (schema, tables) pairs for privilege validation
        schema_tables_map = {}
        for schema_name, table_name, _, _ in work_items:
            schema_tables_map.setdefault(schema_name, []).append(table_name)

        # --------------------------------------------------------------------
        # Step 2c: Validate PostgreSQL user privileges (per schema)
        # --------------------------------------------------------------------
        logging.info("=== Step 2c: Validating PostgreSQL user privileges ===")
        for schema_name, tables_in_schema in schema_tables_map.items():
            privs_ok = validate_postgres_privileges(
                pg_conn_main,
                pg_user,
                schema_name,
                tables_in_schema,
                config={'connector_name': args.connector_name},
            )
            if not privs_ok:
                logging.error(
                    "Aborting: PostgreSQL user '%s' is missing critical privileges "
                    "for schema '%s'. See errors above for details.",
                    pg_user, schema_name,
                )
                pg_conn_main.close()
                sys.exit(1)

        # Create the heartbeat table on the source PostgreSQL database
        logging.info("=== Step 2d: Ensuring heartbeat table exists ===")
        ensure_heartbeat_table(pg_conn_main, pg_user)

        pg_conn_main.close()

        # Collect unique CH database names that need to be created
        ch_databases_needed = sorted(set(
            ch_db for _, _, ch_db, _ in work_items
        ))

        # --------------------------------------------------------------------
        # Step 3: Create ClickHouse database(s) and tables
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
                for ch_db in ch_databases_needed:
                    ensure_ch_database(ch_conn_default, ch_db,
                                       dry_run=args.dry_run)
            finally:
                ch_conn_default.close()

            # Create tables in each CH database
            for ch_db in ch_databases_needed:
                ch_conn_schema = clickhouse_connection(
                    args.ch_host,
                    database=ch_db,
                    user=ch_user,
                    password=ch_password,
                    port=args.ch_port,
                    secure=args.ch_secure,
                )
                try:
                    for schema_name, table_name, item_ch_db, ch_table in work_items:
                        if item_ch_db != ch_db:
                            continue
                        pg_conn_t = get_postgres_connection(
                            args.pg_host, pg_user, pg_password,
                            args.pg_port, args.pg_database
                        )
                        columns_meta = get_table_columns(
                            pg_conn_t, schema_name, table_name,
                            pg_server_timezone=pg_server_timezone,
                            override_config=override_config,
                            pg_database=args.pg_database,
                        )
                        pk_cols = get_table_pk(
                            pg_conn_t, schema_name, table_name
                        )
                        pg_conn_t.close()

                        # -- Drop or truncate existing table -----------------
                        if args.drop_existing:
                            drop_ch_table(
                                ch_conn_schema, ch_db, ch_table,
                                dry_run=args.dry_run,
                            )
                        elif args.truncate:
                            truncate_ch_table(
                                ch_conn_schema, ch_db, ch_table,
                                dry_run=args.dry_run,
                            )

                        # -- Override reconciliation -------------------------
                        # If the table already exists AND overrides are
                        # configured, reconcile before (re-)creating:
                        #   - ALIAS overrides  → auto-ALTER
                        #   - DIRECT overrides → raise on mismatch
                        if override_config and override_config.has_overrides():
                            if not args.dry_run and ch_table_exists(
                                ch_conn_schema, ch_db, ch_table
                            ):
                                logging.info(
                                    f"Table {ch_db}.{ch_table} already exists "
                                    f"— running override reconciliation"
                                )
                                reconcile_overrides_with_existing_table(
                                    ch_conn=ch_conn_schema,
                                    ch_database=ch_db,
                                    table_name=ch_table,
                                    schema=schema_name,
                                    override_config=override_config,
                                    database=args.pg_database,
                                )

                        create_ch_table(
                            ch_conn_schema,
                            ch_db,
                            ch_table,
                            columns_meta,
                            pk_cols,
                            dry_run=args.dry_run,
                            override_config=override_config,
                            schema=schema_name,
                            pg_database=args.pg_database,
                        )
                finally:
                    ch_conn_schema.close()

        # --------------------------------------------------------------------
        # Step 4: Load data in parallel (with PK-range segmentation for
        # large tables)
        # --------------------------------------------------------------------
        if not args.schema_only:
            strategy = args.strategy

            # -- Skip-existing: filter out tables that already have data -----
            if args.skip_existing:
                ch_conn_check = clickhouse_connection(
                    args.ch_host,
                    database='default',
                    user=ch_user,
                    password=ch_password,
                    port=args.ch_port,
                    secure=args.ch_secure,
                )
                try:
                    filtered_items = []
                    for item in work_items:
                        schema_name, table_name, ch_database, ch_table = item
                        exists, row_count = check_ch_table_has_data(
                            ch_conn_check, ch_database, ch_table
                        )
                        if exists and row_count > 0:
                            logging.info(
                                f"[{ch_table}] Skipping — already has "
                                f"{row_count:,} rows in CH (--skip_existing)"
                            )
                        else:
                            filtered_items.append(item)
                finally:
                    ch_conn_check.close()

                skipped = len(work_items) - len(filtered_items)
                if skipped > 0:
                    logging.info(
                        f"Skipped {skipped} tables with existing data; "
                        f"{len(filtered_items)} remaining"
                    )
                work_items = filtered_items

                if not work_items:
                    logging.info("All tables already have data — nothing to load")
                    # Jump directly to Step 5 (offset writing)
                    strategy = None  # signal to skip loading

            # ================================================================
            # Strategy: pgdump (two-phase: pg_dump → disk → clickhouse-client)
            # ================================================================
            if strategy == 'pgdump':
                dump_dir = args.dump_dir

                # Phase 1: dump
                if not args.load_only:
                    # Collect table list for pg_dump
                    pg_tables_for_dump = [
                        f"{schema_name}.{table_name}"
                        for schema_name, table_name, _, _ in work_items
                    ]
                    pgdump_dump_tables(
                        pg_host=args.pg_host,
                        pg_port=args.pg_port,
                        pg_user=pg_user,
                        pg_password=pg_password,
                        pg_database=args.pg_database,
                        pg_schemas=[s for s in schemas],
                        dump_dir=dump_dir,
                        dump_jobs=args.dump_jobs,
                        dry_run=args.dry_run,
                    )

                if args.dump_only:
                    logging.info(
                        "pgdump Phase 1 complete (--dump_only). "
                        f"Dump directory: {dump_dir}"
                    )
                else:
                    # Phase 2: load from dump directory
                    logging.info(
                        f"=== Step 4: Loading {len(work_items)} tables "
                        f"from pgdump directory ({dump_dir}) "
                        f"with {args.threads} threads ==="
                    )
                    results = []
                    with concurrent.futures.ThreadPoolExecutor(
                        max_workers=args.threads,
                        thread_name_prefix='pgdump_loader',
                    ) as executor:
                        futures = {}
                        for schema_name, table_name, ch_database, ch_table in work_items:
                            future = executor.submit(
                                pgdump_load_table,
                                table_name=table_name,
                                dump_dir=dump_dir,
                                ch_host=args.ch_host,
                                ch_port=args.ch_port,
                                ch_user=ch_user,
                                ch_password=ch_password,
                                ch_database=ch_database,
                                ch_table=ch_table,
                                pg_schema=schema_name,
                                ch_secure=args.ch_secure,
                                dry_run=args.dry_run,
                            )
                            futures[future] = ch_table

                        for future in concurrent.futures.as_completed(futures):
                            tbl_label = futures[future]
                            try:
                                result = future.result()
                                results.append(result)
                            except Exception as exc:
                                logging.error(
                                    f"[{tbl_label}] raised an exception: {exc}"
                                )
                                logging.error(traceback.format_exc())
                                results.append((tbl_label, -1, 0, False))

                    # Summary
                    logging.info("=== Load Summary (pgdump) ===")
                    failed = []
                    total_rows = 0
                    for (tbl, rows, elapsed, ok) in sorted(
                        results, key=lambda r: r[0]
                    ):
                        status = "OK" if ok else "FAILED"
                        rate = (
                            rows / elapsed
                            if elapsed > 0 and rows > 0
                            else 0
                        )
                        logging.info(
                            f"  {status:6s}  {tbl:50s} "
                            f"~{rows:>12,} rows  {elapsed:>7.1f}s  "
                            f"~{rate:>10,.0f} rows/s"
                        )
                        if not ok:
                            failed.append(tbl)
                        elif rows > 0:
                            total_rows += rows

                    if failed:
                        logging.error(f"FAILED tables: {failed}")
                        sys.exit(1)
                    logging.info(
                        f"All tables loaded successfully "
                        f"(~{total_rows:,} rows total)"
                    )

            # ================================================================
            # Strategy: psql-copy (two-phase: psql COPY → gzip → disk → CH)
            # ================================================================
            elif strategy == 'psql-copy':
                dump_dir = args.dump_dir
                os.makedirs(dump_dir, exist_ok=True)

                # Phase 1: dump each table to gzipped CSV
                if not args.load_only:
                    logging.info(
                        f"=== Step 4a: Dumping {len(work_items)} tables "
                        f"to {dump_dir} (psql-copy) ==="
                    )
                    for schema_name, table_name, ch_database, ch_table in work_items:
                        pg_conn_cols = get_postgres_connection(
                            args.pg_host, pg_user, pg_password,
                            args.pg_port, args.pg_database
                        )
                        columns_meta = get_table_columns(
                            pg_conn_cols, schema_name, table_name,
                            pg_server_timezone=pg_server_timezone,
                            override_config=override_config,
                            pg_database=args.pg_database,
                        )
                        pg_conn_cols.close()
                        col_names = [c['column_name'] for c in columns_meta]

                        psqlcopy_dump_table(
                            table_name=table_name,
                            column_names=col_names,
                            pg_host=args.pg_host,
                            pg_port=args.pg_port,
                            pg_user=pg_user,
                            pg_password=pg_password,
                            pg_database=args.pg_database,
                            pg_schema=schema_name,
                            dump_dir=dump_dir,
                            dry_run=args.dry_run,
                        )

                if args.dump_only:
                    logging.info(
                        "psql-copy Phase 1 complete (--dump_only). "
                        f"Dump directory: {dump_dir}"
                    )
                else:
                    # Phase 2: load each gzipped CSV into ClickHouse
                    logging.info(
                        f"=== Step 4b: Loading {len(work_items)} tables "
                        f"from {dump_dir} (psql-copy) "
                        f"with {args.threads} threads ==="
                    )
                    results = []
                    with concurrent.futures.ThreadPoolExecutor(
                        max_workers=args.threads,
                        thread_name_prefix='psqlcopy_loader',
                    ) as executor:
                        futures = {}
                        for schema_name, table_name, ch_database, ch_table in work_items:
                            csv_file = os.path.join(
                                dump_dir, f"{table_name}.csv.gz"
                            )
                            if not os.path.exists(csv_file):
                                logging.warning(
                                    f"[{ch_table}] Dump file not found: "
                                    f"{csv_file} — skipping"
                                )
                                results.append((ch_table, 0, 0, False))
                                continue

                            # Get column names for the load command
                            pg_conn_cols = get_postgres_connection(
                                args.pg_host, pg_user, pg_password,
                                args.pg_port, args.pg_database
                            )
                            columns_meta = get_table_columns(
                                pg_conn_cols, schema_name, table_name,
                                pg_server_timezone=pg_server_timezone,
                                override_config=override_config,
                                pg_database=args.pg_database,
                            )
                            pg_conn_cols.close()
                            col_names = [
                                c['column_name'] for c in columns_meta
                            ]

                            future = executor.submit(
                                psqlcopy_load_table,
                                table_name=table_name,
                                csv_file=csv_file,
                                ch_host=args.ch_host,
                                ch_port=args.ch_port,
                                ch_user=ch_user,
                                ch_password=ch_password,
                                ch_database=ch_database,
                                ch_table=ch_table,
                                column_names=col_names,
                                ch_secure=args.ch_secure,
                                dry_run=args.dry_run,
                            )
                            futures[future] = ch_table

                        for future in concurrent.futures.as_completed(futures):
                            tbl_label = futures[future]
                            try:
                                result = future.result()
                                results.append(result)
                            except Exception as exc:
                                logging.error(
                                    f"[{tbl_label}] raised an exception: "
                                    f"{exc}"
                                )
                                logging.error(traceback.format_exc())
                                results.append((tbl_label, -1, 0, False))

                    # Summary
                    logging.info("=== Load Summary (psql-copy) ===")
                    failed = []
                    total_rows = 0
                    for (tbl, rows, elapsed, ok) in sorted(
                        results, key=lambda r: r[0]
                    ):
                        status = "OK" if ok else "FAILED"
                        rate = (
                            rows / elapsed
                            if elapsed > 0 and rows > 0
                            else 0
                        )
                        logging.info(
                            f"  {status:6s}  {tbl:50s} "
                            f"~{rows:>12,} rows  {elapsed:>7.1f}s  "
                            f"~{rate:>10,.0f} rows/s"
                        )
                        if not ok:
                            failed.append(tbl)
                        elif rows > 0:
                            total_rows += rows

                    if failed:
                        logging.error(f"FAILED tables: {failed}")
                        sys.exit(1)
                    logging.info(
                        f"All tables loaded successfully "
                        f"(~{total_rows:,} rows total)"
                    )

            # ================================================================
            # Strategy: streaming (default — existing behavior)
            # ================================================================
            elif strategy == 'streaming':
                # Build the full list of load jobs.  Each job is either:
                #   - A whole-table load (small tables)
                #   - A PK-range segment load (large tables)
                # This list is then fed into the thread pool.
                load_jobs = []   # list of dicts with load_table kwargs

                segment_threshold = getattr(
                    args, 'segment_threshold', 1_000_000
                )
                segments_per_table = getattr(args, 'segments_per_table', 4)

                for schema_name, table_name, ch_database, ch_table in work_items:
                    # Check row count to decide if segmentation is needed
                    pg_conn_seg = get_postgres_connection(
                        args.pg_host, pg_user, pg_password,
                        args.pg_port, args.pg_database
                    )
                    approx_rows = get_table_row_count(
                        pg_conn_seg, schema_name, table_name
                    )
                    pk_cols = get_table_pk(
                        pg_conn_seg, schema_name, table_name
                    )

                    if (segment_threshold > 0
                            and approx_rows > segment_threshold
                            and segments_per_table > 1):
                        # Check if PK is suitable for segmentation
                        is_seg, pk_col = get_pk_type_is_segmentable(
                            pg_conn_seg, schema_name, table_name, pk_cols
                        )
                        if is_seg:
                            logging.info(
                                f"[{ch_table}] Large table "
                                f"(~{approx_rows:,} rows), splitting into "
                                f"{segments_per_table} PK segments "
                                f"on column '{pk_col}'"
                            )
                            boundaries = get_pk_range_boundaries(
                                pg_conn_seg, schema_name, table_name,
                                pk_col, segments_per_table
                            )
                            pg_conn_seg.close()
                            for seg_idx, (lower, upper) in enumerate(
                                boundaries, 1
                            ):
                                where = build_segment_where_clause(
                                    pk_col, lower, upper
                                )
                                load_jobs.append({
                                    'table_name': table_name,
                                    'pg_host': args.pg_host,
                                    'pg_port': args.pg_port,
                                    'pg_user': pg_user,
                                    'pg_password': pg_password,
                                    'pg_database': args.pg_database,
                                    'pg_schema': schema_name,
                                    'ch_host': args.ch_host,
                                    'ch_port': args.ch_port,
                                    'ch_user': ch_user,
                                    'ch_password': ch_password,
                                    'ch_database': ch_database,
                                    'ch_config_file': args.ch_config_file,
                                    'ch_secure': args.ch_secure,
                                    'dry_run': args.dry_run,
                                    'batch_size': args.batch_size,
                                    'pg_server_timezone': pg_server_timezone,
                                    'ch_table_name': ch_table,
                                    'where_clause': where,
                                    'segment_label': (
                                        f"seg {seg_idx}/{len(boundaries)}"
                                    ),
                                })
                            continue  # skip the whole-table fallback below
                        else:
                            logging.info(
                                f"[{ch_table}] Large table "
                                f"(~{approx_rows:,} rows) but PK not "
                                f"segmentable (compound or unsupported "
                                f"type), loading as single stream"
                            )

                    pg_conn_seg.close()

                    # Whole-table load (no segmentation)
                    load_jobs.append({
                        'table_name': table_name,
                        'pg_host': args.pg_host,
                        'pg_port': args.pg_port,
                        'pg_user': pg_user,
                        'pg_password': pg_password,
                        'pg_database': args.pg_database,
                        'pg_schema': schema_name,
                        'ch_host': args.ch_host,
                        'ch_port': args.ch_port,
                        'ch_user': ch_user,
                        'ch_password': ch_password,
                        'ch_database': ch_database,
                        'ch_config_file': args.ch_config_file,
                        'ch_secure': args.ch_secure,
                        'dry_run': args.dry_run,
                        'batch_size': args.batch_size,
                        'pg_server_timezone': pg_server_timezone,
                        'ch_table_name': ch_table,
                    })

                logging.info(
                    f"=== Step 4: Loading {len(work_items)} tables "
                    f"({len(load_jobs)} jobs including segments) "
                    f"with {args.threads} threads ==="
                )
                results = []
                with concurrent.futures.ThreadPoolExecutor(
                    max_workers=args.threads, thread_name_prefix='pg_loader'
                ) as executor:
                    futures = {
                        executor.submit(load_table, **job): job
                        for job in load_jobs
                    }

                    for future in concurrent.futures.as_completed(futures):
                        try:
                            result = future.result()
                            results.append(result)
                        except Exception as exc:
                            job = futures[future]
                            tbl_label = job.get(
                                'ch_table_name', job['table_name']
                            )
                            seg = job.get('segment_label', '')
                            if seg:
                                tbl_label = f"{tbl_label} [{seg}]"
                            logging.error(
                                f"[{tbl_label}] raised an exception: {exc}"
                            )
                            logging.error(traceback.format_exc())
                            results.append((tbl_label, -1, 0, False))

                # Summary report
                logging.info("=== Load Summary ===")
                failed = []
                total_rows = 0
                for (tbl, rows, elapsed, ok) in sorted(
                    results, key=lambda r: r[0]
                ):
                    status = "OK" if ok else "FAILED"
                    rate = (
                        rows / elapsed if elapsed > 0 and rows > 0 else 0
                    )
                    if rows >= 0:
                        logging.info(
                            f"  {status:6s}  {tbl:50s} "
                            f"~{rows:>12,} rows  {elapsed:>7.1f}s  "
                            f"~{rate:>10,.0f} rows/s"
                        )
                    else:
                        logging.info(
                            f"  {status:6s}  {tbl:50s} "
                            f"   (segment)  {elapsed:>7.1f}s"
                        )
                    if not ok:
                        failed.append(tbl)
                    elif rows > 0:
                        total_rows += rows

                if failed:
                    logging.error(f"FAILED tables: {failed}")
                    sys.exit(1)

                logging.info(
                    f"All tables loaded successfully "
                    f"(~{total_rows:,} rows total)"
                )

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
                f"LSN offset written: {lsn_str} (low32={lsn_int}) \u2192 {args.offset_table}. "
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
