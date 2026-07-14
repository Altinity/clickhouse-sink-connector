# `db_load/postgres_parser` — PostgreSQL ANTLR DDL Parser

Parses raw PostgreSQL DDL strings arriving from CDC WAL events (Debezium
logical-replication change records) and returns structured dicts that the
Java sink-connector uses to mirror schema changes in ClickHouse.

---

## Grammar Generation — Status: ✅ SUCCESS

ANTLR 4.11.1 generated all Python files cleanly on 2026-03-01.

**Warnings during generation (harmless):**

```
warning(146): PostgreSQLLexer.g4:1440:0: non-fragment lexer rule
  AfterEscapeStringConstantMode_NotContinued can match the empty string
warning(146): PostgreSQLLexer.g4:1455:0: non-fragment lexer rule
  AfterEscapeStringConstantWithNewlineMode_NotContinued can match the empty string
```

These are known upstream issues in the grammars-v4 PostgreSQL grammar and do
not affect parsing of DDL statements.

---

## Directory Layout

```
db_load/postgres_parser/
├── README.md                              # this file
├── __init__.py                            # re-exports parse_postgres_ddl()
├── postgres_parser.py                     # public API entry point
├── CreateTablePostgreSQLParserListener.py # hand-written listener (business logic)
│
│  ── ANTLR-generated (do not edit manually) ──
├── PostgreSQLLexer.py
├── PostgreSQLLexer.interp
├── PostgreSQLLexer.tokens
├── PostgreSQLParser.py
├── PostgreSQLParser.interp
├── PostgreSQLParser.tokens
├── PostgreSQLParserListener.py
│
│  ── Base-class stubs (sourced from grammars-v4, committed here) ──
├── PostgreSQLLexerBase.py
└── PostgreSQLParserBase.py
```

Grammar source files live at:

```
antlr_grammars/postgres/
├── PostgreSQLLexer.g4          (1 476 lines, superClass = PostgreSQLLexerBase)
├── PostgreSQLParser.g4         (5 470 lines, superClass = PostgreSQLParserBase)
├── PostgreSQLLexerBase.py      (committed stub, copied to output dir by build)
└── PostgreSQLParserBase.py     (committed stub, copied to output dir by build)
```

---

## Re-generating the Python Files

Prerequisites are downloaded by `build_grammars.sh` on first run.  To
regenerate only the PostgreSQL parser after a grammar update:

```bash
cd sink-connector/python

# Ensure JDK and ANTLR jar are present (downloaded by build_grammars.sh if missing)
ls jdk-19.0.2/bin/java antlr-4.11.1-complete.jar

mkdir -p db_load/postgres_parser
cp antlr_grammars/postgres/PostgreSQLLexerBase.py  db_load/postgres_parser/
cp antlr_grammars/postgres/PostgreSQLParserBase.py db_load/postgres_parser/

(
  cd antlr_grammars/postgres
  ../../jdk-19.0.2/bin/java -Xmx500M \
    -cp ../../antlr-4.11.1-complete.jar org.antlr.v4.Tool \
    -Dlanguage=Python3 -no-visitor -listener \
    PostgreSQLLexer.g4 PostgreSQLParser.g4 \
    -o ../../db_load/postgres_parser
)
```

Or simply run the full build script (regenerates both MySQL and PostgreSQL):

```bash
cd sink-connector/python && bash build_grammars.sh
```

---

## Public API

```python
from db_load.postgres_parser.postgres_parser import parse_postgres_ddl

result = parse_postgres_ddl(ddl_string)
```

### Signature

```python
def parse_postgres_ddl(source: str) -> dict:
    """
    Parse a raw PostgreSQL DDL string from a CDC WAL event.

    Parameters
    ----------
    source : str
        A single PostgreSQL DDL statement, e.g.:
            "CREATE TABLE public.orders (id bigint PRIMARY KEY, ...)"
            "ALTER TABLE orders ADD COLUMN note text"
            "DROP TABLE IF EXISTS public.orders"
            "ALTER TABLE orders RENAME TO archived_orders"

    Returns
    -------
    dict
        action        : "create_table" | "alter_table" | "drop_table" |
                        "rename_table" | "unknown"
        table_name    : str   schema-qualified name as written in the DDL
        raw_sql       : str   the input string

        (create_table only)
        columns       : list[dict]  column_name, pg_type, ch_type,
                                    nullable (bool), primary_key (bool)
        primary_keys  : list[str]
        clickhouse_sql: str   best-effort ClickHouse CREATE TABLE DDL

        (alter_table only)
        alter_cmds    : list[dict]  op, column_name, pg_type, ch_type
                        op ∈ {"add_column","drop_column","alter_type","other"}

        (rename_table only)
        new_name      : str

        clickhouse_sql: str | None  (None for ALTER / DROP / RENAME)

    Raises
    ------
    SyntaxError
        If the input cannot be parsed as valid PostgreSQL DDL.
    """
```

### Example output — CREATE TABLE

```python
parse_postgres_ddl("""
    CREATE TABLE public.orders (
        id          bigint                   PRIMARY KEY,
        customer_id integer                  NOT NULL,
        note        text,
        created_at  timestamp with time zone
    );
""")
```

```json
{
  "action": "create_table",
  "table_name": "public.orders",
  "columns": [
    {"column_name": "id",          "pg_type": "bigint",                   "ch_type": "Int64",              "nullable": true,  "primary_key": true},
    {"column_name": "customer_id", "pg_type": "integer",                  "ch_type": "Int32",              "nullable": false, "primary_key": false},
    {"column_name": "note",        "pg_type": "text",                     "ch_type": "String",             "nullable": true,  "primary_key": false},
    {"column_name": "created_at",  "pg_type": "timestamp with time zone", "ch_type": "DateTime64(6, 'UTC')", "nullable": true, "primary_key": false}
  ],
  "primary_keys": ["id"],
  "clickhouse_sql": "CREATE TABLE public.orders (\n    `id` Nullable(Int64),\n    ...\n) ENGINE = ReplacingMergeTree(_version, is_deleted)\nORDER BY (id)",
  "raw_sql": "CREATE TABLE public.orders ( ... )"
}
```

### Example output — ALTER TABLE ADD COLUMN

```python
parse_postgres_ddl("ALTER TABLE orders ADD COLUMN amount numeric(10,2);")
```

```json
{
  "action": "alter_table",
  "table_name": "orders",
  "alter_cmds": [
    {"op": "add_column", "column_name": "amount", "pg_type": "numeric(10,2)", "ch_type": "String"}
  ],
  "clickhouse_sql": null,
  "raw_sql": "ALTER TABLE orders ADD COLUMN amount numeric(10,2)"
}
```

---

## Known Limitations

1. **`numeric(p,s)` maps to `String`** — `map_pg_type()` does not yet strip
   precision/scale suffixes from `numeric`.  This matches the existing
   behaviour for unknown types and is safe (the Java connector stores numeric
   values as strings until a dedicated mapping is added to
   `postgres_type_mapper.py`).

2. **Primary-key nullability** — the grammar marks `PRIMARY KEY` columns
   `nullable=True` (PostgreSQL grammar does not make them implicitly `NOT NULL`
   at the parse-tree level).  Callers should treat `primary_key=True` columns
   as non-nullable regardless of the `nullable` flag.

3. **Inline table constraints** (e.g. `CONSTRAINT pk PRIMARY KEY (a, b)`)
   are not extracted into `primary_keys`; only single-column `PRIMARY KEY`
   constraints in `colconstraintelem` are detected.

4. **Multi-statement input** — only the *last* matched top-level DDL statement
   populates `get_result()`.  Pass one statement per `parse_postgres_ddl()` call.

5. **`ALTER TABLE … DROP COLUMN`** — the ALTER TABLE `DROP COLUMN` sub-command
   returns the `op` as `"drop_column"`.  The Java connector must decide whether
   to propagate the drop to ClickHouse (ClickHouse `ALTER TABLE DROP COLUMN`
   is supported).

6. **No ClickHouse DDL for ALTER / DROP / RENAME** — `clickhouse_sql` is `None`
   for these actions.  The Java connector is responsible for translating them
   using the `alter_cmds` / `new_name` fields.

---

## Files NOT Modified

The following files are intentionally unchanged per the task constraints:

- `sink-connector/python/db/postgres.py`
- `sink-connector/python/db_load/postgres_type_mapper.py`
- `sink-connector/python/db_dump/postgres_dumper.py`
- Any Java source files
