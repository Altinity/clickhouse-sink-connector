# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : postgres_table_count
# -- Date         :
# -- Summary      : compute exact COUNT(*) for a PostgreSQL table
# --                Mirrors mysql_table_count.py for the PostgreSQL pipeline
# -- Credits      : https://www.percona.com/blog/mysql-8-0-14-a-road-to-parallel-query-execution-is-wide-open/
# --
"""
import logging
import argparse
import traceback
import sys
import datetime
import re
import os
import concurrent.futures
from db.postgres import *

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def get_postgres_table_count(conn, table_name, schema="public"):
    """
    Return the exact row count for a PostgreSQL table.

    Parameters
    ----------
    conn       : psycopg2 connection (obtained via get_postgres_connection())
    table_name : bare table name (no schema prefix)
    schema     : PostgreSQL schema, default 'public'

    Returns
    -------
    int : exact row count, or -1 on error
    """
    try:
        sql = f'SELECT COUNT(*) AS cnt FROM "{schema}"."{table_name}"'
        rows = execute_pg(conn, sql)
        if rows:
            return int(rows[0]['cnt'])
        return 0
    except Exception as e:
        logging.error(f"Error counting {schema}.{table_name}: {e}")
        return -1


def calculate_table_count(pg_host, pg_user, pg_password, pg_port, pg_database,
                          pg_schema, table_name, where=None):
    """
    Open a fresh connection (thread-safe) and compute COUNT(*) for one table.
    psycopg2 connections are NOT thread-safe, so each thread gets its own.
    """
    if args.exclude_tables_regex:
        rex = re.compile(args.exclude_tables_regex, re.IGNORECASE)
        if rex.match(table_name):
            logging.info(f"Excluding {table_name} due to exclude_tables_regex")
            return

    conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)
    try:
        where_clause = ""
        if where:
            where_clause = f" WHERE {where}"

        sql = f'SELECT COUNT(*) AS cnt FROM "{pg_schema}"."{table_name}"{where_clause}'
        rows = execute_pg(conn, sql)
        count = int(rows[0]['cnt']) if rows else 0
        logging.info(f"Count for table {pg_database}.{pg_schema}.{table_name} = {count}")
        return count
    except Exception as e:
        logging.error(f"Error in calculate_table_count for {table_name}: {e}")
        logging.error(traceback.format_exc())
        return -1
    finally:
        conn.close()


# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


def main():
    parser = argparse.ArgumentParser(description='''
Compute exact row counts for PostgreSQL tables.
Mirrors mysql_table_count.py for the PostgreSQL→ClickHouse CDC pipeline.
    ''')

    # PostgreSQL connection
    parser.add_argument('--pg_host', help='PostgreSQL host', required=True)
    parser.add_argument('--pg_user', help='PostgreSQL user', required=False)
    parser.add_argument('--pg_password',
                        help='PostgreSQL password (discouraged; use ~/.pgpass)', required=False)
    parser.add_argument('--pgpass_file',
                        help='Path to .pgpass file (default: ~/.pgpass)',
                        required=False, default='~/.pgpass')
    parser.add_argument('--pg_database', help='PostgreSQL database', required=True)
    parser.add_argument('--pg_port', help='PostgreSQL port', default=5432, required=False)
    parser.add_argument('--pg_schema', help='PostgreSQL schema', default='public', required=False)

    # Table selection
    parser.add_argument('--include_tables_regex',
                        help='Table name regex (matches against bare table name)',
                        required=False, default='.')
    parser.add_argument('--exclude_tables_regex',
                        help='Exclude table name regex', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Use --include_tables_regex as literal table name', required=False)

    # Query options
    parser.add_argument('--where', help='Additional WHERE clause', required=False)

    # Parallelism
    parser.add_argument('--threads', type=int,
                        help='Number of parallel table threads', default=1)

    # Debug
    parser.add_argument('--debug', dest='debug', action='store_true', default=False)

    global args
    args = parser.parse_args()

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(threadName)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if args.debug:
        root.setLevel(logging.DEBUG)
        handler.setLevel(logging.DEBUG)

    pg_user = args.pg_user
    pg_password = args.pg_password

    if args.pg_password:
        logging.warning("Using password on the command line is not secure; use ~/.pgpass")
        assert args.pg_user is not None, "--pg_user must be specified when using --pg_password"
    else:
        pgpass_file = os.path.expanduser(args.pgpass_file)
        (pg_user, pg_password) = resolve_credentials_from_pgpass(pgpass_file)
        if pg_user is None:
            logging.error(f"Could not resolve credentials from {pgpass_file}")
            sys.exit(1)

    try:
        conn = get_postgres_connection(
            args.pg_host, pg_user, pg_password, args.pg_port, args.pg_database)

        # Discover tables
        if args.no_wc:
            tables = [args.include_tables_regex]
        else:
            tables = get_tables(
                conn,
                pg_schema=args.pg_schema,
                include_regex=args.include_tables_regex,
                exclude_regex=args.exclude_tables_regex,
            )
        conn.close()

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table_name in tables:
                futures.append(executor.submit(
                    calculate_table_count,
                    args.pg_host, pg_user, pg_password, args.pg_port,
                    args.pg_database, args.pg_schema, table_name, args.where
                ))
            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    raise future.exception()

    except (KeyboardInterrupt, SystemExit):
        logging.info("Received interrupt")
        os._exit(1)
    except Exception as e:
        logging.error(f"Exception in main thread: {e}")
        logging.error(traceback.format_exc())
        sys.exit(1)

    logging.debug("Exiting Main Thread")
    sys.exit(0)


if __name__ == '__main__':
    main()
