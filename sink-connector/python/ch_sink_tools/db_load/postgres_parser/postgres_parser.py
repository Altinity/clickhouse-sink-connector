# sink-connector/python/db_load/postgres_parser/postgres_parser.py
#
# Public API for parsing raw PostgreSQL DDL strings arriving from CDC WAL
# events (Debezium logical-replication change events).
#
# This module mirrors the MySQL equivalent at:
#   sink-connector/python/db_load/mysql_parser/mysql_parser.py
#
# Usage
# -----
#   from db_load.postgres_parser.postgres_parser import parse_postgres_ddl
#
#   result = parse_postgres_ddl("CREATE TABLE orders (id bigint PRIMARY KEY, amount numeric(10,2))")
#   # result["action"]        == "create_table"
#   # result["table_name"]    == "orders"
#   # result["columns"]       == [{"column_name": "id", "pg_type": "bigint",
#   #                               "ch_type": "Int64", "nullable": False, "primary_key": True}, ...]
#   # result["clickhouse_sql"] == "CREATE TABLE orders ( ... ) ENGINE = ..."
#
import sys
import logging

from antlr4 import InputStream, CommonTokenStream, ParseTreeWalker
from antlr4.error.ErrorListener import ErrorListener

from ch_sink_tools.db_load.postgres_parser.PostgreSQLLexer import PostgreSQLLexer
from ch_sink_tools.db_load.postgres_parser.PostgreSQLParser import PostgreSQLParser
from ch_sink_tools.db_load.postgres_parser.CreateTablePostgreSQLParserListener import (
    CreateTablePostgreSQLParserListener,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Error listener
# ---------------------------------------------------------------------------

class _PostgreSQLErrorListener(ErrorListener):
    """Raise an exception on the first syntax error so callers get a clear signal."""

    def syntaxError(self, recognizer, offendingSymbol, line, column, msg, e):
        raise SyntaxError(
            f"PostgreSQL DDL parse error at line {line} col {column}: {msg}"
        )


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def parse_postgres_ddl(source: str) -> dict:
    """
    Parse a raw PostgreSQL DDL string (from CDC WAL events) and return a
    structured representation suitable for ClickHouse DDL generation.

    Parameters
    ----------
    source : str
        Raw PostgreSQL DDL SQL string, e.g.::

            "CREATE TABLE public.orders (id bigint PRIMARY KEY, total numeric(10,2) NOT NULL)"
            "ALTER TABLE orders ADD COLUMN note text"
            "DROP TABLE orders"
            "ALTER TABLE orders RENAME TO archived_orders"

    Returns
    -------
    dict
        Keys depend on ``action``:

        Always present
            ``action``      : str  – "create_table" | "alter_table" |
                                     "drop_table"   | "rename_table" | "unknown"
            ``table_name``  : str  – schema-qualified name as written in the DDL
            ``raw_sql``     : str  – the input string (may be omitted for "unknown")

        "create_table" only
            ``columns``      : list[dict] with keys column_name, pg_type, ch_type,
                               nullable, primary_key
            ``primary_keys`` : list[str]
            ``clickhouse_sql``: str  – best-effort ClickHouse CREATE TABLE statement

        "alter_table" only
            ``alter_cmds``  : list[dict] with keys op, column_name, pg_type, ch_type
                              op ∈ {"add_column","drop_column","alter_type","other"}

        "rename_table" only
            ``new_name``    : str

        "create_table" | "alter_table" | "drop_table" | "rename_table"
            ``clickhouse_sql`` : str | None

    Raises
    ------
    SyntaxError
        If the input cannot be parsed as valid PostgreSQL DDL.
    """
    input_stream = InputStream(source)

    lexer = PostgreSQLLexer(input_stream)
    lexer.removeErrorListeners()
    lexer.addErrorListener(_PostgreSQLErrorListener())

    token_stream = CommonTokenStream(lexer)

    parser = PostgreSQLParser(token_stream)
    parser.removeErrorListeners()
    parser.addErrorListener(_PostgreSQLErrorListener())

    tree = parser.root()

    listener = CreateTablePostgreSQLParserListener()
    walker = ParseTreeWalker()
    walker.walk(listener, tree)

    result = listener.get_result()
    logger.debug("parse_postgres_ddl result: action=%s table=%s",
                 result.get("action"), result.get("table_name"))
    return result


# ---------------------------------------------------------------------------
# CLI convenience (python -m db_load.postgres_parser.postgres_parser <file>)
# ---------------------------------------------------------------------------

def _main(argv: list[str]) -> None:
    import json

    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    )
    root_logger.addHandler(handler)

    if len(argv) < 2:
        print("Usage: python postgres_parser.py <ddl_file.sql>", file=sys.stderr)
        sys.exit(1)

    with open(argv[1], "r", encoding="utf-8") as fh:
        source = fh.read()

    result = parse_postgres_ddl(source)
    print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    _main(sys.argv)
