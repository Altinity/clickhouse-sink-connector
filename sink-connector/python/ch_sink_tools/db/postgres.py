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
try:
    import pandas as pd
    _PANDAS_AVAILABLE = True
except ImportError:
    _PANDAS_AVAILABLE = False

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
    'interval':                      'String',    # ← the bug that broke staging

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


def pg_type_to_ch(pg_type: str, precision=None, scale=None, nullable=False,
                 pg_server_timezone: str = None) -> str:
    """
    Map a single PostgreSQL column type to a ClickHouse type string.

    Parameters
    ----------
    pg_type            : full PostgreSQL type name, e.g. "character varying", "numeric"
    precision          : numeric precision (for numeric/decimal columns)
    scale              : numeric scale
    nullable           : if True, wrap result in Nullable(...)
    pg_server_timezone : explicit PG server timezone (e.g. 'America/Chicago').
                         Used for 'timestamp without time zone' columns so that
                         the CH DateTime64 column has an unambiguous timezone annotation.
                         If None, falls back to bare DateTime64(6).

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
    # NOTE: 'with time zone' check must be explicit — 'timestamp without time zone'
    # also contains the substring 'time zone', so we check 'with time zone' or 'timestamptz'.
    if 'timestamp' in base:
        if 'with time zone' in base or base == 'timestamptz':
            ch = "DateTime64(6, 'UTC')"
        else:
            # Use explicit PG server timezone so the stored epoch is unambiguous.
            if pg_server_timezone:
                ch = f"DateTime64(6, '{pg_server_timezone}')"
            else:
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

def get_schemas(pg_conn, include_regex=None, exclude_regex=None):
    """Discover PostgreSQL schemas, optionally filtered by regex patterns.

    Args:
        pg_conn: PostgreSQL connection
        include_regex: Optional regex pattern — only schemas matching are included
        exclude_regex: Optional regex pattern — schemas matching are excluded

    Returns:
        List of schema names
    """
    query = """
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'pg_temp_1', 'pg_toast_temp_1')
    """
    params = []
    if include_regex:
        query += " AND schema_name ~ %s"
        params.append(include_regex)
    if exclude_regex:
        query += " AND schema_name !~ %s"
        params.append(exclude_regex)
    query += " ORDER BY schema_name"

    with pg_conn.cursor() as cur:
        cur.execute(query, params)
        return [row[0] for row in cur.fetchall()]


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


def get_server_timezone(conn) -> str:
    """
    Return the PostgreSQL server timezone string (e.g. 'America/Chicago', 'UTC').
    Queries 'SHOW timezone' via the existing connection.
    """
    rows = execute_pg(conn, 'SHOW timezone')
    if rows:
        return rows[0]['TimeZone']
    return 'UTC'


def get_table_columns(conn, pg_schema, table_name, pg_server_timezone=None,
                      override_config=None):
    """
    Return an ordered list of dicts describing every column in *table_name*:
      column_name, pg_type, ordinal_position, is_nullable,
      character_maximum_length, numeric_precision, numeric_scale

    Parameters
    ----------
    conn               : psycopg2 connection
    pg_schema          : PostgreSQL schema name
    table_name         : PostgreSQL table name
    pg_server_timezone : explicit PG server timezone for DateTime64 annotation
    override_config    : optional ColumnTypeOverrideConfig — when provided,
                         direct overrides replace the mapped CH type for matching columns
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
            pg_server_timezone=pg_server_timezone,
        )
        # Apply direct override if configured
        if override_config:
            direct_type = override_config.get_direct_override(
                pg_schema, table_name, r['column_name']
            )
            if direct_type:
                ch_type = direct_type

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

    Uses pg_index / pg_attribute system catalogs instead of information_schema
    because information_schema.table_constraints is not visible to users who
    lack SELECT privilege on the constraint catalog (e.g. replication-only users).
    """
    sql = f"""
        SELECT a.attname AS column_name
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = i.indrelid
                           AND a.attnum = ANY(i.indkey)
        WHERE i.indisprimary
          AND n.nspname = '{pg_schema}'
          AND c.relname = '{table_name}'
        ORDER BY array_position(i.indkey, a.attnum)
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

    NOTE: Use get_standby_lsn() instead when connecting to a hot standby,
    since pg_current_wal_lsn() is not available on standbys.
    """
    rows = execute_pg(conn, "SELECT pg_current_wal_lsn()::text AS lsn")
    lsn_str = rows[0]['lsn']
    # Split e.g. "0/1A3F000" → hi="0", lo="1A3F000"
    _hi, lo = lsn_str.split('/')
    # Debezium reads only the low half (right of "/")
    lsn_int = int(lo, 16)
    return lsn_str, lsn_int


def get_standby_lsn(conn):
    """
    Return the current WAL LSN as a (lsn_str, lsn_int) tuple, working on
    BOTH primary and hot-standby PostgreSQL instances.

    Strategy:
      1. Try SELECT pg_last_wal_replay_lsn()::text   ← works on standbys
      2. If NULL (primary, no replay LSN), fall back to
         SELECT pg_current_wal_lsn()::text            ← works on primaries

    LSN encoding:
      The offset table stores the full 64-bit integer:
        high_segment * 4294967296 + low_segment
      where the LSN string is "HIGH/LOW" in hex (e.g. "9C9/21AE7C20").

    Returns
    -------
    (lsn_str, lsn_int) : e.g. ('9C9/21AE7C20', 10755683362016)
    """
    # Step 1: try standby replay LSN
    try:
        rows = execute_pg(conn, "SELECT pg_last_wal_replay_lsn()::text AS lsn")
        lsn_str = rows[0]['lsn'] if rows else None
    except Exception:
        lsn_str = None

    # Step 2: fall back to primary current LSN if replay LSN is NULL
    if not lsn_str:
        rows = execute_pg(conn, "SELECT pg_current_wal_lsn()::text AS lsn")
        lsn_str = rows[0]['lsn']

    # Compute full 64-bit integer: high_segment * 2^32 + low_segment
    hi_str, lo_str = lsn_str.split('/')
    hi = int(hi_str, 16)
    lo = int(lo_str, 16)
    lsn_int = hi * 4294967296 + lo

    return lsn_str, lsn_int


# ---------------------------------------------------------------------------
# WAL replay control (for hot-standby checksum workflow)
# ---------------------------------------------------------------------------

def pause_wal_replay(conn):
    """Pause WAL replay on a PG standby. conn must have autocommit=True."""
    cur = conn.cursor()
    cur.execute("SELECT pg_wal_replay_pause()")
    cur.close()
    # Verify
    cur = conn.cursor()
    cur.execute("SELECT pg_is_wal_replay_paused()")
    paused = cur.fetchone()[0]
    cur.close()
    if not paused:
        raise RuntimeError("pg_wal_replay_pause() called but pg_is_wal_replay_paused() returned False")
    return True


def resume_wal_replay(conn):
    """Resume WAL replay on a PG standby. conn must have autocommit=True."""
    cur = conn.cursor()
    cur.execute("SELECT pg_wal_replay_resume()")
    cur.close()
    # Verify
    cur = conn.cursor()
    cur.execute("SELECT pg_is_wal_replay_paused()")
    paused = cur.fetchone()[0]
    cur.close()
    if paused:
        raise RuntimeError("pg_wal_replay_resume() called but pg_is_wal_replay_paused() still True")
    return True


def is_in_recovery(conn):
    """Check if the PG server is a standby (in recovery mode)."""
    cur = conn.cursor()
    cur.execute("SELECT pg_is_in_recovery()")
    result = cur.fetchone()[0]
    cur.close()
    return result


def is_wal_replay_paused(conn):
    """Check if WAL replay is currently paused."""
    cur = conn.cursor()
    cur.execute("SELECT pg_is_wal_replay_paused()")
    result = cur.fetchone()[0]
    cur.close()
    return result


def build_ch_create_table_ddl(pg_schema, table_name, columns, pk_columns,
                              ch_database, override_config=None):
    """
    Generate a ClickHouse CREATE TABLE IF NOT EXISTS … DDL string that mirrors
    what the Altinity sink-connector would auto-create, including:
      - _version Nullable(UInt64)   ← snapshot rows have NULL _version
      - is_deleted UInt8 DEFAULT 0
      ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY (pk...)

    Parameters
    ----------
    pg_schema       : PostgreSQL schema name
    table_name      : table name
    columns         : list of column dicts with 'column_name' and 'ch_type'
    pk_columns      : list of PK column name strings
    ch_database     : target ClickHouse database name
    override_config : optional ColumnTypeOverrideConfig for type overrides
    """
    col_defs = []
    for col in columns:
        ch_type = col['ch_type']
        col_name = col['column_name']

        # Apply direct override if configured
        if override_config:
            direct_type = override_config.get_direct_override(
                pg_schema, table_name, col_name
            )
            if direct_type:
                ch_type = direct_type

        col_defs.append(f"    `{col_name}` {ch_type}")

    # Append ALIAS column definitions before virtual columns
    if override_config:
        alias_overrides = override_config.get_alias_overrides(pg_schema, table_name)
        for ao in alias_overrides:
            col_defs.append(
                f"    `{ao.alias_column_name}` {ao.alias_type} ALIAS {ao.expression}"
            )

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
