# PostgreSQL Batch Dump Implementation Plan

## Executive Summary

This document provides a detailed implementation specification for PostgreSQL batch dump functionality in the clickhouse-sink-connector project. The implementation mirrors the existing MySQL batch dump tools while leveraging PostgreSQL-specific capabilities for efficient bulk data loading.

> **⚠️ DOCUMENT STATUS — UPDATED 2026-03-01**
>
> This document was originally written as a forward-looking design spec (2026-02-27) when the batch dump tools **did not yet exist**. The core Python implementation has since been built and is **production-validated**. See [`plans/postgres/11-postgres-snapshot-cdc-architecture.md`](plans/postgres/11-postgres-snapshot-cdc-architecture.md) for the authoritative, up-to-date runbook.
>
> **Implementation Status** (as of 2026-03-01):
>
> | Component | File | Status |
> |-----------|------|--------|
> | PostgreSQL connection helpers + type mapping | [`sink-connector/python/db/postgres.py`](sink-connector/python/db/postgres.py) | ✅ **Implemented** |
> | Parallel snapshot orchestrator (pg_dump → ClickHouse) | [`sink-connector/python/db_dump/postgres_dumper.py`](sink-connector/python/db_dump/postgres_dumper.py) | ✅ **Implemented** |
> | ClickHouse DDL generation + LSN offset writer | [`sink-connector/python/db_load/postgres_type_mapper.py`](sink-connector/python/db_load/postgres_type_mapper.py) | ✅ **Implemented** |
>
> **Architecture change from original plan**: The original design spec described a DDL-parser-based approach using `pg_dump --schema-only`. The actual implementation uses `information_schema.columns` queries (via psycopg2) for schema discovery and `psql COPY … TO STDOUT FORMAT CSV | clickhouse-client INSERT FORMAT CSV` pipes for streaming data — **no intermediate disk I/O, no DDL file parsing required**.
>
> **Key bug fixes applied on 2026-02-28** (see Plan 11 §Bug Fixes for details):
> 1. `clickhouse_driver` `Connection` has no `__enter__`/`__exit__` — use explicit `try/finally/conn.close()`
> 2. `offset_key` must be `["<connector_name>",{"server":"embeddedconnector"}]` (matched from `DebeziumOffsetStorage.getOffsetKey()`)
> 3. `offset_val` JSON fields: `transaction_id`, `lsn_proc`, `lsn`, `ts_usec` (no `snapshot_completed` field)
> 4. `--connector_name` CLI arg added; must match Java connector `name` in `config.yml`
> 5. `get_current_lsn()` returns only the **low-32-bit hex** part (right side of `/` in PG LSN string)

**Goal**: Enable initial bulk data loading from PostgreSQL to ClickHouse with full data type conversion, DDL translation, and validation capabilities.

---

## 1. Architecture Overview

### 1.1 Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   PostgreSQL Batch Dump System              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────┐         ┌──────────────────┐           │
│  │ postgres_      │  DDL    │ postgres_parser/ │           │
│  │ dumper.py      ├────────▶│  - Lexer         │           │
│  │                │         │  - Parser        │           │
│  └────────┬───────┘         │  - Listener      │           │
│           │                 └──────────────────┘           │
│           │ CSV/Custom                                      │
│           │ Format                                          │
│           ▼                                                 │
│  ┌────────────────┐         ┌──────────────────┐           │
│  │ clickhouse_    │  Data   │ postgres_        │           │
│  │ loader.py      ├────────▶│ checksum.py      │           │
│  │ (enhanced)     │         │                  │           │
│  └────────────────┘         └──────────────────┘           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
         │                              │
         │                              │
         ▼                              ▼
┌─────────────────┐          ┌──────────────────┐
│   PostgreSQL    │          │   ClickHouse     │
│   Database      │          │   Database       │
└─────────────────┘          └──────────────────┘
```

### 1.2 Data Flow

1. **Dump Phase**: [`postgres_dumper.py`](sink-connector/python/db_dump/postgres_dumper.py) extracts schema and data
2. **Parse Phase**: [`postgres_parser/postgres_parser.py`](sink-connector/python/db_load/postgres_parser/postgres_parser.py) converts DDL
3. **Load Phase**: [`clickhouse_loader.py`](sink-connector/python/db_load/clickhouse_loader.py) creates tables and inserts data
4. **Validation Phase**: [`postgres_table_checksum.py`](sink-connector/python/db_compare/postgres_table_checksum.py) verifies data integrity

---

## 2. Component Specifications

### 2.1 PostgreSQL Dumper (`postgres_dumper.py`)

**Location**: `sink-connector/python/db_dump/postgres_dumper.py`

**Purpose**: Dump PostgreSQL database schema and data in formats compatible with ClickHouse loader.

#### 2.1.1 Implementation Strategy

PostgreSQL provides multiple dump utilities:
- **`pg_dump`**: Standard PostgreSQL dump utility
- **`COPY` command**: High-performance data export to CSV
- **Custom format**: Binary dump for large datasets

**Recommended Approach**: Hybrid strategy using both `pg_dump` and `COPY`

#### 2.1.2 Core Functions

```python
# File: sink-connector/python/db_dump/postgres_dumper.py

def dump_schema(conn, database, tables, output_dir, schema='public'):
    """
    Extract DDL using pg_dump --schema-only
    
    Args:
        conn: PostgreSQL connection
        database: Target database name
        tables: List of tables to dump
        output_dir: Directory for dump files
        schema: PostgreSQL schema (default: public)
    
    Output:
        {database}@{table}.sql files with CREATE TABLE statements
    """
    pass

def dump_table_data(conn, database, table, output_dir, 
                    threads=1, chunk_size=1000000, 
                    where_clause=None, schema='public'):
    """
    Export table data using PostgreSQL COPY TO CSV
    
    Args:
        conn: PostgreSQL connection
        database: Database name
        table: Table name
        output_dir: Output directory
        threads: Parallel export threads
        chunk_size: Rows per chunk
        where_clause: Optional filter
        schema: Schema name
    
    Output:
        {database}.{table}.*.csv.gz files
    """
    pass

def get_table_primary_key(conn, database, table, schema='public'):
    """
    Query information_schema for primary key columns
    
    Returns:
        List of primary key column names
    """
    query = """
        SELECT a.attname
        FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid 
            AND a.attnum = ANY(i.indkey)
        WHERE i.indrelid = %s::regclass
        AND i.indisprimary;
    """
    pass

def generate_pg_dump_command(database, table, output_dir, 
                             schema='public', schema_only=False):
    """
    Generate pg_dump command for schema extraction
    
    Returns:
        pg_dump command string
    """
    if schema_only:
        return f"pg_dump -h {host} -U {user} -d {database} \
                -t {schema}.{table} --schema-only \
                -f {output_dir}/{database}@{table}.sql"
    pass

def generate_copy_command(database, table, output_file, 
                         where_clause=None, schema='public'):
    """
    Generate COPY TO CSV command for data export
    
    Returns:
        COPY command for PostgreSQL
    """
    copy_sql = f"""
        COPY (SELECT * FROM {schema}.{table} 
              {where_clause if where_clause else ''})
        TO STDOUT WITH (FORMAT CSV, HEADER, DELIMITER ',', 
                       QUOTE '"', ESCAPE '"', NULL '\\N')
    """
    return copy_sql

def dump_database_parallel(conn, database, output_dir, 
                          tables_regex='.*', threads=4, 
                          schema='public', data_only=False, 
                          schema_only=False):
    """
    Parallel dump of multiple tables using ThreadPoolExecutor
    
    Similar to mysql_dumper.py parallel execution pattern
    """
    pass
```

#### 2.1.3 PostgreSQL-Specific Features

**a) Schema Support**
```python
def get_tables_from_schema(conn, database, schema='public', 
                          tables_regex='.*'):
    """
    Query pg_catalog for tables matching regex in schema
    """
    query = """
        SELECT schemaname, tablename 
        FROM pg_tables 
        WHERE schemaname = %s 
        AND tablename ~ %s
        ORDER BY tablename;
    """
    pass
```

**b) Partition Handling**
```python
def get_table_partitions(conn, database, table, schema='public'):
    """
    Detect partitioned tables and their partitions
    """
    query = """
        SELECT 
            schemaname,
            tablename,
            pg_get_expr(c.relpartbound, c.oid) as partition_expr
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE i.inhparent = %s::regclass;
    """
    pass
```

**c) Large Object Handling**
```python
def dump_large_objects(conn, database, output_dir):
    """
    Export PostgreSQL Large Objects (LOBs) if present
    
    Uses pg_dump --blobs or pg_largeobject catalog
    """
    pass
```

#### 2.1.4 Data Type Extraction Metadata

```python
def get_table_column_info(conn, database, table, schema='public'):
    """
    Extract comprehensive column metadata
    
    Returns:
        List of dicts with:
        - column_name
        - data_type (PostgreSQL type)
        - is_nullable
        - column_default
        - character_maximum_length
        - numeric_precision
        - numeric_scale
        - datetime_precision
        - udt_name (underlying type)
    """
    query = """
        SELECT 
            column_name,
            data_type,
            udt_name,
            is_nullable,
            column_default,
            character_maximum_length,
            numeric_precision,
            numeric_scale,
            datetime_precision
        FROM information_schema.columns
        WHERE table_schema = %s 
        AND table_name = %s
        ORDER BY ordinal_position;
    """
    pass
```

#### 2.1.5 Command-Line Interface

Reference: [`mysql_dumper.py`](sink-connector/python/db_dump/mysql_dumper.py:177-285)

```python
def main():
    parser = argparse.ArgumentParser(
        description='Dump PostgreSQL database for ClickHouse loading'
    )
    parser.add_argument('--postgres_host', required=True)
    parser.add_argument('--postgres_port', default=5432)
    parser.add_argument('--postgres_user', required=True)
    parser.add_argument('--postgres_password', required=True)
    parser.add_argument('--postgres_database', required=True)
    parser.add_argument('--postgres_schema', default='public')
    parser.add_argument('--dump_dir', required=True)
    parser.add_argument('--tables_regex', default='.*')
    parser.add_argument('--exclude_tables_regex', default=None)
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--chunk_size', type=int, default=1000000)
    parser.add_argument('--schema_only', action='store_true')
    parser.add_argument('--data_only', action='store_true')
    parser.add_argument('--where', default=None)
    parser.add_argument('--compress', choices=['gzip', 'zstd'], default='gzip')
    parser.add_argument('--format', choices=['csv', 'custom'], default='csv')
    args = parser.parse_args()
```

---

### 2.2 PostgreSQL DDL Parser

**Location**: `sink-connector/python/db_load/postgres_parser/postgres_parser.py`

**Purpose**: Convert PostgreSQL CREATE TABLE DDL to ClickHouse DDL.

#### 2.2.1 Parser Implementation Options

**Option A: ANTLR4-based Parser** (Recommended)
- Pros: Robust, handles complex DDL, consistent with MySQL parser
- Cons: Requires ANTLR4 grammar maintenance
- Reference: [`mysql_parser.py`](sink-connector/python/db_load/mysql_parser/mysql_parser.py)

**Option B: Regex-based Parser**
- Pros: Simpler, faster development
- Cons: Less robust for complex DDL
- Note: PostgreSQL DDL is simpler than MySQL (no engine clauses, etc.)

**Recommendation**: Start with Option B (regex), upgrade to Option A if needed.

#### 2.2.2 Core Conversion Function

```python
# File: sink-connector/python/db_load/postgres_parser/postgres_parser.py

import re
import logging
from typing import Tuple, List, Dict

def convert_to_clickhouse_table(source_ddl: str, 
                                rmt_delete_support: bool = True,
                                partition_options: str = '',
                                datetime_timezone: str = None) -> Tuple[str, List[str]]:
    """
    Convert PostgreSQL CREATE TABLE to ClickHouse DDL
    
    Args:
        source_ddl: PostgreSQL CREATE TABLE statement
        rmt_delete_support: Add _sign column for ReplacingMergeTree
        partition_options: Custom partition clause
        datetime_timezone: Override timezone for DateTime columns
    
    Returns:
        Tuple of (clickhouse_ddl, column_list)
    
    Example Input:
        CREATE TABLE public.users (
            id UUID PRIMARY KEY,
            email VARCHAR(255) NOT NULL,
            age INTEGER,
            balance NUMERIC(10,2),
            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
        );
    
    Example Output:
        CREATE TABLE public.users (
            id UUID,
            email String,
            age Nullable(Int32),
            balance Nullable(Decimal(10,2)),
            created_at DateTime64(6, 'UTC'),
            _sign Int8 DEFAULT 1,
            _version UInt64
        ) ENGINE = ReplacingMergeTree(_version)
        ORDER BY id;
    """
    pass
```

#### 2.2.3 PostgreSQL to ClickHouse Data Type Mapping

| PostgreSQL Type | PostgreSQL Example | ClickHouse Type | Notes |
|-----------------|-------------------|-----------------|-------|
| **Integer Types** |
| `SMALLINT`, `INT2` | `INT2` | `Int16` | 2 bytes |
| `INTEGER`, `INT`, `INT4` | `INT4` | `Int32` | 4 bytes |
| `BIGINT`, `INT8` | `INT8` | `Int64` | 8 bytes |
| `SERIAL` | `SERIAL` | `Int32` | Auto-increment removed |
| `BIGSERIAL` | `BIGSERIAL` | `Int64` | Auto-increment removed |
| **Numeric Types** |
| `NUMERIC(p,s)` | `NUMERIC(21,5)` | `Decimal(p,s)` | Preserve precision |
| `DECIMAL(p,s)` | `DECIMAL(10,2)` | `Decimal(p,s)` | Alias for NUMERIC |
| `NUMERIC` | `NUMERIC` | `Decimal(38,9)` | Default precision |
| `REAL`, `FLOAT4` | `REAL` | `Float32` | 4 bytes |
| `DOUBLE PRECISION`, `FLOAT8` | `FLOAT8` | `Float64` | 8 bytes |
| **String Types** |
| `VARCHAR(n)` | `VARCHAR(255)` | `String` | No length limit in CH |
| `CHAR(n)` | `CHAR(10)` | `FixedString(n)` | Fixed length |
| `TEXT` | `TEXT` | `String` | Unlimited text |
| **Binary Types** |
| `BYTEA` | `BYTEA` | `String` | Store as hex or base64 |
| **Date/Time Types** |
| `DATE` | `DATE` | `Date32` | Range: 1900-2299 |
| `TIME` | `TIME` | `String` | No native Time type |
| `TIME WITH TIME ZONE` | `TIME WITH TIME ZONE` | `String` | Convert to string |
| `TIMESTAMP` | `TIMESTAMP` | `DateTime64(6)` | Microsecond precision |
| `TIMESTAMP WITH TIME ZONE` | `TIMESTAMPTZ` | `DateTime64(6, 'UTC')` | Always store in UTC |
| **Boolean** |
| `BOOLEAN`, `BOOL` | `BOOLEAN` | `Bool` or `UInt8` | CH 22.3+ has Bool |
| **UUID** |
| `UUID` | `UUID` | `UUID` | Native UUID support |
| **JSON** |
| `JSON` | `JSON` | `String` | Store as JSON string |
| `JSONB` | `JSONB` | `String` | Store as JSON string |
| **Array Types** |
| `INTEGER[]` | `INT[]` | `Array(Int32)` | Native array support |
| `TEXT[]` | `TEXT[]` | `Array(String)` | String arrays |
| `UUID[]` | `UUID[]` | `Array(UUID)` | UUID arrays |
| **Special Types** |
| `HSTORE` | `HSTORE` | `Map(String, String)` | Key-value pairs |
| `CIDR` | `CIDR` | `IPv4` or `IPv6` | Network addresses |
| `INET` | `INET` | `IPv4` or `IPv6` | IP addresses |
| `MACADDR` | `MACADDR` | `String` | MAC addresses |
| `XML` | `XML` | `String` | Store as text |
| **Geometric Types** |
| `POINT` | `POINT` | `Tuple(Float64, Float64)` | (x, y) coordinates |
| `POLYGON` | `POLYGON` | `String` | GeoJSON or WKT |
| `GEOMETRY` | `GEOMETRY` | `String` | PostGIS types as WKT |

#### 2.2.4 Type Conversion Implementation

```python
def map_postgres_type_to_clickhouse(pg_type: str, 
                                   is_nullable: bool,
                                   precision: int = None,
                                   scale: int = None,
                                   datetime_timezone: str = None) -> str:
    """
    Map PostgreSQL data type to ClickHouse type
    
    Args:
        pg_type: PostgreSQL type (e.g., 'character varying', 'timestamp with time zone')
        is_nullable: Whether column allows NULL
        precision: Numeric precision
        scale: Numeric scale
        datetime_timezone: Timezone for DateTime columns
    
    Returns:
        ClickHouse type string
    """
    pg_type_lower = pg_type.lower().strip()
    
    # Integer types
    if pg_type_lower in ('smallint', 'int2'):
        ch_type = 'Int16'
    elif pg_type_lower in ('integer', 'int', 'int4', 'serial'):
        ch_type = 'Int32'
    elif pg_type_lower in ('bigint', 'int8', 'bigserial'):
        ch_type = 'Int64'
    
    # Numeric types
    elif pg_type_lower.startswith('numeric') or pg_type_lower.startswith('decimal'):
        if precision and scale is not None:
            ch_type = f'Decimal({precision}, {scale})'
        else:
            ch_type = 'Decimal(38, 9)'  # Default precision
    
    # Floating point
    elif pg_type_lower in ('real', 'float4'):
        ch_type = 'Float32'
    elif pg_type_lower in ('double precision', 'float8'):
        ch_type = 'Float64'
    
    # String types
    elif pg_type_lower.startswith('character varying') or pg_type_lower.startswith('varchar'):
        ch_type = 'String'
    elif pg_type_lower.startswith('character(') or pg_type_lower.startswith('char('):
        # Extract length: char(10) -> FixedString(10)
        match = re.match(r'char(?:acter)?\((\d+)\)', pg_type_lower)
        if match:
            ch_type = f'FixedString({match.group(1)})'
        else:
            ch_type = 'String'
    elif pg_type_lower == 'text':
        ch_type = 'String'
    
    # Binary
    elif pg_type_lower == 'bytea':
        ch_type = 'String'
    
    # Date/Time types
    elif pg_type_lower == 'date':
        ch_type = 'Date32'
    elif pg_type_lower == 'time' or pg_type_lower == 'time without time zone':
        ch_type = 'String'  # No native Time type
    elif pg_type_lower == 'time with time zone':
        ch_type = 'String'
    elif pg_type_lower in ('timestamp', 'timestamp without time zone'):
        ch_type = 'DateTime64(6)'
    elif pg_type_lower in ('timestamp with time zone', 'timestamptz'):
        tz = datetime_timezone or 'UTC'
        ch_type = f"DateTime64(6, '{tz}')"
    
    # Boolean
    elif pg_type_lower in ('boolean', 'bool'):
        ch_type = 'Bool'  # or 'UInt8' for older ClickHouse
    
    # UUID
    elif pg_type_lower == 'uuid':
        ch_type = 'UUID'
    
    # JSON
    elif pg_type_lower in ('json', 'jsonb'):
        ch_type = 'String'
    
    # Array types
    elif pg_type_lower.endswith('[]'):
        base_type = pg_type_lower[:-2]
        inner_type = map_postgres_type_to_clickhouse(base_type, False)
        ch_type = f'Array({inner_type})'
    
    # Network types
    elif pg_type_lower in ('cidr', 'inet'):
        ch_type = 'IPv4'  # or IPv6 based on detection
    elif pg_type_lower == 'macaddr':
        ch_type = 'String'
    
    # Special types
    elif pg_type_lower == 'hstore':
        ch_type = 'Map(String, String)'
    elif pg_type_lower == 'xml':
        ch_type = 'String'
    
    # Geometric types
    elif pg_type_lower == 'point':
        ch_type = 'Tuple(Float64, Float64)'
    elif pg_type_lower in ('polygon', 'geometry', 'geography'):
        ch_type = 'String'  # Store as WKT
    
    else:
        # Default fallback
        logging.warning(f"Unknown PostgreSQL type '{pg_type}', using String")
        ch_type = 'String'
    
    # Apply Nullable wrapper
    if is_nullable and not ch_type.startswith('Nullable'):
        ch_type = f'Nullable({ch_type})'
    
    return ch_type
```

#### 2.2.5 DDL Parsing Logic

```python
def parse_create_table(source_ddl: str) -> Dict:
    """
    Parse PostgreSQL CREATE TABLE statement
    
    Returns:
        Dict with:
        - schema: Schema name
        - table: Table name
        - columns: List of column definitions
        - primary_key: List of PK columns
        - indexes: List of indexes
        - constraints: List of constraints
    """
    result = {
        'schema': 'public',
        'table': '',
        'columns': [],
        'primary_key': [],
        'indexes': [],
        'constraints': []
    }
    
    # Extract table name with schema
    table_pattern = r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(["\w]+)\.)?(["\w]+)'
    table_match = re.search(table_pattern, source_ddl, re.IGNORECASE)
    if table_match:
        result['schema'] = table_match.group(1).strip('"') if table_match.group(1) else 'public'
        result['table'] = table_match.group(2).strip('"')
    
    # Extract column definitions
    # Match lines like: id UUID PRIMARY KEY,
    column_pattern = r'^\s*(["\w]+)\s+([^,]+?)(?:,|$)'
    
    # Parse each column
    # ... (detailed regex parsing)
    
    # Extract PRIMARY KEY constraint
    pk_pattern = r'PRIMARY\s+KEY\s*\((.*?)\)'
    pk_match = re.search(pk_pattern, source_ddl, re.IGNORECASE)
    if pk_match:
        result['primary_key'] = [col.strip().strip('"') for col in pk_match.group(1).split(',')]
    
    return result
```

#### 2.2.6 ClickHouse DDL Generation

```python
def generate_clickhouse_ddl(parsed_table: Dict, 
                           rmt_delete_support: bool,
                           partition_options: str,
                           datetime_timezone: str) -> str:
    """
    Generate ClickHouse CREATE TABLE statement
    """
    schema = parsed_table['schema']
    table = parsed_table['table']
    columns = parsed_table['columns']
    primary_key = parsed_table['primary_key']
    
    # Build column definitions
    col_defs = []
    for col in columns:
        col_name = col['name']
        ch_type = map_postgres_type_to_clickhouse(
            col['type'], 
            col['nullable'],
            col.get('precision'),
            col.get('scale'),
            datetime_timezone
        )
        col_defs.append(f"    {col_name} {ch_type}")
    
    # Add ReplacingMergeTree virtual columns
    if rmt_delete_support:
        col_defs.append("    _sign Int8 DEFAULT 1")
    col_defs.append("    _version UInt64")
    
    columns_clause = ',\n'.join(col_defs)
    
    # Determine ORDER BY clause
    if primary_key:
        order_by = ', '.join(primary_key)
    elif columns:
        # Use first column as fallback
        order_by = columns[0]['name']
    else:
        order_by = 'tuple()'
    
    # Build CREATE TABLE
    ddl = f"""CREATE TABLE {schema}.{table}
(
{columns_clause}
)
ENGINE = ReplacingMergeTree(_version)
{partition_options if partition_options else ''}
ORDER BY ({order_by});"""
    
    return ddl
```

---

### 2.3 ClickHouse Loader Enhancements

**Location**: Enhance existing [`clickhouse_loader.py`](sink-connector/python/db_load/clickhouse_loader.py)

#### 2.3.1 PostgreSQL Support

```python
# Add to clickhouse_loader.py

def parse_postgres_schema_path(path):
    """
    Parse PostgreSQL dump file naming convention
    
    Expected format: {database}@{table}.sql (matching MySQL convention)
    Or custom: {schema}.{table}.sql
    """
    p = Path(path)
    name = p.stem  # Remove .sql
    
    if '@' in name:
        # Format: database@table
        parts = name.split('@')
        schema = parts[0]
        table = parts[1]
    elif '.' in name:
        # Format: schema.table
        parts = name.split('.')
        schema = parts[0]
        table = parts[1]
    else:
        schema = 'public'
        table = name
    
    return (schema, table)

def load_postgres_dump(args):
    """
    Load PostgreSQL dump into ClickHouse
    
    Similar to load_mysql_dump but uses postgres_parser
    """
    from db_load.postgres_parser.postgres_parser import convert_to_clickhouse_table
    
    # Find schema files
    schema_files = glob.glob(os.path.join(args.dump_dir, '*.sql'))
    
    for schema_file in schema_files:
        schema, table = parse_postgres_schema_path(schema_file)
        
        # Read DDL
        with open(schema_file, 'r') as f:
            postgres_ddl = f.read()
        
        # Convert to ClickHouse DDL
        ch_ddl, columns = convert_to_clickhouse_table(
            postgres_ddl,
            rmt_delete_support=True,
            datetime_timezone=args.datetime_timezone
        )
        
        # Create table in ClickHouse
        conn = get_connection(args, args.clickhouse_user, args.clickhouse_password)
        execute_clickhouse(conn, ch_ddl)
        
        # Load data files
        data_files = glob.glob(os.path.join(args.dump_dir, f'{schema}.{table}.*.csv.gz'))
        for data_file in data_files:
            load_csv_file(conn, schema, table, data_file, columns)
```

#### 2.3.2 CSV Loading with PostgreSQL Format

```python
def load_postgres_csv(conn, schema, table, csv_file, columns):
    """
    Load CSV exported from PostgreSQL COPY command
    
    PostgreSQL COPY format specifics:
    - NULL represented as \N
    - Quotes handled with double quotes ""
    - CSV header included
    """
    # Determine compression
    if csv_file.endswith('.gz'):
        decompress_cmd = 'gzip -dc'
    elif csv_file.endswith('.zst'):
        decompress_cmd = 'zstd -dc'
    else:
        decompress_cmd = 'cat'
    
    # Build ClickHouse client command
    clickhouse_cmd = f"""
    {decompress_cmd} {csv_file} | 
    clickhouse-client 
        --host {conn.host} 
        --port {conn.port} 
        --user {conn.user} 
        --password {conn.password} 
        --database {schema} 
        --query "INSERT INTO {table} ({','.join(columns)}) 
                FORMAT CSVWithNames"
    """
    
    run_command(clickhouse_cmd)
```

---

### 2.4 PostgreSQL Checksum Validation

**Location**: `sink-connector/python/db_compare/postgres_table_checksum.py`

**Purpose**: Verify data integrity between PostgreSQL source and ClickHouse destination.

#### 2.4.1 Implementation

Reference: [`mysql_table_checksum.py`](sink-connector/python/db_compare/mysql_table_checksum.py)

```python
# File: sink-connector/python/db_compare/postgres_table_checksum.py

import psycopg2
import logging
import hashlib
from db.postgres import get_postgres_connection, execute_postgres

def compute_postgres_checksum(table, schema='public', conn=None, 
                              excluded_columns=[], where_clause=None):
    """
    Compute MD5 checksum of PostgreSQL table
    
    Args:
        table: Table name
        schema: Schema name
        conn: PostgreSQL connection
        excluded_columns: Columns to exclude
        where_clause: Optional filter
    
    Returns:
        (checksum_hash, row_count)
    """
    # Get table columns
    column_query = f"""
        SELECT column_name, data_type, udt_name
        FROM information_schema.columns
        WHERE table_schema = '{schema}'
        AND table_name = '{table}'
        ORDER BY ordinal_position;
    """
    
    cursor = conn.cursor()
    cursor.execute(column_query)
    columns = cursor.fetchall()
    
    # Build checksum query
    select_parts = []
    for col_name, data_type, udt_name in columns:
        if col_name in excluded_columns:
            continue
        
        # Handle different data types
        if udt_name in ('uuid',):
            select_parts.append(f"COALESCE({col_name}::TEXT, 'NULL')")
        elif data_type in ('timestamp with time zone', 'timestamp without time zone'):
            select_parts.append(f"COALESCE(TO_CHAR({col_name}, 'YYYY-MM-DD HH24:MI:SS.US'), 'NULL')")
        elif udt_name == 'jsonb':
            select_parts.append(f"COALESCE({col_name}::TEXT, 'NULL')")
        elif data_type == 'ARRAY':
            select_parts.append(f"COALESCE({col_name}::TEXT, 'NULL')")
        elif data_type == 'bytea':
            select_parts.append(f"COALESCE(ENCODE({col_name}, 'hex'), 'NULL')")
        else:
            select_parts.append(f"COALESCE({col_name}::TEXT, 'NULL')")
    
    concat_expr = " || '|' || ".join(select_parts)
    
    where = f"WHERE {where_clause}" if where_clause else ""
    
    query = f"""
        SELECT MD5(STRING_AGG(row_hash, '' ORDER BY row_hash)) as table_checksum,
               COUNT(*) as row_count
        FROM (
            SELECT MD5({concat_expr}) as row_hash
            FROM {schema}.{table}
            {where}
        ) t;
    """
    
    cursor.execute(query)
    result = cursor.fetchone()
    
    return (result[0], result[1])

def main():
    parser = argparse.ArgumentParser(
        description='Compute PostgreSQL table checksum'
    )
    parser.add_argument('--postgres_host', required=True)
    parser.add_argument('--postgres_port', default=5432)
    parser.add_argument('--postgres_user', required=True)
    parser.add_argument('--postgres_password', required=True)
    parser.add_argument('--postgres_database', required=True)
    parser.add_argument('--postgres_schema', default='public')
    parser.add_argument('--tables_regex', default='.*')
    parser.add_argument('--exclude_columns', default='')
    parser.add_argument('--threads', type=int, default=4)
    
    args = parser.parse_args()
    
    # Implementation similar to mysql_table_checksum.py
```

---

## 3. PostgreSQL Database Module

**Location**: `sink-connector/python/db/postgres.py`

```python
# File: sink-connector/python/db/postgres.py

from sqlalchemy import create_engine
import psycopg2
import logging

def get_postgres_connection(host, database, user, password, port=5432):
    """
    Create PostgreSQL connection using SQLAlchemy
    """
    url = f'postgresql+psycopg2://{user}:{password}@{host}:{port}/{database}'
    engine = create_engine(url)
    conn = engine.connect()
    return conn

def execute_postgres(conn, sql):
    """
    Execute PostgreSQL query
    
    Returns:
        (result, rowcount)
    """
    result = conn.execute(sql)
    return (result, result.rowcount)

def is_postgres_binary_datatype(datatype):
    """
    Check if PostgreSQL type is binary
    """
    binary_types = ('bytea',)
    return datatype.lower() in binary_types

def get_postgres_tables(conn, schema='public', regex='.*'):
    """
    Get list of tables matching regex
    """
    query = f"""
        SELECT schemaname, tablename
        FROM pg_tables
        WHERE schemaname = '{schema}'
        AND tablename ~ '{regex}'
        ORDER BY tablename;
    """
    result = conn.execute(query)
    return result.fetchall()
```

---

## 4. Dependencies and Libraries

### 4.1 Python Packages

Add to [`requirements.txt`](sink-connector/python/requirements.txt):

```txt
# PostgreSQL support
psycopg2-binary>=2.9.0
sqlalchemy>=1.4.0

# Existing dependencies
clickhouse-driver>=0.2.0
pandas>=1.3.0
```

### 4.2 System Dependencies

**PostgreSQL Client Tools**:
- `postgresql-client` (includes `pg_dump`, `psql`)
- `libpq-dev` (PostgreSQL C library)

**Installation**:
```bash
# Ubuntu/Debian
sudo apt-get install postgresql-client libpq-dev

# macOS
brew install postgresql
```

---

## 5. Error Handling and Retry Logic

### 5.1 Connection Resilience

```python
def postgres_connection_with_retry(host, database, user, password, 
                                  port=5432, max_retries=3):
    """
    PostgreSQL connection with exponential backoff retry
    """
    import time
    
    for attempt in range(max_retries):
        try:
            conn = get_postgres_connection(host, database, user, password, port)
            logging.info("PostgreSQL connection established")
            return conn
        except Exception as e:
            if attempt < max_retries - 1:
                wait_time = 2 ** attempt
                logging.warning(f"Connection failed, retrying in {wait_time}s: {e}")
                time.sleep(wait_time)
            else:
                logging.error(f"Failed to connect after {max_retries} attempts")
                raise
```

### 5.2 Dump Failure Handling

```python
def dump_table_with_retry(conn, table, output_dir, max_retries=3):
    """
    Dump table with retry on transient failures
    """
    for attempt in range(max_retries):
        try:
            dump_table_data(conn, table, output_dir)
            return True
        except Exception as e:
            logging.error(f"Dump failed for {table}: {e}")
            if attempt < max_retries - 1:
                logging.info(f"Retrying dump for {table}")
            else:
                logging.error(f"Failed to dump {table} after {max_retries} attempts")
                return False
```

---

## 6. Performance Considerations

### 6.1 Parallel Dumping

Use PostgreSQL parallel export capabilities:

```python
def parallel_dump_table(conn, table, output_dir, threads=4, chunk_size=1000000):
    """
    Parallel dump using WHERE clause chunking
    
    Strategy:
    1. Get primary key range
    2. Divide into chunks
    3. Dump each chunk in parallel
    """
    # Get total rows
    row_count = get_table_row_count(conn, table)
    
    # Calculate chunks
    num_chunks = (row_count // chunk_size) + 1
    
    # Use ThreadPoolExecutor for parallel export
    with ThreadPoolExecutor(max_workers=threads) as executor:
        futures = []
        for i in range(num_chunks):
            offset = i * chunk_size
            where = f"LIMIT {chunk_size} OFFSET {offset}"
            future = executor.submit(dump_table_chunk, conn, table, output_dir, i, where)
            futures.append(future)
        
        # Wait for completion
        for future in futures:
            future.result()
```

### 6.2 Compression

Use `zstd` for better compression ratio and speed:

```python
def compress_dump_file(input_file, output_file, compression='zstd'):
    """
    Compress dump file
    """
    if compression == 'zstd':
        cmd = f"zstd -T0 --rm {input_file} -o {output_file}"
    elif compression == 'gzip':
        cmd = f"gzip {input_file} -c > {output_file}"
    
    run_command(cmd)
```

---

## 7. Testing Strategy Integration

See [`02-testing-strategy.md`](plans/postgres/02-testing-strategy.md) for detailed testing approach.

**Unit Tests**:
- Type conversion functions
- DDL parsing
- Column metadata extraction

**Integration Tests**:
- End-to-end dump and load
- Checksum validation
- Large dataset handling

---

## 8. Docker Integration

### 8.1 Dockerfile for PostgreSQL Tools

```dockerfile
# File: sink-connector/python/Dockerfile_postgres_dump

FROM python:3.10-slim

# Install PostgreSQL client tools
RUN apt-get update && apt-get install -y \
    postgresql-client \
    libpq-dev \
    zstd \
    gzip \
    && rm -rf /var/lib/apt/lists/*

# Copy Python code
COPY . /app
WORKDIR /app

# Install Python dependencies
RUN pip install -r requirements.txt

ENTRYPOINT ["python", "db_dump/postgres_dumper.py"]
```

### 8.2 Docker Compose Integration

Reference: [`docker-compose-postgres.yml`](sink-connector-lightweight/docker/docker-compose-postgres.yml)

```yaml
# Example docker-compose snippet
services:
  postgres-dumper:
    build:
      context: ./sink-connector/python
      dockerfile: Dockerfile_postgres_dump
    environment:
      - POSTGRES_HOST=postgres
      - POSTGRES_PORT=5432
      - POSTGRES_USER=root
      - POSTGRES_PASSWORD=root
    volumes:
      - ./dumps:/dumps
    depends_on:
      - postgres
```

---

## 9. Migration from MySQL Tools

### 9.1 Code Reuse

Maximum code reuse from MySQL implementation:

| Component | Reuse % | Notes |
|-----------|---------|-------|
| [`clickhouse_loader.py`](sink-connector/python/db_load/clickhouse_loader.py) | 80% | Add PostgreSQL-specific parsing |
| CSV loading logic | 90% | PostgreSQL COPY format similar |
| Checksum framework | 85% | Adapt SQL for PostgreSQL |
| CLI argument parsing | 95% | Similar interface |
| Threading logic | 100% | Identical pattern |

### 9.2 Shared Utilities

Extract common utilities to shared module:

```python
# File: sink-connector/python/db/common.py

def get_table_row_count(conn, table, where=None):
    """Database-agnostic row count"""
    pass

def parallel_execute(tasks, threads):
    """Generic parallel execution"""
    pass
```

---

## 10. Documentation Requirements

### 10.1 User Documentation

Create: `sink-connector/python/docs/PostgreSQL_Dump_Guide.md`

**Contents**:
- Quick start guide
- Command-line examples
- Troubleshooting
- Performance tuning

### 10.2 Code Documentation

- Docstrings for all functions
- Type hints for parameters
- Example usage in comments

---

## 11. Success Criteria

**Functional Requirements**:
- ✅ Dump PostgreSQL schema and data
- ✅ Convert PostgreSQL DDL to ClickHouse DDL
- ✅ Load data into ClickHouse with correct types
- ✅ Validate data integrity with checksums
- ✅ Handle all PostgreSQL data types listed in mapping table
- ✅ Support parallel dumping and loading

**Performance Requirements**:
- Dump rate: > 100K rows/second
- Load rate: > 500K rows/second
- Checksum computation: < 1 minute for 10M rows

**Quality Requirements**:
- 100% data type conversion coverage
- Checksum match rate: 100% for supported types
- Error handling for all failure modes
- Comprehensive logging

---

## 12. Implementation Checklist

- [ ] Create `sink-connector/python/db/postgres.py`
- [ ] Create `sink-connector/python/db_dump/postgres_dumper.py`
- [ ] Create `sink-connector/python/db_load/postgres_parser/postgres_parser.py`
- [ ] Enhance `sink-connector/python/db_load/clickhouse_loader.py`
- [ ] Create `sink-connector/python/db_compare/postgres_table_checksum.py`
- [ ] Create `sink-connector/python/db_compare/postgres_table_count.py`
- [ ] Update `sink-connector/python/requirements.txt`
- [ ] Create `Dockerfile_postgres_dump`
- [ ] Create `Dockerfile_postgres_checksum`
- [ ] Write unit tests
- [ ] Write integration tests
- [ ] Update README.md
- [ ] Create user documentation

---

## 13. Advanced Implementation Details

### 13.1 PostgreSQL-Specific Type Handling Edge Cases

#### 13.1.1 TIMESTAMPTZ Timezone Conversion

**Challenge**: PostgreSQL stores `TIMESTAMP WITH TIME ZONE` in UTC but displays in session timezone. ClickHouse requires explicit timezone specification.

**Solution**:
```python
def handle_timestamptz_conversion(pg_value, target_timezone='UTC'):
    """
    Convert PostgreSQL TIMESTAMPTZ to ClickHouse DateTime64 with timezone
    
    Args:
        pg_value: PostgreSQL timestamp value (datetime object)
        target_timezone: Target timezone for ClickHouse (default: UTC)
    
    Returns:
        String formatted for ClickHouse DateTime64
    
    Example:
        Input:  2024-01-15 10:30:00+05:30 (IST)
        Output: 2024-01-15 05:00:00 (UTC)
    """
    from datetime import timezone
    
    if pg_value is None:
        return None
    
    # PostgreSQL returns timezone-aware datetime
    if pg_value.tzinfo is not None:
        # Convert to UTC
        utc_value = pg_value.astimezone(timezone.utc)
        return utc_value.strftime('%Y-%m-%d %H:%M:%S.%f')
    else:
        # No timezone info, assume UTC
        return pg_value.strftime('%Y-%m-%d %H:%M:%S.%f')
```

**DDL Conversion**:
```python
# PostgreSQL DDL
CREATE TABLE events (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

# ClickHouse DDL
CREATE TABLE events (
    id UInt32,
    created_at DateTime64(6, 'UTC') DEFAULT now64(6),
    updated_at Nullable(DateTime64(6, 'UTC')),
    _sign Int8 DEFAULT 1,
    _version UInt64
) ENGINE = ReplacingMergeTree(_version)
ORDER BY id;
```

#### 13.1.2 Array Type Conversion

**Challenge**: PostgreSQL arrays can be multi-dimensional and contain NULLs.

**Solution**:
```python
def convert_postgres_array_to_clickhouse(pg_array_str, base_type):
    """
    Convert PostgreSQL array string to ClickHouse array format
    
    Args:
        pg_array_str: PostgreSQL array string (e.g., '{1,2,NULL,4}')
        base_type: Base element type
    
    Returns:
        ClickHouse array format
    
    Examples:
        '{1,2,3}'          -> [1,2,3]
        '{a,b,c}'          -> ['a','b','c']
        '{{1,2},{3,4}}'    -> [[1,2],[3,4]]  # 2D array
        '{1,NULL,3}'       -> [1,NULL,3]     # With NULLs
    """
    import ast
    
    if pg_array_str is None or pg_array_str == '{}':
        return []
    
    # Replace PostgreSQL array syntax with Python list syntax
    cleaned = pg_array_str.replace('{', '[').replace('}', ']')
    
    # Handle NULL values
    cleaned = cleaned.replace('NULL', 'None')
    
    # Handle string elements (quoted)
    # PostgreSQL: {"a","b","c"} -> ClickHouse: ['a','b','c']
    
    try:
        return ast.literal_eval(cleaned)
    except:
        # Fallback: return as-is if parsing fails
        return pg_array_str
```

**Type Mapping**:
```python
# PostgreSQL → ClickHouse array mappings
POSTGRES_ARRAY_MAPPINGS = {
    'integer[]': 'Array(Int32)',
    'bigint[]': 'Array(Int64)',
    'text[]': 'Array(String)',
    'varchar[]': 'Array(String)',
    'boolean[]': 'Array(UInt8)',
    'uuid[]': 'Array(UUID)',
    'timestamp[]': 'Array(DateTime64(6))',
    'numeric[]': 'Array(Decimal(38,9))',
    'jsonb[]': 'Array(String)',  # Each element is JSON string
}
```

#### 13.1.3 JSONB Handling

**Challenge**: PostgreSQL JSONB is binary format, needs proper serialization.

**Solution**:
```python
def serialize_jsonb_for_clickhouse(jsonb_value):
    """
    Serialize PostgreSQL JSONB to ClickHouse String
    
    Args:
        jsonb_value: PostgreSQL JSONB value (dict or psycopg2 Json)
    
    Returns:
        JSON string for ClickHouse
    
    Example:
        Input:  {"name": "Alice", "age": 30, "tags": ["developer", "remote"]}
        Output: '{"name":"Alice","age":30,"tags":["developer","remote"]}'
    """
    import json
    from psycopg2.extras import Json
    
    if jsonb_value is None:
        return None
    
    if isinstance(jsonb_value, (dict, list)):
        return json.dumps(jsonb_value, ensure_ascii=False, separators=(',', ':'))
    elif isinstance(jsonb_value, Json):
        return json.dumps(jsonb_value.adapted, ensure_ascii=False, separators=(',', ':'))
    else:
        # Already string
        return str(jsonb_value)
```

**CSV Export Handling**:
```python
def generate_copy_command_with_jsonb(table, columns, output_file):
    """
    Generate COPY command that properly handles JSONB
    """
    # Convert JSONB to JSON string in COPY command
    select_parts = []
    for col_name, col_type in columns:
        if col_type.lower() == 'jsonb':
            select_parts.append(f"{col_name}::TEXT")
        else:
            select_parts.append(col_name)
    
    select_clause = ', '.join(select_parts)
    
    copy_sql = f"""
        COPY (SELECT {select_clause} FROM {table})
        TO STDOUT WITH (FORMAT CSV, HEADER, DELIMITER ',',
                       QUOTE '"', ESCAPE '"', NULL '\\N')
    """
    return copy_sql
```

#### 13.1.4 UUID Handling

**Challenge**: PostgreSQL UUID is 128-bit, ClickHouse UUID has specific format requirements.

**Solution**:
```python
def validate_uuid_format(uuid_str):
    """
    Validate and normalize UUID format for ClickHouse
    
    Args:
        uuid_str: UUID string from PostgreSQL
    
    Returns:
        Normalized UUID string (lowercase, with hyphens)
    
    Example:
        Input:  '550e8400-e29b-41d4-a716-446655440000'
        Input:  '550E8400E29B41D4A716446655440000' (no hyphens, uppercase)
        Output: '550e8400-e29b-41d4-a716-446655440000'
    """
    import uuid
    
    if uuid_str is None:
        return None
    
    try:
        # Parse and reformat
        parsed = uuid.UUID(uuid_str)
        return str(parsed).lower()
    except ValueError:
        raise ValueError(f"Invalid UUID format: {uuid_str}")
```

#### 13.1.5 Enum and Custom Types

**Challenge**: PostgreSQL custom ENUMs don't exist in ClickHouse.

**Solution**:
```python
def get_enum_values(conn, enum_type_name):
    """
    Query PostgreSQL for ENUM type values
    
    Args:
        conn: PostgreSQL connection
        enum_type_name: Name of ENUM type
    
    Returns:
        List of ENUM values
    
    Example:
        CREATE TYPE status_enum AS ENUM ('pending', 'active', 'inactive');
        → ['pending', 'active', 'inactive']
    """
    query = f"""
        SELECT enumlabel
        FROM pg_enum
        WHERE enumtypid = '{enum_type_name}'::regtype
        ORDER BY enumsortorder;
    """
    cursor = conn.cursor()
    cursor.execute(query)
    return [row[0] for row in cursor.fetchall()]

def convert_enum_to_clickhouse(enum_type_name, enum_values):
    """
    Convert PostgreSQL ENUM to ClickHouse Enum
    
    Args:
        enum_type_name: ENUM type name
        enum_values: List of ENUM values
    
    Returns:
        ClickHouse Enum type definition
    
    Example:
        Input:  ['pending', 'active', 'inactive']
        Output: Enum8('pending' = 1, 'active' = 2, 'inactive' = 3)
    """
    enum_pairs = [f"'{val}' = {i+1}" for i, val in enumerate(enum_values)]
    
    if len(enum_values) <= 256:
        return f"Enum8({', '.join(enum_pairs)})"
    else:
        return f"Enum16({', '.join(enum_pairs)})"
```

---

### 13.2 Large Dataset Optimization Strategies

#### 13.2.1 Chunked Export with Primary Key Ranges

**Challenge**: Dumping 100M+ row tables without memory overflow.

**Solution**:
```python
def dump_table_by_pk_ranges(conn, table, pk_column, output_dir,
                            chunk_size=1000000, threads=8):
    """
    Dump large table by dividing into PK ranges
    
    Strategy:
    1. Get MIN/MAX of primary key
    2. Divide into equal ranges
    3. Dump each range in parallel
    
    Args:
        conn: PostgreSQL connection
        table: Table name
        pk_column: Primary key column name
        output_dir: Output directory
        chunk_size: Rows per chunk
        threads: Parallel threads
    
    Example:
        Table: 50M rows, PK: id (1 to 50000000)
        chunk_size: 1M
        → 50 chunks: [1-1M], [1M+1-2M], ..., [49M+1-50M]
    """
    from concurrent.futures import ThreadPoolExecutor, as_completed
    
    # Get PK range
    query = f"SELECT MIN({pk_column}), MAX({pk_column}) FROM {table}"
    cursor = conn.cursor()
    cursor.execute(query)
    min_pk, max_pk = cursor.fetchone()
    
    if min_pk is None or max_pk is None:
        logging.warning(f"Table {table} is empty")
        return
    
    # Calculate ranges
    total_range = max_pk - min_pk + 1
    num_chunks = (total_range // chunk_size) + 1
    
    logging.info(f"Dumping {table}: PK range [{min_pk}, {max_pk}], {num_chunks} chunks")
    
    # Parallel dump
    with ThreadPoolExecutor(max_workers=threads) as executor:
        futures = []
        
        for i in range(num_chunks):
            range_start = min_pk + (i * chunk_size)
            range_end = min(range_start + chunk_size - 1, max_pk)
            
            where_clause = f"{pk_column} >= {range_start} AND {pk_column} <= {range_end}"
            output_file = f"{output_dir}/{table}.chunk_{i:06d}.csv"
            
            future = executor.submit(
                dump_table_chunk,
                conn, table, output_file, where_clause
            )
            futures.append((i, future))
        
        # Track progress
        for i, future in as_completed([f[1] for f in futures]):
            try:
                result = future.result()
                logging.info(f"Chunk {i}/{num_chunks} completed")
            except Exception as e:
                logging.error(f"Chunk {i} failed: {e}")

def dump_table_chunk(conn, table, output_file, where_clause):
    """
    Dump single table chunk using COPY
    """
    copy_sql = f"""
        COPY (SELECT * FROM {table} WHERE {where_clause})
        TO STDOUT WITH (FORMAT CSV, HEADER, DELIMITER ',',
                       QUOTE '"', ESCAPE '"', NULL '\\N')
    """
    
    with open(output_file, 'w') as f:
        cursor = conn.cursor()
        cursor.copy_expert(copy_sql, f)
    
    # Compress immediately
    os.system(f"gzip {output_file}")
    
    return output_file + '.gz'
```

#### 13.2.2 Streaming Export (Low Memory)

**Challenge**: Export without loading entire dataset into memory.

**Solution**:
```python
def streaming_export_to_csv(conn, table, output_file, batch_size=10000):
    """
    Stream table data to CSV with minimal memory usage
    
    Uses server-side cursor (named cursor in psycopg2)
    
    Args:
        conn: PostgreSQL connection
        table: Table name
        output_file: Output CSV file
        batch_size: Rows per batch
    
    Memory Usage: ~batch_size rows in memory at once
    """
    import csv
    
    # Use named cursor for server-side cursor
    cursor = conn.cursor(name='fetch_large_table')
    cursor.itersize = batch_size  # Fetch batch_size rows at a time
    
    query = f"SELECT * FROM {table}"
    cursor.execute(query)
    
    # Get column names
    column_names = [desc[0] for desc in cursor.description]
    
    with open(output_file, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile, quoting=csv.QUOTE_MINIMAL)
        
        # Write header
        writer.writerow(column_names)
        
        # Stream rows
        row_count = 0
        while True:
            rows = cursor.fetchmany(batch_size)
            if not rows:
                break
            
            for row in rows:
                writer.writerow(row)
                row_count += 1
            
            if row_count % 100000 == 0:
                logging.info(f"Exported {row_count} rows...")
    
    cursor.close()
    logging.info(f"Total rows exported: {row_count}")
```

#### 13.2.3 Parallel Table Dumping

**Challenge**: Dump 100+ tables efficiently.

**Solution**:
```python
def dump_all_tables_parallel(conn, database, schema, output_dir,
                             threads=8, exclude_tables=[]):
    """
    Dump all tables in schema using parallel workers
    
    Strategy:
    1. Get list of all tables with row counts
    2. Sort by row count (largest first)
    3. Distribute to thread pool
    4. Monitor progress
    
    Args:
        conn: PostgreSQL connection
        database: Database name
        schema: Schema name
        output_dir: Output directory
        threads: Number of parallel workers
        exclude_tables: Tables to skip
    """
    from concurrent.futures import ThreadPoolExecutor, as_completed
    import time
    
    # Get tables with row counts
    query = f"""
        SELECT schemaname, tablename, n_live_tup as row_count
        FROM pg_stat_user_tables
        WHERE schemaname = '{schema}'
        ORDER BY n_live_tup DESC;
    """
    cursor = conn.cursor()
    cursor.execute(query)
    tables = cursor.fetchall()
    
    # Filter excluded tables
    tables = [(s, t, r) for s, t, r in tables if t not in exclude_tables]
    
    total_tables = len(tables)
    total_rows = sum(r for _, _, r in tables)
    
    logging.info(f"Dumping {total_tables} tables, ~{total_rows:,} total rows")
    
    start_time = time.time()
    completed_tables = 0
    
    with ThreadPoolExecutor(max_workers=threads) as executor:
        # Submit all tasks
        futures = {}
        for schema_name, table_name, row_count in tables:
            future = executor.submit(
                dump_single_table_full,
                conn, database, schema_name, table_name, output_dir
            )
            futures[future] = (table_name, row_count)
        
        # Process completions
        for future in as_completed(futures):
            table_name, row_count = futures[future]
            completed_tables += 1
            
            try:
                result = future.result()
                elapsed = time.time() - start_time
                remaining = total_tables - completed_tables
                eta = (elapsed / completed_tables) * remaining if completed_tables > 0 else 0
                
                logging.info(
                    f"[{completed_tables}/{total_tables}] "
                    f"Completed: {table_name} ({row_count:,} rows) "
                    f"| ETA: {eta/60:.1f} min"
                )
            except Exception as e:
                logging.error(f"Failed to dump {table_name}: {e}")
    
    total_time = time.time() - start_time
    logging.info(f"Dump completed in {total_time/60:.1f} minutes")
```

---

### 13.3 Data Validation and Quality Checks

#### 13.3.1 Pre-Dump Validation

**Validate before starting dump**:

```python
def validate_before_dump(conn, table, schema='public'):
    """
    Perform validation checks before dump
    
    Checks:
    1. Table exists
    2. Table not empty
    3. Table not locked
    4. Sufficient disk space
    5. No corrupt indexes
    
    Returns:
        (is_valid, error_messages)
    """
    errors = []
    
    # Check 1: Table exists
    check_query = f"""
        SELECT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = '{schema}'
            AND table_name = '{table}'
        );
    """
    cursor = conn.cursor()
    cursor.execute(check_query)
    if not cursor.fetchone()[0]:
        errors.append(f"Table {schema}.{table} does not exist")
        return (False, errors)
    
    # Check 2: Row count
    count_query = f"SELECT COUNT(*) FROM {schema}.{table}"
    cursor.execute(count_query)
    row_count = cursor.fetchone()[0]
    if row_count == 0:
        logging.warning(f"Table {table} is empty (0 rows)")
    
    # Check 3: Check for locks
    lock_query = f"""
        SELECT COUNT(*) FROM pg_locks
        WHERE relation = '{schema}.{table}'::regclass
        AND mode = 'AccessExclusiveLock';
    """
    cursor.execute(lock_query)
    lock_count = cursor.fetchone()[0]
    if lock_count > 0:
        errors.append(f"Table {table} has {lock_count} exclusive locks")
    
    # Check 4: Disk space (estimate)
    size_query = f"SELECT pg_total_relation_size('{schema}.{table}')"
    cursor.execute(size_query)
    table_size = cursor.fetchone()[0]
    
    # Check available disk space
    import shutil
    stat = shutil.disk_usage('/')
    available_space = stat.free
    
    if table_size * 2 > available_space:  # Need 2x for dump + compression
        errors.append(
            f"Insufficient disk space: need {table_size*2/1e9:.1f}GB, "
            f"have {available_space/1e9:.1f}GB"
        )
    
    # Check 5: Index corruption
    index_query = f"""
        SELECT indexname, pg_relation_size(indexrelid) as size
        FROM pg_stat_user_indexes
        WHERE schemaname = '{schema}' AND tablename = '{table}';
    """
    cursor.execute(index_query)
    indexes = cursor.fetchall()
    
    logging.info(f"Table {table}: {row_count:,} rows, {len(indexes)} indexes, "
                f"size: {table_size/1e6:.1f}MB")
    
    return (len(errors) == 0, errors)
```

#### 13.3.2 Post-Dump Validation

**Validate dump files**:

```python
def validate_dump_files(output_dir, table, expected_row_count=None):
    """
    Validate dump files after creation
    
    Checks:
    1. Files exist
    2. Files not empty
    3. CSV headers present
    4. Row count matches (if provided)
    5. No corruption (can open and read)
    
    Returns:
        (is_valid, validation_report)
    """
    import glob
    import gzip
    import csv
    
    validation_report = {
        'files': [],
        'total_rows': 0,
        'errors': []
    }
    
    # Find all dump files for table
    dump_files = glob.glob(f"{output_dir}/{table}*.csv.gz")
    
    if not dump_files:
        validation_report['errors'].append(f"No dump files found for {table}")
        return (False, validation_report)
    
    for dump_file in dump_files:
        file_info = {
            'path': dump_file,
            'size': os.path.getsize(dump_file),
            'rows': 0,
            'valid': True,
            'error': None
        }
        
        try:
            # Open and count rows
            with gzip.open(dump_file, 'rt') as f:
                reader = csv.reader(f)
                
                # Check header
                header = next(reader, None)
                if header is None:
                    file_info['valid'] = False
                    file_info['error'] = "No header row"
                else:
                    # Count data rows
                    row_count = sum(1 for _ in reader)
                    file_info['rows'] = row_count
                    validation_report['total_rows'] += row_count
        
        except Exception as e:
            file_info['valid'] = False
            file_info['error'] = str(e)
            validation_report['errors'].append(f"{dump_file}: {e}")
        
        validation_report['files'].append(file_info)
    
    # Check expected row count
    if expected_row_count is not None:
        if validation_report['total_rows'] != expected_row_count:
            validation_report['errors'].append(
                f"Row count mismatch: expected {expected_row_count}, "
                f"got {validation_report['total_rows']}"
            )
    
    is_valid = len(validation_report['errors']) == 0
    
    logging.info(
        f"Dump validation for {table}: {len(dump_files)} files, "
        f"{validation_report['total_rows']:,} rows, "
        f"valid: {is_valid}"
    )
    
    return (is_valid, validation_report)
```

---

### 13.4 Error Recovery and Resumption

#### 13.4.1 Checkpoint-Based Resumption

**Resume interrupted dumps**:

```python
def dump_with_checkpoints(conn, tables, output_dir, checkpoint_file=None):
    """
    Dump with checkpoint support for resumption
    
    Strategy:
    1. Write checkpoint after each table completes
    2. On restart, read checkpoint and skip completed tables
    3. Resume from last incomplete table
    
    Args:
        conn: PostgreSQL connection
        tables: List of tables to dump
        output_dir: Output directory
        checkpoint_file: Checkpoint state file
    
    Checkpoint Format (JSON):
    {
        "completed_tables": ["table1", "table2"],
        "in_progress_table": "table3",
        "last_chunk": 5,
        "timestamp": "2024-01-15T10:30:00"
    }
    """
    import json
    from datetime import datetime
    
    checkpoint_path = checkpoint_file or f"{output_dir}/dump_checkpoint.json"
    
    # Load checkpoint if exists
    completed_tables = set()
    in_progress_table = None
    last_chunk = 0
    
    if os.path.exists(checkpoint_path):
        with open(checkpoint_path, 'r') as f:
            checkpoint = json.load(f)
            completed_tables = set(checkpoint.get('completed_tables', []))
            in_progress_table = checkpoint.get('in_progress_table')
            last_chunk = checkpoint.get('last_chunk', 0)
        
        logging.info(f"Resuming dump: {len(completed_tables)} tables completed")
    
    # Dump tables
    for table in tables:
        if table in completed_tables:
            logging.info(f"Skipping {table} (already completed)")
            continue
        
        try:
            logging.info(f"Dumping {table}...")
            
            # Update checkpoint: in progress
            with open(checkpoint_path, 'w') as f:
                json.dump({
                    'completed_tables': list(completed_tables),
                    'in_progress_table': table,
                    'last_chunk': 0,
                    'timestamp': datetime.now().isoformat()
                }, f, indent=2)
            
            # Perform dump
            dump_single_table_full(conn, None, 'public', table, output_dir)
            
            # Update checkpoint: completed
            completed_tables.add(table)
            with open(checkpoint_path, 'w') as f:
                json.dump({
                    'completed_tables': list(completed_tables),
                    'in_progress_table': None,
                    'last_chunk': 0,
                    'timestamp': datetime.now().isoformat()
                }, f, indent=2)
            
            logging.info(f"Completed {table}")
        
        except Exception as e:
            logging.error(f"Failed to dump {table}: {e}")
            # Checkpoint saved with in_progress_table set
            raise
    
    # Cleanup checkpoint on full completion
    os.remove(checkpoint_path)
    logging.info("Dump completed successfully, checkpoint removed")
```

#### 13.4.2 Automatic Retry with Exponential Backoff

```python
def dump_with_retry(conn, table, output_dir, max_retries=3):
    """
    Dump table with automatic retry on failure
    
    Retry strategy:
    - Attempt 1: Immediate
    - Attempt 2: Wait 5 seconds
    - Attempt 3: Wait 25 seconds (5^2)
    - Attempt 4: Wait 125 seconds (5^3)
    
    Args:
        conn: PostgreSQL connection
        table: Table name
        output_dir: Output directory
        max_retries: Maximum retry attempts
    
    Returns:
        Success boolean
    """
    import time
    
    for attempt in range(max_retries):
        try:
            dump_single_table_full(conn, None, 'public', table, output_dir)
            logging.info(f"Successfully dumped {table} on attempt {attempt + 1}")
            return True
        
        except Exception as e:
            logging.error(f"Dump attempt {attempt + 1} failed for {table}: {e}")
            
            if attempt < max_retries - 1:
                wait_time = 5 ** attempt  # Exponential backoff
                logging.info(f"Retrying in {wait_time} seconds...")
                time.sleep(wait_time)
                
                # Reconnect (in case connection was lost)
                try:
                    conn = reconnect_postgres(conn)
                except:
                    logging.error("Failed to reconnect to PostgreSQL")
            else:
                logging.error(f"Failed to dump {table} after {max_retries} attempts")
                return False
    
    return False
```

---

### 13.5 Performance Benchmarks and Tuning

#### 13.5.1 Performance Metrics Collection

```python
def dump_with_metrics(conn, table, output_dir):
    """
    Dump table and collect performance metrics
    
    Metrics collected:
    - Dump duration
    - Rows per second
    - MB per second
    - Memory usage
    - CPU usage
    
    Returns:
        (success, metrics_dict)
    """
    import time
    import psutil
    import os
    
    metrics = {
        'table': table,
        'start_time': time.time(),
        'end_time': None,
        'duration_seconds': None,
        'rows_dumped': 0,
        'bytes_written': 0,
        'rows_per_second': 0,
        'mb_per_second': 0,
        'peak_memory_mb': 0,
        'avg_cpu_percent': 0
    }
    
    # Get initial row count
    cursor = conn.cursor()
    cursor.execute(f"SELECT COUNT(*) FROM {table}")
    total_rows = cursor.fetchone()[0]
    
    # Monitor memory and CPU
    process = psutil.Process(os.getpid())
    initial_memory = process.memory_info().rss / 1024 / 1024  # MB
    
    cpu_samples = []
    memory_samples = []
    
    # Start monitoring thread
    import threading
    stop_monitoring = threading.Event()
    
    def monitor_resources():
        while not stop_monitoring.is_set():
            cpu_samples.append(process.cpu_percent(interval=0.1))
            memory_samples.append(process.memory_info().rss / 1024 / 1024)
            time.sleep(1)
    
    monitor_thread = threading.Thread(target=monitor_resources)
    monitor_thread.start()
    
    try:
        # Perform dump
        dump_single_table_full(conn, None, 'public', table, output_dir)
        
        # Stop monitoring
        stop_monitoring.set()
        monitor_thread.join()
        
        # Collect metrics
        metrics['end_time'] = time.time()
        metrics['duration_seconds'] = metrics['end_time'] - metrics['start_time']
        metrics['rows_dumped'] = total_rows
        
        # Get file size
        dump_files = glob.glob(f"{output_dir}/{table}*.csv.gz")
        metrics['bytes_written'] = sum(os.path.getsize(f) for f in dump_files)
        
        # Calculate rates
        if metrics['duration_seconds'] > 0:
            metrics['rows_per_second'] = total_rows / metrics['duration_seconds']
            metrics['mb_per_second'] = (metrics['bytes_written'] / 1024 / 1024) / metrics['duration_seconds']
        
        # Peak memory
        metrics['peak_memory_mb'] = max(memory_samples) if memory_samples else initial_memory
        metrics['avg_cpu_percent'] = sum(cpu_samples) / len(cpu_samples) if cpu_samples else 0
        
        logging.info(
            f"Dump metrics for {table}: "
            f"{metrics['rows_per_second']:.0f} rows/s, "
            f"{metrics['mb_per_second']:.1f} MB/s, "
            f"peak memory: {metrics['peak_memory_mb']:.0f} MB, "
            f"avg CPU: {metrics['avg_cpu_percent']:.1f}%"
        )
        
        return (True, metrics)
    
    except Exception as e:
        stop_monitoring.set()
        monitor_thread.join()
        logging.error(f"Dump failed: {e}")
        return (False, metrics)
```

#### 13.5.2 PostgreSQL Tuning for Bulk Export

**Optimize PostgreSQL settings for dump performance**:

```sql
-- Temporary session settings for faster dumps
-- Execute before starting dump

-- Increase work memory for sorting
SET work_mem = '256MB';

-- Increase shared buffers cache hit rate
SET shared_buffers = '4GB';

-- Disable query planner randomization
SET random_page_cost = 1.1;

-- Increase max parallel workers
SET max_parallel_workers_per_gather = 4;

-- Reduce checkpoint frequency during dump
SET checkpoint_timeout = '30min';

-- Increase WAL buffers
SET wal_buffers = '16MB';

-- Disable autovacuum during dump (be careful!)
ALTER TABLE mytable SET (autovacuum_enabled = false);

-- After dump, re-enable
ALTER TABLE mytable SET (autovacuum_enabled = true);
```

**Python wrapper**:

```python
def optimize_postgres_for_dump(conn):
    """
    Apply PostgreSQL optimizations for dump performance
    
    WARNING: These are session-level settings and don't affect
    other connections. Some settings require superuser.
    """
    optimizations = [
        "SET work_mem = '256MB'",
        "SET maintenance_work_mem = '1GB'",
        "SET random_page_cost = 1.1",
        "SET effective_cache_size = '8GB'",
    ]
    
    cursor = conn.cursor()
    for sql in optimizations:
        try:
            cursor.execute(sql)
            logging.info(f"Applied: {sql}")
        except Exception as e:
            logging.warning(f"Could not apply {sql}: {e}")
```

---

### 13.6 Integration with Existing MySQL Tools

#### 13.6.1 Unified CLI Interface

**Create unified interface that supports both MySQL and PostgreSQL**:

```python
# File: sink-connector/python/db_dump/universal_dumper.py

def main():
    parser = argparse.ArgumentParser(
        description='Universal database dumper (MySQL/PostgreSQL → ClickHouse)'
    )
    
    # Database type selection
    parser.add_argument('--source_type', required=True,
                       choices=['mysql', 'postgres'],
                       help='Source database type')
    
    # Common arguments
    parser.add_argument('--host', required=True)
    parser.add_argument('--port', type=int)
    parser.add_argument('--user', required=True)
    parser.add_argument('--password', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--dump_dir', required=True)
    parser.add_argument('--tables_regex', default='.*')
    parser.add_argument('--threads', type=int, default=4)
    
    # PostgreSQL-specific
    parser.add_argument('--postgres_schema', default='public',
                       help='PostgreSQL schema (default: public)')
    
    args = parser.parse_args()
    
    # Delegate to appropriate dumper
    if args.source_type == 'mysql':
        from db_dump.mysql_dumper import dump_mysql_database
        dump_mysql_database(args)
    elif args.source_type == 'postgres':
        from db_dump.postgres_dumper import dump_postgres_database
        dump_postgres_database(args)

if __name__ == '__main__':
    main()
```

**Usage**:
```bash
# PostgreSQL dump
python db_dump/universal_dumper.py \
  --source_type postgres \
  --host localhost \
  --port 5432 \
  --user postgres \
  --password secret \
  --database mydb \
  --postgres_schema public \
  --dump_dir /tmp/dumps \
  --threads 8

# MySQL dump (same interface)
python db_dump/universal_dumper.py \
  --source_type mysql \
  --host localhost \
  --port 3306 \
  --user root \
  --password secret \
  --database mydb \
  --dump_dir /tmp/dumps \
  --threads 8
```

---

## 14. Next Steps

1. Review this plan with stakeholders
2. Proceed to [`02-testing-strategy.md`](plans/postgres/02-testing-strategy.md)
3. Review [`03-implementation-phases.md`](plans/postgres/03-implementation-phases.md)
4. Review [`08-mysql-postgres-isolation-strategy.md`](plans/postgres/08-mysql-postgres-isolation-strategy.md) for isolation requirements
5. Review [`09-detailed-test-specifications.md`](plans/postgres/09-detailed-test-specifications.md) for comprehensive testing
6. Begin Phase 1 implementation

---

## Appendix A: File Structure

```
sink-connector/python/
├── db/
│   ├── postgres.py                          # NEW
│   ├── mysql.py
│   └── clickhouse.py
├── db_dump/
│   ├── postgres_dumper.py                   # NEW
│   └── mysql_dumper.py
├── db_load/
│   ├── clickhouse_loader.py                 # ENHANCED
│   ├── postgres_parser/                     # NEW
│   │   ├── __init__.py
│   │   └── postgres_parser.py
│   └── mysql_parser/
├── db_compare/
│   ├── postgres_table_checksum.py           # NEW
│   ├── postgres_table_count.py              # NEW
│   ├── mysql_table_checksum.py
│   └── clickhouse_table_checksum.py
├── Dockerfile_postgres_dump                 # NEW
├── Dockerfile_postgres_checksum             # NEW
└── requirements.txt                         # UPDATED
```

---

## Appendix B: Example Usage

```bash
# 1. Dump PostgreSQL database
python db_dump/postgres_dumper.py \
  --postgres_host localhost \
  --postgres_port 5432 \
  --postgres_user root \
  --postgres_password root \
  --postgres_database mydb \
  --postgres_schema public \
  --dump_dir /tmp/pg_dumps \
  --tables_regex '.*' \
  --threads 4

# 2. Load into ClickHouse
python db_load/clickhouse_loader.py \
  --clickhouse_host localhost \
  --clickhouse_port 8123 \
  --clickhouse_user default \
  --clickhouse_password '' \
  --clickhouse_database mydb \
  --dump_dir /tmp/pg_dumps \
  --postgres_source_database mydb \
  --threads 8

# 3. Verify with checksums
python db_compare/postgres_table_checksum.py \
  --postgres_host localhost \
  --postgres_database mydb \
  --tables_regex '.*'

python db_compare/clickhouse_table_checksum.py \
  --clickhouse_host localhost \
  --clickhouse_database mydb \
  --tables_regex '.*'
```
