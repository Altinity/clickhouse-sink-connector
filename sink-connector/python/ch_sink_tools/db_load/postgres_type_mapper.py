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
# --   numeric (no prec) → Decimal(18, 6)  (safe default; matches Java connector)
# --   numeric(p)        → Decimal(p, 0)
# --   numeric(p,s)      → Decimal(p, s)
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


def _normalize_type(pg_type: str):
    """
    Split a parametric PostgreSQL type string into its base name and optional
    precision / scale modifiers.

    Examples
    --------
    >>> _normalize_type('numeric(10,2)')
    ('numeric', '10', '2')
    >>> _normalize_type('numeric(18)')
    ('numeric', '18', None)
    >>> _normalize_type('numeric')
    ('numeric', None, None)
    >>> _normalize_type('varchar(255)')
    ('varchar', '255', None)
    >>> _normalize_type('double precision')
    ('double precision', None, None)

    Returns
    -------
    tuple[str, str | None, str | None]
        (base_type, precision, scale)  — precision and scale are strings or None.
    """
    pg_type = pg_type.strip().lower()
    m = re.match(r'^([a-z][a-z ]*?)\s*\(\s*(\d+)(?:\s*,\s*(\d+))?\s*\)$', pg_type)
    if m:
        return m.group(1).strip(), m.group(2), m.group(3)
    return pg_type, None, None


def map_pg_type(
    pg_type: str,
    numeric_precision=None,
    numeric_scale=None,
    nullable: bool = False,
    pg_server_timezone: str = None,
) -> str:
    """
    Map a PostgreSQL column data_type string to a ClickHouse type string.

    Parameters
    ----------
    pg_type           : PostgreSQL type as returned by information_schema.columns
                        OR the full parametric text from the ANTLR parse tree,
                        e.g. "character varying", "numeric", "numeric(10,2)",
                        "timestamp with time zone", "varchar(255)"
    numeric_precision : INTEGER precision (for numeric/decimal only).
                        When provided explicitly (e.g. from information_schema),
                        takes precedence over any modifiers embedded in pg_type.
    numeric_scale     : INTEGER scale     (for numeric/decimal only)
    nullable          : wrap result in Nullable(…) if True
    pg_server_timezone: explicit PG server timezone (e.g. 'America/Chicago').
                        When set, used as the timezone annotation for
                        "timestamp without time zone" columns in CH so that
                        the stored DateTime64 epoch is unambiguous.
                        If None, falls back to bare DateTime64(6).

    Returns
    -------
    str  — a valid ClickHouse type string

    Numeric / Decimal mapping rules
    --------------------------------
    Explicit params take priority; otherwise the modifier is parsed from pg_type:
      numeric(p,s)  / decimal(p,s)  →  Decimal(p, s)
      numeric(p)    / decimal(p)    →  Decimal(p, 0)
      numeric       / decimal       →  Decimal(18, 6)   (safe default)
    """
    # -----------------------------------------------------------------------
    # 0. Normalize — parse precision/scale out of the type string when they
    #    are embedded (e.g. "numeric(10,2)" from the ANTLR listener).
    #    Explicit caller-supplied numeric_precision / numeric_scale win.
    # -----------------------------------------------------------------------
    parsed_base, parsed_precision, parsed_scale = _normalize_type(pg_type)
    base = parsed_base  # lower-cased, stripped, no modifiers

    # Resolve effective precision / scale:
    #   explicit args (information_schema callers) beat embedded modifiers.
    eff_precision = numeric_precision if numeric_precision is not None else parsed_precision
    eff_scale = numeric_scale if numeric_scale is not None else parsed_scale

    # -----------------------------------------------------------------------
    # 1. Arrays  →  String
    # -----------------------------------------------------------------------
    if base.endswith('[]') or base == 'array' or base.startswith('array'):
        ch = 'String'
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 2. numeric / decimal  →  Decimal(p,s)
    # -----------------------------------------------------------------------
    if base in ('numeric', 'decimal'):
        if eff_precision is not None:
            s = int(eff_scale) if eff_scale is not None else 0
            ch = f'Decimal({int(eff_precision)}, {s})'
        else:
            ch = 'Decimal(18, 6)'   # bare numeric/decimal: safe default
        return f'Nullable({ch})' if nullable else ch

    # -----------------------------------------------------------------------
    # 3. Timestamp variants  (must come before generic "time" check)
    # -----------------------------------------------------------------------
    if 'timestamp' in base:
        # 'timestamp with time zone' / 'timestamptz'  →  UTC (absolute epoch)
        # 'timestamp without time zone' / 'timestamp'  →  PG server TZ (local time)
        if 'with time zone' in base or base == 'timestamptz':
            ch = "DateTime64(6, 'UTC')"
        else:
            # Use explicit PG server timezone so the stored epoch is unambiguous.
            # Without this, DateTime64(6) renders in CH server TZ by default.
            if pg_server_timezone:
                ch = f"DateTime64(6, '{pg_server_timezone}')"
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

def build_column_defs(columns, override_config=None, schema=None, table=None,
                      database=None) -> list:
    """
    Given a list of column dicts (as returned by db.postgres.get_table_columns),
    return a list of SQL column definition strings for ClickHouse CREATE TABLE.

    Each dict must have keys: column_name, ch_type

    Parameters
    ----------
    columns         : list of column dicts
    override_config : optional ColumnTypeOverrideConfig — when provided,
                      direct overrides replace the mapped CH type for matching columns
    schema          : PG schema name (needed for override lookups)
    table           : PG table name  (needed for override lookups)
    database        : PG database name (needed for override lookups)
    """
    defs = []
    db = database or "*"
    for col in columns:
        ch_type = col['ch_type']
        col_name = col['column_name']

        # Apply direct override if configured
        if override_config and schema and table:
            direct_type = override_config.get_direct_override(db, schema, table, col_name)
            if direct_type:
                ch_type = direct_type

        defs.append(f"`{col_name}` {ch_type}")

    # Append ALIAS column definitions before virtual columns
    if override_config and schema and table:
        alias_overrides = override_config.get_alias_overrides(db, schema, table)
        for ao in alias_overrides:
            defs.append(
                f"`{ao.alias_column_name}` {ao.alias_type} ALIAS {ao.expression}"
            )

    # Altinity sink-connector virtual columns
    defs.append("`_version` UInt64 DEFAULT 0")
    defs.append("`is_deleted` UInt8 DEFAULT 0")
    return defs


def build_create_table(
    ch_database: str,
    table_name: str,
    columns,
    pk_columns,
    override_config=None,
    schema=None,
    database=None,
) -> str:
    """
    Build a complete ClickHouse CREATE TABLE IF NOT EXISTS … statement that
    matches what the Altinity sink-connector would auto-create.

    Parameters
    ----------
    ch_database     : target ClickHouse database name
    table_name      : table name (no schema prefix)
    columns         : list of dicts with 'column_name' and 'ch_type'
    pk_columns      : list of PK column name strings
    override_config : optional ColumnTypeOverrideConfig for type overrides
    schema          : PG schema name (needed for override lookups)
    database        : PG database name (needed for override lookups)

    Returns
    -------
    str — complete DDL ready to execute
    """
    col_defs = build_column_defs(
        columns,
        override_config=override_config,
        schema=schema,
        table=table_name,
        database=database,
    )
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
            # For Nullable columns, preserve NULL (don't convert to 0)
            bool_expr = f"multiIf(\"{name}\" = 't', 1, \"{name}\" = 'true', 1, \"{name}\" = '1', 1, 0)"
            if ch_type.startswith('Nullable'):
                parts.append(
                    f"if(isNull(\"{name}\"), null, {bool_expr})"
                )
            else:
                parts.append(bool_expr)
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
                     e.g. 'altinity_sink_connector.replica_source_info_db_name_dev'
    lsn_int        : LOW-32-BIT integer LSN value (as returned by get_current_lsn()).
                     Debezium stores only the hex value right of "/" in the LSN string,
                     e.g. "0/1A3F000" → 0x1A3F000 = 27516928.
    connector_name : the connector "name" property from config.yml
                     (e.g. "sink-connector-dev").

    OFFSET KEY FORMAT (from DebeziumOffsetStorage.getOffsetKey):
        [\"<connectorName>\",{"server":"embeddedconnector"}]
    The Java code does:
        String.format("[\\\"%s\\\",{\\\"server\\\":\\\"embeddedconnector\\\"}]", connectorName)
    which produces e.g.:
        ["sink-connector-dev",{"server":"embeddedconnector"}]

    OFFSET VAL FORMAT (from DebeziumOffsetStorage table comment + updateLsnInformation):
        {"transaction_id":null,"lsn_proc":<lsn_int>,"lsn":<lsn_int>,"ts_usec":<epoch_us>}
    NOTE: "snapshot_completed" is NOT a field the Java connector reads from offset_val;
    snapshot skipping is controlled exclusively by snapshot.mode=never in config.yml.
    """
    import time as _time
    import json as _json
    import uuid as _uuid
    ts_usec = int(_time.time() * 1_000_000)
    # Construct the offset key to exactly match what the Java connector writes.
    # Java DebeziumOffsetStorage.getOffsetKey() (line 58):
    #   String.format("[\"%s\",{\"server\":\"embeddedconnector\"}]", connectorName)
    # which produces:  ["<name>",{"server":"embeddedconnector"}]  (no backslashes).
    # Using json.dumps with separators=(',',':') produces the identical byte sequence.
    offset_key = _json.dumps(
        [connector_name, {"server": "embeddedconnector"}],
        separators=(',', ':'),
    )

    # Use a deterministic UUID (v3/MD5) derived from the offset_key so that
    # all updates for the same connector produce the same `id` value.
    # This matches the Java fix in DebeziumOffsetStorage.updateDebeziumStorageRow()
    # which uses UUID.nameUUIDFromBytes(offsetKey.getBytes(UTF-8)) — also UUID v3/MD5.
    deterministic_id = str(_uuid.uuid3(_uuid.NAMESPACE_URL, offset_key))

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
        f"('{deterministic_id}', '{offset_key}', '{payload}', now(), 1)"
    )
    return sql
