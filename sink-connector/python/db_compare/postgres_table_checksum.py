# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : postgres_table_checksum
# -- Date         :
# -- Summary      : compute a deterministic checksum for a PostgreSQL table
# --                Mirrors mysql_table_checksum.py for the PostgreSQL pipeline.
# --
# --                Uses the same 4-bucket MD5 accumulation technique:
# --                  split 32-hex MD5 into four 8-hex chunks,
# --                  interpret each as signed int64, sum across all rows,
# --                  then hashlib.md5("cnt#a#b#c#d#") → table checksum.
# --
# -- Credits      : https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/
# --
"""
import logging
import argparse
import traceback
import sys
import datetime
import re
import os
import hashlib
import concurrent.futures
from db.postgres import *

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


# ---------------------------------------------------------------------------
# Column expression builder
# ---------------------------------------------------------------------------

def build_pg_select_expression(col_name, pg_type, is_nullable, udt_name,
                                include_floating_point=False,
                                include_json=False,
                                excluded_columns=None):
    """
    Return (sql_fragment, skip) where:
      sql_fragment : a SQL expression that produces a stable text value
                     comparable with the ClickHouse side
      skip         : True if this column should be excluded from the checksum

    Normalization rules to match ClickHouse toString() output:
      boolean        → CASE WHEN col THEN '1' ELSE '0' END
      timestamp*     → to_char(col, 'YYYY-MM-DD HH24:MI:SS.US')
      date           → to_char(col, 'YYYY-MM-DD')
      time*          → to_char(col, 'HH24:MI:SS.US')
      bytea          → encode(col, 'hex')
      numeric/decimal→ col::text  (full precision text)
      uuid           → lower(col::text)
      json/jsonb     → col::text  (excluded by default – non-deterministic)
      arrays (_*)    → array_to_string(col, ',')
      float/double   → excluded by default
      everything else→ col::text
    """
    if excluded_columns and col_name in excluded_columns:
        return (None, True)

    q = f'"{col_name}"'
    pg_type_lower = pg_type.lower().strip()
    udt_lower = (udt_name or '').lower().strip()

    # Skip floating-point columns by default
    if not include_floating_point:
        if pg_type_lower in ('real', 'float4', 'double precision', 'float8', 'float'):
            logging.info(f"Excluding floating-point column {col_name} of type {pg_type}")
            return (None, True)

    # Skip json/jsonb by default (non-deterministic ordering)
    if not include_json:
        if pg_type_lower in ('json', 'jsonb'):
            logging.info(f"Excluding json column {col_name} of type {pg_type}")
            return (None, True)

    # Boolean → '0'/'1' to match CH UInt8 toString()
    if pg_type_lower in ('boolean', 'bool'):
        expr = f"CASE WHEN {q} THEN '1' ELSE '0' END"

    # Timestamps → microsecond-precision UTC string matching CH toString(toTimeZone(col, 'UTC'))
    # timestamptz (with time zone): normalise to UTC first so both sides produce the same string
    # regardless of PG session timezone or CH server timezone.
    elif 'timestamp' in pg_type_lower and 'time zone' in pg_type_lower:
        expr = f"to_char({q} AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"
    # timestamp without time zone: no timezone conversion needed
    elif 'timestamp' in pg_type_lower:
        expr = f"to_char({q}, 'YYYY-MM-DD HH24:MI:SS.US')"

    # Date → YYYY-MM-DD matching CH Date32 toString()
    elif pg_type_lower == 'date':
        expr = f"to_char({q}, 'YYYY-MM-DD')"

    # Time types → HH24:MI:SS.US
    elif 'time' in pg_type_lower:
        expr = f"to_char({q}, 'HH24:MI:SS.US')"

    # Bytea → hex string matching CH String from Debezium hex-encoding
    elif pg_type_lower == 'bytea':
        logging.info(f"Excluding bytea column {col_name} (Debezium encodes as Base64, dump uses hex)")
        return (None, True)

    # Range types: skip — Debezium CDC encodes tstzrange as UTC string,
    # dump uses session-TZ string. Cannot be checksummed reliably across dump+CDC.
    elif pg_type_lower in ('tstzrange', 'tsrange', 'daterange', 'int4range', 'int8range', 'numrange') \
            or udt_lower in ('tstzrange', 'tsrange', 'daterange', 'int4range', 'int8range', 'numrange'):
        logging.info(f"Excluding range-type column {col_name} of type {pg_type} (Debezium uses UTC, dump uses session-TZ)")
        return (None, True)

    # UUID → lowercase string matching CH String toString()
    elif pg_type_lower == 'uuid':
        expr = f"lower({q}::text)"

    # Arrays → PG native array literal {a,b,c} matches CH toString() output for Array stored as String
    elif udt_lower.startswith('_') or pg_type_lower.endswith('[]'):
        expr = f"{q}::text"

    # JSON/JSONB (when include_json=True)
    elif pg_type_lower in ('json', 'jsonb'):
        expr = f"{q}::text"

    # Numeric / decimal → cast to text (preserves exact representation)
    elif pg_type_lower in ('numeric', 'decimal'):
        expr = f"{q}::text"

    # Everything else → simple cast to text
    else:
        expr = f"{q}::text"

    # NULL handling: wrap with coalesce to empty string
    if is_nullable:
        expr = f"coalesce({expr}, '')"

    return (expr, False)


# ---------------------------------------------------------------------------
# Query builders
# ---------------------------------------------------------------------------

def build_tier1_chunk_query(table_name, pg_schema, columns_meta,
                             pk_col, min_pk, max_pk,
                             where=None,
                             include_floating_point=False,
                             include_json=False,
                             excluded_columns=None,
                             debug_output=False,
                             debug_limit=None):
    """
    Build the Tier-1 full-column MD5 chunk query for PostgreSQL.

    Returns the SQL string that produces (cnt, a, b, c, d) — the 4-bucket
    accumulation needed to compute the final table checksum.

    If debug_output is True, returns the raw hash rows instead.
    """
    col_exprs = []
    nullable_cols = []

    for col in columns_meta:
        col_name = col['column_name']
        pg_type = col['pg_type']
        is_nullable = col['nullable']
        udt_name = col.get('udt_name', '')

        (expr, skip) = build_pg_select_expression(
            col_name, pg_type, is_nullable, udt_name,
            include_floating_point=include_floating_point,
            include_json=include_json,
            excluded_columns=excluded_columns,
        )
        if skip:
            continue

        col_exprs.append(expr)
        if is_nullable:
            nullable_cols.append(f'"{col_name}"')

    if not col_exprs:
        logging.warning(f"No columns to checksum for {pg_schema}.{table_name}")
        return None

    # Build the concat_ws expression with chr(1) separator (avoids collisions with data containing '#')
    # Append NULL indicator bitmap at end (same as MySQL version)
    null_indicator = ""
    if nullable_cols:
        null_bits = " || ".join(
            f"(CASE WHEN {c} IS NULL THEN '1' ELSE '0' END)"
            for c in nullable_cols
        )
        null_indicator = f" || chr(1) || {null_bits}"

    concat_expr = "concat_ws(chr(1), " + ", ".join(col_exprs) + ")" + null_indicator

    # WHERE clause assembly
    where_parts = ["1=1"]
    if where:
        where_parts.append(where)
    if pk_col and min_pk is not None and max_pk is not None:
        where_parts.append(f'"{pk_col}" BETWEEN {min_pk} AND {max_pk}')
    where_sql = " AND ".join(where_parts)

    limit_clause = ""
    if debug_output and debug_limit:
        limit_clause = f" LIMIT {debug_limit}"

    if debug_output:
        # Return raw hash rows for debugging
        sql = f"""
SELECT md5({concat_expr}) AS row_hash
FROM "{pg_schema}"."{table_name}"
WHERE {where_sql}{limit_clause}
""".strip()
    else:
        sql = f"""
SELECT
    count(*) AS cnt,
    sum(('x' || substring(row_hash,  1, 8))::bit(32)::int8) AS a,
    sum(('x' || substring(row_hash,  9, 8))::bit(32)::int8) AS b,
    sum(('x' || substring(row_hash, 17, 8))::bit(32)::int8) AS c,
    sum(('x' || substring(row_hash, 25, 8))::bit(32)::int8) AS d
FROM (
    SELECT md5({concat_expr}) AS row_hash
    FROM "{pg_schema}"."{table_name}"
    WHERE {where_sql}{limit_clause}
) t
""".strip()

    return sql


def build_tier2_chunk_query(table_name, pg_schema, pk_col,
                             min_pk, max_pk, where=None,
                             debug_output=False, debug_limit=None):
    """
    Build the Tier-2 PK-list MD5 query.
    Detects missing/extra rows by checksumming sorted PK values only.
    """
    where_parts = ["1=1"]
    if where:
        where_parts.append(where)
    if pk_col and min_pk is not None and max_pk is not None:
        where_parts.append(f'"{pk_col}" BETWEEN {min_pk} AND {max_pk}')
    where_sql = " AND ".join(where_parts)

    limit_clause = ""
    if debug_output and debug_limit:
        limit_clause = f" LIMIT {debug_limit}"

    if debug_output:
        sql = f"""
SELECT md5("{pk_col}"::text) AS pk_hash
FROM "{pg_schema}"."{table_name}"
WHERE {where_sql}
ORDER BY "{pk_col}"{limit_clause}
""".strip()
    else:
        sql = f"""
SELECT
    count(*) AS cnt,
    sum(('x' || substring(pk_hash,  1, 8))::bit(32)::int8) AS a,
    sum(('x' || substring(pk_hash,  9, 8))::bit(32)::int8) AS b,
    sum(('x' || substring(pk_hash, 17, 8))::bit(32)::int8) AS c,
    sum(('x' || substring(pk_hash, 25, 8))::bit(32)::int8) AS d
FROM (
    SELECT md5("{pk_col}"::text) AS pk_hash
    FROM "{pg_schema}"."{table_name}"
    WHERE {where_sql}
    ORDER BY "{pk_col}"{limit_clause}
) t
""".strip()

    return sql


# ---------------------------------------------------------------------------
# Chunk divider (mirrors mysql divide_table_into_even_chunks)
# ---------------------------------------------------------------------------

def divide_table_into_chunks(conn, table_name, pg_schema, pk_col, chunk_size, where=None):
    """
    Return a list of (min_pk, max_pk) chunk tuples based on the PK range.
    If no PK or chunk_size=0, returns a single chunk with (None, None).
    """
    if not pk_col:
        return [{'min_pk': None, 'max_pk': None}]

    where_clause = ""
    if where:
        where_clause = f" WHERE {where}"

    sql = f'SELECT min("{pk_col}") AS min_pk, max("{pk_col}") AS max_pk FROM "{pg_schema}"."{table_name}"{where_clause}'
    rows = execute_pg(conn, sql)
    if not rows or rows[0]['min_pk'] is None:
        return [{'min_pk': None, 'max_pk': None}]

    min_pk = int(rows[0]['min_pk'])
    max_pk = int(rows[0]['max_pk'])

    chunks = []
    current = min_pk
    while current <= max_pk:
        chunk_max = min(current + chunk_size - 1, max_pk)
        chunks.append({'min_pk': current, 'max_pk': chunk_max})
        current = chunk_max + 1

    return chunks


# ---------------------------------------------------------------------------
# Per-chunk checksum execution
# ---------------------------------------------------------------------------

def compute_chunk_checksum(pg_host, pg_user, pg_password, pg_port, pg_database,
                            table_name, pg_schema, sql, debug_output, debug_limit,
                            debug_out_file=None):
    """
    Execute a single chunk checksum query in its own connection.
    Returns (cnt, a, b, c, d) tuple or writes debug rows to file.
    """
    conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)
    try:
        rows = execute_pg(conn, sql)
        if debug_output:
            if debug_out_file and rows:
                with open(debug_out_file, 'a') as f:
                    for row in rows:
                        f.write(str(dict(row)) + '\n')
            return None

        if not rows or rows[0]['cnt'] is None:
            return (0, 0, 0, 0, 0)

        row = rows[0]
        return (
            int(row['cnt'] or 0),
            int(row['a'] or 0),
            int(row['b'] or 0),
            int(row['c'] or 0),
            int(row['d'] or 0),
        )
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Main checksum computation
# ---------------------------------------------------------------------------

def get_postgres_table_checksum(conn, table_name, columns_meta, pk_columns,
                                 schema="public", chunk_size=100000,
                                 where=None, tier=1,
                                 include_floating_point=False,
                                 include_json=False,
                                 excluded_columns=None,
                                 debug_output=False,
                                 debug_limit=None,
                                 pg_host=None, pg_user=None,
                                 pg_password=None, pg_port=5432,
                                 pg_database=None,
                                 threads_per_table=1):
    """
    Compute a deterministic checksum for a PostgreSQL table.

    Parameters
    ----------
    conn            : psycopg2 connection (used only for schema/chunk queries)
    table_name      : bare table name
    columns_meta    : list of dicts from get_table_columns()
    pk_columns      : list of PK column names from get_table_pk()
    schema          : PostgreSQL schema (default 'public')
    chunk_size      : PK range per chunk (for Tier-1/2)
    where           : optional extra WHERE clause
    tier            : 1 = full column MD5, 2 = PK-list MD5
    excluded_columns: set/list of column names to exclude
    debug_output    : write raw hash rows instead of checksum
    debug_limit     : limit rows in debug mode

    Returns
    -------
    str : hex MD5 digest of the accumulated (cnt, a, b, c, d) tuple
          None if debug_output=True
    """
    excluded_cols = set(excluded_columns or [])

    # Use first integer PK column for chunking
    pk_col = None
    if pk_columns:
        for pkc in pk_columns:
            # Check if the PK column is an integer type (suitable for range chunking)
            for col in columns_meta:
                if col['column_name'] == pkc:
                    pg_type = col['pg_type'].lower()
                    if any(t in pg_type for t in ('int', 'serial', 'bigint', 'smallint')):
                        pk_col = pkc
                    break
            if pk_col:
                break

    # For tables with composite or non-integer PK: single chunk, no range filter
    if not pk_col and pk_columns:
        pk_col = None  # no chunking possible; single full scan

    # Divide into chunks
    chunks = divide_table_into_chunks(conn, table_name, schema, pk_col, chunk_size, where)

    # Initialize debug file
    debug_out_file = None
    if debug_output:
        debug_out_file = f"out.{table_name}.pg.txt"
        open(debug_out_file, 'w').close()  # truncate/create

    futures_list = []
    chunk_results = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=threads_per_table) as executor:
        for chunk in chunks:
            min_pk = chunk['min_pk']
            max_pk = chunk['max_pk']

            if tier == 1:
                sql = build_tier1_chunk_query(
                    table_name, schema, columns_meta,
                    pk_col, min_pk, max_pk,
                    where=where,
                    include_floating_point=include_floating_point,
                    include_json=include_json,
                    excluded_columns=excluded_cols,
                    debug_output=debug_output,
                    debug_limit=debug_limit,
                )
            else:  # tier == 2
                if not pk_col and not pk_columns:
                    logging.warning(f"No PK for Tier-2 checksum of {schema}.{table_name}; skipping")
                    return None
                actual_pk = pk_col or pk_columns[0]
                sql = build_tier2_chunk_query(
                    table_name, schema, actual_pk,
                    min_pk, max_pk,
                    where=where,
                    debug_output=debug_output,
                    debug_limit=debug_limit,
                )

            if sql is None:
                continue

            logging.debug(f"Chunk SQL for {schema}.{table_name}: {sql[:200]}...")

            fut = executor.submit(
                compute_chunk_checksum,
                pg_host, pg_user, pg_password, pg_port, pg_database,
                table_name, schema, sql, debug_output, debug_limit, debug_out_file,
            )
            futures_list.append(fut)

        for fut in concurrent.futures.as_completed(futures_list):
            if fut.exception() is not None:
                raise fut.exception()
            result = fut.result()
            if result is not None:
                chunk_results.append(result)

    if debug_output:
        return None

    # Accumulate across chunks (same pattern as MySQL version)
    to_add = (0, 0, 0, 0, 0)
    for r in chunk_results:
        to_add = (
            to_add[0] + r[0],
            to_add[1] + r[1],
            to_add[2] + r[2],
            to_add[3] + r[3],
            to_add[4] + r[4],
        )

    cnt = to_add[0]
    md5_input = '#'.join(str(x) for x in to_add) + '#'
    m = hashlib.md5()
    m.update(md5_input.encode('utf-8'))
    checksum = m.hexdigest()

    logging.info(
        f"Checksum for table {pg_database or schema}.{schema}.{table_name}"
        f" = {checksum} count {cnt}"
    )
    return checksum


# ---------------------------------------------------------------------------
# High-level calculate_checksum (mirrors mysql_table_checksum.py structure)
# ---------------------------------------------------------------------------

def calculate_checksum(table_name, pg_host, pg_user, pg_password, pg_port,
                        pg_database, pg_schema, excluded_columns,
                        include_floating_point, include_json,
                        chunk_size, threads_per_table, where, tier=1,
                        debug_output=False, debug_limit=None):
    """
    Entry point for one table — opens its own connection, discovers columns
    and PK, then calls get_postgres_table_checksum().
    Thread-safe: each invocation uses its own psycopg2 connection.
    """
    if args.ignore_tables_regex:
        rex = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex.match(table_name):
            logging.info(f"Ignoring {table_name} due to ignore_tables_regex")
            return

    conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)
    try:
        columns_meta = get_table_columns(conn, pg_schema, table_name)
        pk_columns = get_table_pk(conn, pg_schema, table_name)

        parsed_excluded = []
        for col in excluded_columns:
            parsed_excluded.extend(col.split(','))

        get_postgres_table_checksum(
            conn=conn,
            table_name=table_name,
            columns_meta=columns_meta,
            pk_columns=pk_columns,
            schema=pg_schema,
            chunk_size=chunk_size,
            where=where,
            tier=tier,
            include_floating_point=include_floating_point,
            include_json=include_json,
            excluded_columns=parsed_excluded,
            debug_output=debug_output,
            debug_limit=debug_limit,
            pg_host=pg_host,
            pg_user=pg_user,
            pg_password=pg_password,
            pg_port=pg_port,
            pg_database=pg_database,
            threads_per_table=threads_per_table,
        )
    except Exception as e:
        logging.error(f"Error checksumming {pg_schema}.{table_name}: {e}")
        logging.error(traceback.format_exc())
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Logger factory
# ---------------------------------------------------------------------------

old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='''
Compute a ClickHouse-compatible checksum for a PostgreSQL table.
Uses the same 4-bucket MD5 accumulation as mysql_table_checksum.py.
    ''')

    # PostgreSQL connection
    parser.add_argument('--pg_host', help='PostgreSQL host', required=True)
    parser.add_argument('--pg_user', help='PostgreSQL user', required=False)
    parser.add_argument('--pg_password',
                        help='PostgreSQL password (discouraged; use ~/.pgpass)',
                        required=False)
    parser.add_argument('--pgpass_file',
                        help='Path to .pgpass file (default: ~/.pgpass)',
                        required=False, default='~/.pgpass')
    parser.add_argument('--pg_database', help='PostgreSQL database', required=True)
    parser.add_argument('--pg_port', help='PostgreSQL port', default=5432, required=False)
    parser.add_argument('--pg_schema', help='PostgreSQL schema', default='public', required=False)

    # Table selection
    parser.add_argument('--tables_regex', help='Table name regex', required=True)
    parser.add_argument('--ignore_tables_regex',
                        help='Ignore table regexp', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Use --tables_regex as literal table name', required=False)

    # Query options
    parser.add_argument('--where', help='Additional WHERE clause', required=False)
    parser.add_argument('--exclude_columns', help='Columns to exclude from checksum',
                        nargs='+', default=[])

    # Checksum tier
    parser.add_argument('--tier', type=int, default=1, choices=[1, 2],
                        help='Checksum tier: 1=full column MD5, 2=PK-list MD5')

    # Parallelism / chunking
    parser.add_argument('--threads_per_table', type=int,
                        help='Parallel chunk threads per table', default=1)
    parser.add_argument('--chunk_size', type=int,
                        help='PK range chunk size', default=100000)
    parser.add_argument('--threads', type=int,
                        help='Parallel tables', default=1)

    # Column type inclusion
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='Include float/double columns (excluded by default)')
    parser.add_argument('--include_json_columns', action='store_true', default=False,
                        help='Include json/jsonb columns (excluded by default)')

    # Debug
    parser.add_argument('--debug_output', action='store_true', default=False,
                        help='Write raw hash rows to out.<table>.pg.txt')
    parser.add_argument('--debug_limit',
                        help='Limit rows in debug output', required=False)
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
        logging.warning("Using password on command line is insecure; use ~/.pgpass")
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

        if args.no_wc:
            tables = [args.tables_regex]
        else:
            tables = get_tables(
                conn,
                pg_schema=args.pg_schema,
                include_regex=args.tables_regex,
                exclude_regex=args.ignore_tables_regex,
            )
        conn.close()

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            future_to_table = {}
            for table_name in tables:
                future = executor.submit(
                    calculate_checksum,
                    table_name,
                    args.pg_host, pg_user, pg_password, args.pg_port,
                    args.pg_database, args.pg_schema,
                    args.exclude_columns,
                    args.include_floating_point_columns,
                    args.include_json_columns,
                    args.chunk_size,
                    args.threads_per_table,
                    args.where,
                    args.tier,
                    args.debug_output,
                    args.debug_limit,
                )
                futures.append(future)
                future_to_table[future] = table_name

            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    logging.error(f"Exception in table {future_to_table[future]}")
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
