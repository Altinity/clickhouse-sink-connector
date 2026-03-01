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
# --       --pg_host fpif1-postgresl1.host \
# --       --pg_port 5435 \
# --       --pg_database awacs-qa \
# --       --pg_user replicator \
# --       --pg_password secret \
# --       --pg_schema public \
# --       --ch_host fpif-dbachl4.host \
# --       --ch_port 9000 \
# --       --ch_database awacs_qa \
# --       --ch_user default \
# --       --ch_password secret \
# --       --threads 8 \
# --       --tables '.' \
# --       --exclude_tables 'django_migrations|django_session' \
# --       --offset_table altinity_sink_connector.replica_source_info_awacs_qa_dev
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

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.dirname(SCRIPT_DIR))

from db.postgres import (
    get_postgres_connection,
    get_tables,
    get_table_columns,
    get_table_pk,
    get_table_row_count,
    get_current_lsn,
    get_server_timezone,
    build_ch_create_table_ddl,
)
from db.clickhouse import clickhouse_connection, clickhouse_execute_conn
from db_load.postgres_type_mapper import (
    build_create_table,
    build_insert_structure,
    build_select_columns,
    build_offset_insert,
)

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")

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
    # -- run the command that is passed as cmd and return True or False
    # -- ======================================================================
    """
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True)
    for line in process.stdout:
        logging.info(line.decode().strip())
        time.sleep(0.02)
    rc = str(process.poll())
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
    NULL values are represented as \\N (standard CSV NULL sentinel).
    """
    col_list = ", ".join(f'"{c}"' for c in column_names)
    copy_sql = (
        f"COPY (SELECT {col_list} FROM \"{pg_schema}\".\"{table_name}\") "
        f"TO STDOUT WITH (FORMAT CSV, HEADER false, NULL '\\\\N')"
    )
    # PGPASSWORD is set in the environment by the calling shell command
    cmd = (
        f"PGPASSWORD='{pg_password}' psql"
        f" -h {pg_host}"
        f" -p {pg_port}"
        f" -U {pg_user}"
        f" -d \"{pg_database}\""
        f" -c \"{copy_sql}\""
    )
    return cmd


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

        psql_cmd = build_psql_copy_cmd(
            pg_host, pg_port, pg_user, pg_password,
            pg_database, pg_schema, table_name, column_names, batch_size,
        )
        ch_cmd = build_ch_insert_cmd(
            ch_host, ch_port, ch_user, ch_password,
            ch_database, table_name, column_names, columns_meta,
            ch_config_file=ch_config_file, ch_secure=ch_secure,
        )

        pipe_cmd = f"{psql_cmd} | {ch_cmd}"
        logging.info(f"[{table_name}] Starting load (~{approx_rows:,} rows)")
        logging.debug(f"[{table_name}] pipe cmd: {pipe_cmd}")

        if not dry_run:
            rc = run_command(pipe_cmd)
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

def write_lsn_offset(ch_conn, offset_table, lsn_int, connector_name, dry_run=False):
    """
    Insert the snapshot starting LSN into the ClickHouse offset table so that
    the Java CDC connector can start from the correct WAL position.

    connector_name must match the "name" property in the Java connector's config.yml,
    e.g. "sink-connector-awacs-qa-sink-dev".  The Java DebeziumOffsetStorage.getOffsetKey()
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
                            'altinity_sink_connector.replica_source_info_awacs_qa_dev. '
                            'When set, the pre-snapshot WAL LSN is written here '
                            'after all tables are loaded.'
                        ))
    parser.add_argument('--connector_name', required=False,
                        default='sink-connector',
                        help=(
                            'The "name" property from the Java connector config.yml. '
                            'Used to build the correct offset_key for the CDC hand-off. '
                            'E.g. "sink-connector-awacs-qa-sink-dev". '
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
        from db.postgres import resolve_credentials_from_pgpass
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
        from db.clickhouse import resolve_credentials_from_config
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
        (lsn_str, lsn_int) = get_current_lsn(pg_conn_main)
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
        pg_conn_main.close()

        if not tables:
            logging.error(
                f"No tables found in schema '{args.pg_schema}' "
                f"matching '{args.tables}'"
            )
            sys.exit(1)

        logging.info(f"Tables to process ({len(tables)}): {tables}")

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
