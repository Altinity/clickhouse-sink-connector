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
    'timestamp':                   "DateTime64(6, 'UTC')",
    'timestamp without time zone': "DateTime64(6, 'UTC')",
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
        # All timestamp variants use explicit UTC timezone to prevent
        # DST-related corruption when the CH server timezone is a
        # DST-observing zone like America/Chicago.  See Phase 85 plan:
        # plans/postgres/21-dst-timestamp-fix-plan.md
        ch = "DateTime64(6, 'UTC')"
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

    settings = "index_granularity = 8192"
    if pk_columns:
        order_by = ", ".join(f"`{c}`" for c in pk_columns)
    else:
        # Keyless source table.  ORDER BY tuple() would make every row
        # compare equal, so ReplacingMergeTree would collapse the whole table
        # to ONE row on merge / FINAL.  Mirror what the streaming connector
        # creates for a keyless table: every source column (connector-managed
        # columns excluded) is the sorting key, with allow_nullable_key = 1
        # when any of them is Nullable (ClickHouseAutoCreateTable
        # .keylessSortingKey; Spec 08.05 section 3.2).
        key_columns, nullable_key = keyless_sorting_key(
            columns, override_config=override_config, schema=schema,
            table=table_name, database=database,
        )
        if not key_columns:
            raise ValueError(
                f"Cannot derive a sorting key for {ch_database}.{table_name}: "
                f"the source table has no primary key and no column. Refusing to "
                f"create a ReplacingMergeTree table with ORDER BY tuple(), which "
                f"would collapse every row into one."
            )
        logging.error(
            f"KEYLESS TABLE {schema}.{table_name}: the source table has no PRIMARY KEY. "
            f"Using every column as the ReplacingMergeTree sorting key so distinct "
            f"rows stay distinct; rows identical in every column will still collapse. "
            f"Give the table a primary key at the source."
        )
        order_by = ", ".join(f"`{c}`" for c in key_columns)
        if nullable_key:
            settings += ", allow_nullable_key = 1"

    ddl = (
        f"CREATE TABLE IF NOT EXISTS `{ch_database}`.`{table_name}`\n"
        f"(\n    {cols_sql}\n)\n"
        f"ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
        f"ORDER BY ({order_by})\n"
        f"SETTINGS {settings}"
    )
    return ddl


# Columns the connector manages itself; never part of a source-derived key
# (ClickHouseAutoCreateTable.isConnectorManagedColumn).
_CONNECTOR_MANAGED_COLUMNS = frozenset({
    '_version', '_sign', 'is_deleted', '_is_deleted',
    '_valid_from', '_valid_to', '_operation',
})


def keyless_sorting_key(columns, override_config=None, schema=None, table=None,
                        database=None):
    """
    Sorting key for a source table without a primary key: every column in
    ordinal order, minus the connector-managed columns.  Returns
    (key_columns, any_nullable) where any_nullable is True when one of the
    key columns is declared Nullable(...) (after direct overrides), which
    ClickHouse only accepts with allow_nullable_key = 1.
    """
    db = database or "*"
    key_columns = []
    any_nullable = False
    for col in columns:
        name = col['column_name']
        if name.lower() in _CONNECTOR_MANAGED_COLUMNS:
            continue
        ch_type = col['ch_type']
        if override_config and schema and table:
            direct_type = override_config.get_direct_override(db, schema, table, name)
            if direct_type:
                ch_type = direct_type
        if ch_type.strip().startswith('Nullable('):
            any_nullable = True
        key_columns.append(name)
    return key_columns, any_nullable


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
    Temporal columns are converted explicitly; all others are passed as-is.

    NOTE: Uses double-quote identifiers instead of backticks so that column
    names survive shell expansion when embedded in shell commands.

    The COPY session pins TimeZone='UTC' and DateStyle='ISO, YMD'
    (postgres_dumper.PG_SESSION_SETTINGS), so temporal text is always
    'YYYY-MM-DD[ HH:MI:SS[.ffffff][+00]][ BC]', 'infinity' or '-infinity'.
    The conversion mirrors what the streaming connector writes (Spec 07.03
    section 3.3): infinity / -infinity and values outside the ClickHouse
    range saturate to the type's bounds; a value that still does not parse
    raises (throwIf), so the INSERT fails loudly instead of storing NULL or
    the type default.  A BC timestamptz is refused, as the connector refuses
    it (FM-07.03-6).  Zone-less text is parsed in the zone the COLUMN
    declares, which is how ClickHouse reads the wall-clock digits the
    connector binds (Spec 07.03 section 3.1.3).
    """
    parts = []
    for idx, col in enumerate(columns):
        name = col['column_name']
        ch_type = col['ch_type']
        bare = _strip_nullable(ch_type)
        dt64 = _DATETIME64_RE.match(bare)
        if dt64:
            parts.append(_datetime64_expr(
                name, idx, int(dt64.group(1)), dt64.group(2) or None,
                refuse_bc=(col.get('pg_type', '').lower() == 'timestamp with time zone'),
            ))
        elif bare == 'Date32':
            parts.append(_date32_expr(name, idx))
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


# ClickHouse DateTime64 / Date32 range, as bounded by the streaming connector
# (DataTypeRange.DATETIME64_MIN/MAX: 1900-01-01 00:00:00 .. 2299-12-31
# 23:59:59 UTC; Date32 1900-01-01 .. 2299-12-31).
_TEMPORAL_MIN_YEAR = 1900
_TEMPORAL_MAX_YEAR = 2299
_DATETIME64_MIN_UTC = '1900-01-01 00:00:00'
_DATETIME64_MAX_UTC = '2299-12-31 23:59:59'
_DATE32_MIN = '1900-01-01'
_DATE32_MAX = '2299-12-31'

_DATETIME64_RE = re.compile(r"^DateTime64\(\s*(\d+)\s*(?:,\s*'([^']*)'\s*)?\)$")


def _strip_nullable(ch_type: str) -> str:
    t = ch_type.strip()
    if t.startswith('Nullable(') and t.endswith(')'):
        return t[len('Nullable('):-1].strip()
    return t


def _year_expr(c: str) -> str:
    # Leading year digits of ISO text; NULL for 'infinity' / '-infinity'.
    return f"toInt32OrNull(substring({c}, 1, position({c}, '-') - 1))"


def _refusal_message(name: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_ .$-]", "?", name)
    return (f"ch-pg-dump: column {safe} holds a temporal value with no "
            f"ClickHouse representation")


def _guarded(name: str, idx: int, value_expr: str, extra_refusal: str = None) -> str:
    """Wrap value_expr so a non-NULL input that converts to NULL raises."""
    c = f'"{name}"'
    alias = f'"_ch_pg_dump_v{idx}"'
    refuse = f"isNull(({value_expr}) AS {alias})"
    if extra_refusal:
        refuse = f"({refuse} OR {extra_refusal})"
    return (f"if(throwIf(isNotNull({c}) AND {refuse}, "
            f"'{_refusal_message(name)}') = 0, {alias}, NULL)")


def _datetime64_expr(name: str, idx: int, precision: int, zone, refuse_bc: bool) -> str:
    c = f'"{name}"'
    tz = zone or 'UTC'
    # Bounds are the connector's UTC instants, expressed in the column zone.
    lo = f"toTimeZone(toDateTime64('{_DATETIME64_MIN_UTC}', {precision}, 'UTC'), '{tz}')"
    hi = f"toTimeZone(toDateTime64('{_DATETIME64_MAX_UTC}', {precision}, 'UTC'), '{tz}')"
    year = _year_expr(c)
    branches = [
        f"isNull({c}), NULL",
        f"{c} = 'infinity', {hi}",
        f"{c} = '-infinity', {lo}",
    ]
    if not refuse_bc:
        branches.append(f"endsWith({c}, ' BC'), {lo}")
    branches += [
        f"{year} < {_TEMPORAL_MIN_YEAR}, {lo}",
        f"{year} > {_TEMPORAL_MAX_YEAR}, {hi}",
        f"parseDateTime64BestEffortOrNull({c}, {precision}, '{tz}')",
    ]
    value = "multiIf(" + ", ".join(branches) + ")"
    return _guarded(name, idx, value,
                    extra_refusal=f"endsWith({c}, ' BC')" if refuse_bc else None)


def _date32_expr(name: str, idx: int) -> str:
    c = f'"{name}"'
    lo = f"toDate32('{_DATE32_MIN}')"
    hi = f"toDate32('{_DATE32_MAX}')"
    year = _year_expr(c)
    value = (
        f"multiIf(isNull({c}), NULL, {c} = 'infinity', {hi}, {c} = '-infinity', {lo}, "
        f"endsWith({c}, ' BC'), {lo}, {year} < {_TEMPORAL_MIN_YEAR}, {lo}, "
        f"{year} > {_TEMPORAL_MAX_YEAR}, {hi}, toDate32OrNull({c}))"
    )
    return _guarded(name, idx, value)


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
