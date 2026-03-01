# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : postgres.py
# -- Date         :
# -- Summary      : PostgreSQL connection helpers and type mapping utilities
# --                Mirrors db/mysql.py for the PostgreSQL snapshot pipeline
# --
"""
import logging
import warnings
import os
import configparser
import psycopg2
import psycopg2.extras
import pandas as pd

# ---------------------------------------------------------------------------
# PostgreSQL binary / special-case types that need explicit handling
# ---------------------------------------------------------------------------
binary_datatypes = ('bytea',)

# ---------------------------------------------------------------------------
# Complete PostgreSQL → ClickHouse type mapping
# ---------------------------------------------------------------------------
# Keys are lower-cased PostgreSQL type names (base name only, no length/precision).
# The mapper function pg_type_to_ch() handles the full oid-level names too.
PG_TO_CH_BASE = {
    # --- booleans ---
    'boolean': 'UInt8',
    'bool':    'UInt8',

    # --- integers ---
    'smallint':          'Int16',
    'int2':              'Int16',
    'integer':           'Int32',
    'int':               'Int32',
    'int4':              'Int32',
    'bigint':            'Int64',
    'int8':              'Int64',
    'smallserial':       'Int16',
    'serial':            'Int32',
    'bigserial':         'Int64',
    'serial2':           'Int16',
    'serial4':           'Int32',
    'serial8':           'Int64',

    # --- floating point ---
    'real':              'Float32',
    'float4':            'Float32',
    'double precision':  'Float64',
    'float8':            'Float64',
    'float':             'Float64',

    # --- text / char ---
    'text':              'String',
    'varchar':           'String',
    'character varying': 'String',
    'character':         'String',
    'char':              'String',
    'name':              'String',
    'citext':            'String',
    'bpchar':            'String',

    # --- binary ---
    'bytea':             'String',

    # --- date / time ---
    'date':                          'Date32',
    'time':                          'String',
    'time without time zone':        'String',
    'time with time zone':           'String',
    'timetz':                        'String',
    'timestamp':                     "DateTime64(6)",
    'timestamp without time zone':   "DateTime64(6)",
    'timestamp with time zone':      "DateTime64(6, 'UTC')",
    'timestamptz':                   "DateTime64(6, 'UTC')",
    'interval':                      'String',    # ← the bug that broke awacs-qa

    # --- JSON ---
    'json':              'String',
    'jsonb':             'String',

    # --- UUID ---
    'uuid':              'String',

    # --- network ---
    'inet':              'String',
    'cidr':              'String',
    'macaddr':           'String',
    'macaddr8':          'String',

    # --- geometric ---
    'point':             'String',
    'line':              'String',
    'lseg':              'String',
    'box':               'String',
    'path':              'String',
    'polygon':           'String',
    'circle':            'String',

    # --- full-text search ---
    'tsvector':          'String',
    'tsquery':           'String',

    # --- bit strings ---
    'bit':               'String',
    'bit varying':       'String',
    'varbit':            'String',

    # --- money ---
    'money':             'String',

    # --- xml ---
    'xml':               'String',

    # --- ranges (map to String; CDC sends them as text anyway) ---
    'int4range':         'String',
    'int8range':         'String',
    'numrange':          'String',
    'tsrange':           'String',
    'tstzrange':         'String',
    'daterange':         'String',

    # --- arrays (generic; specific handling in pg_type_to_ch) ---
    'array':             'String',

    # --- catch-all ---
    'oid':               'String',
    'xid':               'String',
    'cid':               'String',
    'tid':               'String',
    'pg_lsn':            'String',
    'void':              'String',
}


def pg_type_to_ch(pg_type: str, precision=None, scale=None, nullable=False) -> str:
    """
    Map a single PostgreSQL column type to a ClickHouse type string.

    Parameters
    ----------
    pg_type   : full PostgreSQL type name, e.g. "character varying", "numeric"
    precision : numeric precision (for numeric/decimal columns)
    scale     : numeric scale
    nullable  : if True, wrap result in Nullable(...)

    Returns
    -------
    ClickHouse type string, e.g. "Nullable(String)"
    """
    base = pg_type.lower().strip()

    # Strip array suffix  e.g. "integer[]" → "integer"
    if base.endswith('[]'):
        return 'Nullable(String)' if nullable else 'String'

    # numeric / decimal with explicit precision
    if base in ('numeric', 'decimal'):
        if precision is not None and scale is not None:
            ch = f'Decimal({precision}, {scale})'
        elif precision is not None:
            ch = f'Decimal({precision}, 0)'
        else:
            ch = 'String'   # unbounded numeric → String (safe)
        return f'Nullable({ch})' if nullable else ch

    # character varying / character(n) — strip the length
    for prefix in ('character varying', 'varchar', 'character', 'char',
                   'bit varying', 'varbit'):
        if base.startswith(prefix):
            ch = 'String'
            return f'Nullable({ch})' if nullable else ch

    # timestamp / timestamptz with precision  e.g. "timestamp(6) with time zone"
    if 'timestamp' in base and 'time zone' in base:
        ch = "DateTime64(6, 'UTC')"
        return f'Nullable({ch})' if nullable else ch
    if 'timestamp' in base:
        ch = "DateTime64(6)"
        return f'Nullable({ch})' if nullable else ch

    # time with/without time zone
    if 'time' in base:
        ch = 'String'
        return f'Nullable({ch})' if nullable else ch

    # lookup in base map
    ch = PG_TO_CH_BASE.get(base)
    if ch is None:
        logging.warning(f"Unknown PG type '{pg_type}', defaulting to String")
        ch = 'String'

    return f'Nullable({ch})' if nullable else ch


def is_binary_datatype(pg_type: str) -> bool:
    return pg_type.lower().strip() in binary_datatypes


# ---------------------------------------------------------------------------
# Connection helpers
# ---------------------------------------------------------------------------

def get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database):
    """
    Return a psycopg2 connection with autocommit=False.
    Use a dict cursor so rows are accessible by column name.
    """
    conn = psycopg2.connect(
        host=pg_host,
        user=pg_user,
        password=pg_password,
        port=int(pg_port),
        dbname=pg_database,
        connect_timeout=20,
        options='-c statement_timeout=0',   # long-running COPYs
    )
    conn.autocommit = True   # needed for COPY … TO STDOUT
    return conn


def execute_pg(conn, sql, params=None):
    """Execute SQL and return all rows as a list of dicts."""
    logging.debug(f"SQL={sql}")
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, params)
        try:
            rows = cur.fetchall()
        except psycopg2.ProgrammingError:
            rows = []
    return rows


def pg_execute_df(conn, sql, params=None):
    """Execute SQL and return a pandas DataFrame."""
    logging.debug(f"SQL={sql}")
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()
    if rows:
        return pd.DataFrame(rows, columns=list(rows[0].keys()))
    return pd.DataFrame()


# ---------------------------------------------------------------------------
# Schema introspection
# ---------------------------------------------------------------------------

def get_tables(conn, pg_schema, include_regex=None, exclude_regex=None):
    """
    Return a list of table names in *pg_schema* matching *include_regex*
    and not matching *exclude_regex*.  Both are PostgreSQL regexes (~).
    """
    include_clause = ""
    if include_regex:
        include_clause = f"AND table_name ~ '{include_regex}'"
    exclude_clause = ""
    if exclude_regex:
        exclude_clause = f"AND table_name !~ '{exclude_regex}'"

    sql = f"""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = '{pg_schema}'
          AND table_type = 'BASE TABLE'
          {include_clause}
          {exclude_clause}
        ORDER BY table_name
    """
    rows = execute_pg(conn, sql)
    return [r['table_name'] for r in rows]


def get_table_columns(conn, pg_schema, table_name):
    """
    Return an ordered list of dicts describing every column in *table_name*:
      column_name, pg_type, ordinal_position, is_nullable,
      character_maximum_length, numeric_precision, numeric_scale
    """
    sql = f"""
        SELECT
            column_name,
            data_type                    AS pg_type,
            ordinal_position,
            is_nullable,
            character_maximum_length,
            numeric_precision,
            numeric_scale,
            udt_name
        FROM information_schema.columns
        WHERE table_schema = '{pg_schema}'
          AND table_name   = '{table_name}'
        ORDER BY ordinal_position
    """
    rows = execute_pg(conn, sql)
    result = []
    for r in rows:
        nullable = (r['is_nullable'] == 'YES')
        ch_type = pg_type_to_ch(
            r['pg_type'],
            precision=r['numeric_precision'],
            scale=r['numeric_scale'],
            nullable=nullable,
        )
        result.append({
            'column_name':      r['column_name'],
            'pg_type':          r['pg_type'],
            'udt_name':         r['udt_name'],
            'ch_type':          ch_type,
            'nullable':         nullable,
            'ordinal_position': r['ordinal_position'],
        })
    return result


def get_table_pk(conn, pg_schema, table_name):
    """
    Return a list of primary-key column names for *table_name* in ordinal order.
    Returns [] if no PK is defined.
    """
    sql = f"""
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema    = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY'
          AND tc.table_schema    = '{pg_schema}'
          AND tc.table_name      = '{table_name}'
        ORDER BY kcu.ordinal_position
    """
    rows = execute_pg(conn, sql)
    return [r['column_name'] for r in rows]


def get_table_row_count(conn, pg_schema, table_name):
    """
    Approximate row count from pg_stat_user_tables (fast, no full scan).
    Returns -1 if stats are not available yet.
    """
    sql = f"""
        SELECT n_live_tup
        FROM pg_stat_user_tables
        WHERE schemaname = '{pg_schema}'
          AND relname    = '{table_name}'
    """
    rows = execute_pg(conn, sql)
    if rows:
        val = rows[0]['n_live_tup']
        return int(val) if val is not None else -1
    return -1


def get_current_lsn(conn):
    """
    Return the current WAL LSN as a string (e.g. '0/1A3F000') and as an integer.

    IMPORTANT: Debezium stores only the LOW 32-bit half of the LSN in offset_val.
    See DebeziumOffsetStorage.updateLsnInformation() — it does:
        lsn = lsn.split("/")[1]          # take only the part after "/"
        lsnLong = Long.parseLong(lsn, 16)  # parse as hex → long
    So we return that same low-half integer, NOT the full 64-bit value.
    """
    rows = execute_pg(conn, "SELECT pg_current_wal_lsn()::text AS lsn")
    lsn_str = rows[0]['lsn']
    # Split e.g. "0/1A3F000" → hi="0", lo="1A3F000"
    _hi, lo = lsn_str.split('/')
    # Debezium reads only the low half (right of "/")
    lsn_int = int(lo, 16)
    return lsn_str, lsn_int


def build_ch_create_table_ddl(pg_schema, table_name, columns, pk_columns, ch_database):
    """
    Generate a ClickHouse CREATE TABLE IF NOT EXISTS … DDL string that mirrors
    what the Altinity sink-connector would auto-create, including:
      - _version Nullable(UInt64)   ← snapshot rows have NULL _version
      - is_deleted UInt8 DEFAULT 0
      ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY (pk...)
    """
    col_defs = []
    for col in columns:
        ch_type = col['ch_type']
        col_defs.append(f"    `{col['column_name']}` {ch_type}")

    col_defs.append("    `_version` UInt64 DEFAULT 0")
    col_defs.append("    `is_deleted` UInt8 DEFAULT 0")

    cols_sql = ",\n".join(col_defs)

    if pk_columns:
        order_by = ", ".join(f"`{c}`" for c in pk_columns)
    else:
        order_by = "tuple()"

    ddl = (
        f"CREATE TABLE IF NOT EXISTS `{ch_database}`.`{table_name}`\n"
        f"(\n{cols_sql}\n)\n"
        f"ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
        f"ORDER BY ({order_by})\n"
        f"SETTINGS index_granularity = 8192"
    )
    return ddl


def resolve_credentials_from_pgpass(pgpass_file=None):
    """
    Parse ~/.pgpass (format: hostname:port:database:username:password).
    Returns the first matching (user, password) pair, or (None, None).
    """
    if pgpass_file is None:
        pgpass_file = os.path.expanduser('~/.pgpass')
    if not os.path.isfile(pgpass_file):
        return (None, None)
    with open(pgpass_file, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split(':')
            if len(parts) >= 5:
                return (parts[3], parts[4])
    return (None, None)
