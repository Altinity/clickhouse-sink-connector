# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : postgres_type_mapper.py
# -- Date         :
# -- Summary      : Complete PostgreSQL → ClickHouse type mapping module.
# --                Used by postgres_dumper.py to generate CREATE TABLE DDL
# --                and by clickhouse_loader.py when --source postgres is set.
# --
# -- Key design decisions:
# --   interval          → String   (avoids connector auto-create failures)
# --   jsonb / json      → String
# --   timestamptz       → DateTime64(6, 'UTC')
# --   timestamp         → DateTime64(6)
# --   uuid              → String
# --   bytea             → String
# --   numeric (no prec) → String
# --   arrays            → String
# --   _version          → Nullable(UInt64)  (snapshot rows have NULL _version)
# --   is_deleted        → UInt8 DEFAULT 0
# --
"""

import re
import logging

# ---------------------------------------------------------------------------
# Base type map  (lower-cased canonical PostgreSQL type name → CH type)
# ---------------------------------------------------------------------------
_BASE_MAP = {
    # booleans
    'boolean':  'UInt8',
    'bool':     'UInt8',

    # integers
    'smallint':    'Int16',
    'int2':        'Int16',
    'integer':     'Int32',
    'int':         'Int32',
    'int4':        'Int32',
    'bigint':      'Int64',
    'int8':        'Int64',
    'smallserial': 'Int16',
    'serial':      'Int32',
    'bigserial':   'Int64',
    'serial2':     'Int16',
    'serial4':     'Int32',
    'serial8':     'Int64',

    # floating point
    'real':              'Float32',
    'float4':            'Float32',
    'double precision':  'Float64',
    'float8':            'Float64',
    'float':             'Float64',

    # text / character
    'text':              'String',
    'varchar':           'String',
    'character varying': 'String',
    'character':         'String',
    'char':              'String',
    'name':              'String',
    'citext':            'String',
    'bpchar':            'String',

    # binary
    'bytea':             'String',

    # date / time
    'date':                        'Date32',
    'time':                        'String',
    'time without time zone':      'String',
    'time with time zone':         'String',
    'timetz':                      'String',
    'timestamp':                   "DateTime64(6)",
    'timestamp without time zone': "DateTime64(6)",
    'timestamp with time zone':    "DateTime64(6, 'UTC')",
    'timestamptz':                 "DateTime64(6, 'UTC')",
    # ↓ THE critical mapping — PostgreSQL `interval` must become String
    'interval':                    'String',

    # JSON
    'json':  'String',
    'jsonb': 'String',

    # UUID
    'uuid': 'String',

    # network
    'inet':    'String',
    'cidr':    'String',
    'macaddr': 'String',
    'macaddr8':'String',

    # geometric
    'point':   'String',
    'line':    'String',
    'lseg':    'String',
    'box':     'String',
    'path':    'String',
    'polygon': 'String',
    'circle':  'String',

    # full-text search
    'tsvector': 'String',
    'tsquery':  'String',

    # bit strings
    'bit':         'String',
    'bit varying': 'String',
    'varbit':      'String',

    # money
    'money': 'String',

    # xml
    'xml': 'String',

    # ranges
    'int4range':  'String',
    'int8range':  'String',
    'numrange':   'String',
    'tsrange':    'String',
    'tstzrange':  'String',
    'daterange':  'String',

    # system / misc
    'oid':    'String',
    'xid':    'String',
    'cid':    'String',
    'tid':    'String',
    'pg_lsn': 'String',
    'void':   'String',
    'record': 'String',
}

# ---------------------------------------------------------------------------
# Prefixes whose length / modifier suffix should be stripped before lookup
# ---------------------------------------------------------------------------
_STRIP_PREFIXES = (
    'character varying',
    'varchar',
    'character',
    'char',
    'bit varying',
    'varbit',
    'timestamp',
    'time',
    'interval',
    'numeric',
    'decimal',
)


def map_pg_type(
    pg_type: str,
    numeric_precision=None,
    numeric_scale=None,
    nullable: bool = False,
) -> str:
    """
    Map a PostgreSQL column data_type string to a ClickHouse type string.

    Parameters
    ----------
    pg_type           : PostgreSQL type as returned by information_schema.columns
                        e.g. "character varying", "numeric", "timestamp with time zone"
    numeric_precision : INTEGER precision (for numeric/decimal only)
    numeric_scale     : INTEGER scale     (for numeric/decimal only)
    nullable          : wrap result in Nullable(…) if True

    Returns
    -------
    str  — a valid ClickHouse type string
    """
    base = pg_type.lower().strip()

    # -----------------------------------------------------------------------
    # 1. Arrays  →  String
    # -----------------------------------------------------------------------
    if base.endswith('[]') or base == 'array' or base.startswith('array'):
        ch = 'String'
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 2. numeric / decimal  →  Decimal(p,s) when precision is known
    # -----------------------------------------------------------------------
    if base in ('numeric', 'decimal'):
        if numeric_precision is not None:
            s = int(numeric_scale) if numeric_scale is not None else 0
            ch = f'Decimal({int(numeric_precision)}, {s})'
        else:
            ch = 'String'   # unbounded numeric: use String to avoid overflow
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 3. Timestamp variants  (must come before generic "time" check)
    # -----------------------------------------------------------------------
    if 'timestamp' in base:
        if 'time zone' in base:
            ch = "DateTime64(6, 'UTC')"
        else:
            ch = "DateTime64(6)"
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 4. Time variants
    # -----------------------------------------------------------------------
    if base.startswith('time'):
        ch = 'String'
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 5. interval  →  String  (explicit, even with precision modifier)
    # -----------------------------------------------------------------------
    if base.startswith('interval'):
        ch = 'String'
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 6. character / varchar / bit-varying — strip length modifier then lookup
    # -----------------------------------------------------------------------
    for prefix in ('character varying', 'varchar', 'character', 'char',
                   'bit varying', 'varbit'):
        if base.startswith(prefix):
            ch = 'String'
            return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 7. Standard lookup
    # -----------------------------------------------------------------------
    ch = _BASE_MAP.get(base)
    if ch is None:
        logging.warning(
            f"postgres_type_mapper: unknown PG type '{pg_type}', defaulting to String"
        )
        ch = 'String'

    return f'Nullable({ch})' if nullable else ch


def map_udt_type(udt_name: str, nullable: bool = False) -> str:
    """
    Secondary lookup using udt_name from information_schema.columns.
    Used when data_type = 'USER-DEFINED' or 'ARRAY'.
    """
    base = udt_name.lower().lstrip('_')   # strip array prefix underscore
    ch = _BASE_MAP.get(base, 'String')
    return f'Nullable({ch})' if nullable else ch


# ---------------------------------------------------------------------------
# DDL generation helpers
# ---------------------------------------------------------------------------

def build_column_defs(columns) -> list:
    """
    Given a list of column dicts (as returned by db.postgres.get_table_columns),
    return a list of SQL column definition strings for ClickHouse CREATE TABLE.

    Each dict must have keys: column_name, ch_type
    """
    defs = []
    for col in columns:
        defs.append(f"`{col['column_name']}` {col['ch_type']}")
    # Altinity sink-connector virtual columns
    defs.append("`_version` UInt64 DEFAULT 0")
    defs.append("`is_deleted` UInt8 DEFAULT 0")
    return defs


def build_create_table(
    ch_database: str,
    table_name: str,
    columns,
    pk_columns,
) -> str:
    """
    Build a complete ClickHouse CREATE TABLE IF NOT EXISTS … statement that
    matches what the Altinity sink-connector would auto-create.

    Parameters
    ----------
    ch_database : target ClickHouse database name
    table_name  : table name (no schema prefix)
    columns     : list of dicts with 'column_name' and 'ch_type'
    pk_columns  : list of PK column name strings

    Returns
    -------
    str — complete DDL ready to execute
    """
    col_defs = build_column_defs(columns)
    cols_sql = ",\n    ".join(col_defs)

    if pk_columns:
        order_by = ", ".join(f"`{c}`" for c in pk_columns)
    else:
        order_by = "tuple()"

    ddl = (
        f"CREATE TABLE IF NOT EXISTS `{ch_database}`.`{table_name}`\n"
        f"(\n    {cols_sql}\n)\n"
        f"ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
        f"ORDER BY ({order_by})\n"
        f"SETTINGS index_granularity = 8192"
    )
    return ddl


def build_insert_structure(columns) -> str:
    """
    Build the ClickHouse input() structure string for CSV INSERT.
    Every column is treated as Nullable(String) so that clickhouse-client
    can handle NULL values represented as \\N in CSV output.

    NOTE: Uses double-quote identifiers instead of backticks so that the
    structure string survives shell expansion when embedded in commands.

    Example:
        "id" Nullable(String), "name" Nullable(String), ...
    """
    parts = []
    for col in columns:
        parts.append(f'"{col["column_name"]}" Nullable(String)')
    return ", ".join(parts)


def build_select_columns(columns) -> str:
    """
    Build the column list for the SELECT clause of clickhouse-client input().
    Timestamp columns need explicit cast; all others are passed as-is.

    NOTE: Uses double-quote identifiers instead of backticks so that column
    names survive shell expansion when embedded in shell commands.
    """
    parts = []
    for col in columns:
        name = col['column_name']
        ch_type = col['ch_type']
        bare = ch_type.replace('Nullable(', '').rstrip(')')
        # Cast timestamps so CH accepts them from CSV strings
        if bare.startswith('DateTime64'):
            parts.append(f'parseDateTime64BestEffortOrNull("{name}", 6)')
        elif bare == 'Date32':
            parts.append(f'toDate32OrNull("{name}")')
        elif bare == 'UInt8' and col.get('pg_type', '').lower() in ('boolean', 'bool'):
            # CSV will have 't'/'f' from PostgreSQL, convert to 0/1
            parts.append(
                f"multiIf(\"{name}\" = 't', 1, \"{name}\" = 'true', 1, \"{name}\" = '1', 1, 0)"
            )
        else:
            parts.append(f'"{name}"')
    return ", ".join(parts)


# ---------------------------------------------------------------------------
# Offset record helpers  (ClickHouse replica_source_info_* table)
# ---------------------------------------------------------------------------

def build_offset_insert(offset_table: str, lsn_int: int,
                        connector_name: str = 'sink-connector') -> str:
    """
    Return the SQL to write a starting CDC offset into the ClickHouse
    replica_source_info_* table so the Java connector begins CDC from
    the correct WAL position without re-running the snapshot.

    Parameters
    ----------
    offset_table   : fully-qualified table name,
                     e.g. 'altinity_sink_connector.replica_source_info_awacs_qa_dev'
    lsn_int        : LOW-32-BIT integer LSN value (as returned by get_current_lsn()).
                     Debezium stores only the hex value right of "/" in the LSN string,
                     e.g. "0/1A3F000" → 0x1A3F000 = 27516928.
    connector_name : the connector "name" property from config.yml
                     (e.g. "sink-connector-awacs-qa-sink-dev").

    OFFSET KEY FORMAT (from DebeziumOffsetStorage.getOffsetKey):
        [\"<connectorName>\",{"server":"embeddedconnector"}]
    The Java code does:
        String.format("[\\\"%s\\\",{\\\"server\\\":\\\"embeddedconnector\\\"}]", connectorName)
    which produces e.g.:
        ["sink-connector-awacs-qa-sink-dev",{"server":"embeddedconnector"}]

    OFFSET VAL FORMAT (from DebeziumOffsetStorage table comment + updateLsnInformation):
        {"transaction_id":null,"lsn_proc":<lsn_int>,"lsn":<lsn_int>,"ts_usec":<epoch_us>}
    NOTE: "snapshot_completed" is NOT a field the Java connector reads from offset_val;
    snapshot skipping is controlled exclusively by snapshot.mode=never in config.yml.
    """
    import time as _time
    ts_usec = int(_time.time() * 1_000_000)
    # Escape the connector_name for the JSON key — the Java getOffsetKey() wraps
    # the name in escaped quotes: [\"<name>\",{"server":"embeddedconnector"}]
    # i.e. the stored string is literally:  ["name",{"server":"embeddedconnector"}]
    offset_key = f'[\\"{connector_name}\\",{{"server":"embeddedconnector"}}]'

    payload = (
        '{'
        f'"transaction_id":null,'
        f'"lsn_proc":{lsn_int},'
        f'"lsn":{lsn_int},'
        f'"ts_usec":{ts_usec}'
        '}'
    )
    sql = (
        f"INSERT INTO {offset_table} "
        f"(id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
        f"VALUES "
        f"(generateUUIDv4(), '{offset_key}', '{payload}', now(), 1)"
    )
    return sql
