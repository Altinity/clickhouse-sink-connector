# sink-connector/python/db_load/postgres_parser/CreateTablePostgreSQLParserListener.py
#
# Hand-written ANTLR listener for PostgreSQL DDL events arriving via CDC WAL.
#
# Use-case: parse raw DDL strings captured from Debezium / logical-replication
# change events (ALTER TABLE, CREATE TABLE, DROP TABLE, RENAME TABLE) and
# produce structured dicts that the Java sink-connector can use to mirror the
# schema change in ClickHouse.
#
# This file intentionally does NOT interact with ClickHouse directly; it only
# builds a description dict so that the caller can decide what action to take.
#
# Grammar rule → listener method mapping (all lowercase per ANTLR Python target):
#   createstmt        → exitCreatestmt      (CREATE TABLE)
#   altertablestmt    → exitAltertablestmt  (ALTER TABLE ... ADD/DROP/ALTER COLUMN)
#   alter_table_cmd   → exitAlter_table_cmd (individual ALTER TABLE sub-command)
#   dropstmt          → exitDropstmt        (DROP TABLE)
#   renamestmt        → exitRenamestmt      (RENAME TABLE / ALTER TABLE … RENAME TO)
#   columnDef         → exitColumnDef       (column definition inside CREATE TABLE)
#
from __future__ import annotations

import logging
from typing import Any

from antlr4 import ParserRuleContext

from ch_sink_tools.db_load.postgres_parser.PostgreSQLParserListener import PostgreSQLParserListener
from ch_sink_tools.db_load.postgres_parser.PostgreSQLParser import PostgreSQLParser
from ch_sink_tools.db_load.postgres_type_mapper import map_pg_type

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _text(ctx: ParserRuleContext) -> str:
    """Return the original source text covered by *ctx* (inclusive)."""
    token_source = ctx.start.getTokenSource()
    input_stream = token_source.inputStream
    start = ctx.start.start
    stop = ctx.stop.stop
    return input_stream.getText(start, stop)


def _has_token(ctx: ParserRuleContext, token_type: int) -> bool:
    """Return True if *ctx* directly contains a terminal with *token_type*."""
    from antlr4 import TerminalNode
    for i in range(ctx.getChildCount()):
        child = ctx.getChild(i)
        if isinstance(child, TerminalNode) and child.getSymbol().type == token_type:
            return True
    return False


# ---------------------------------------------------------------------------
# Listener
# ---------------------------------------------------------------------------

class CreateTablePostgreSQLParserListener(PostgreSQLParserListener):
    """
    Listener that collects DDL events from a PostgreSQL parse tree and builds
    a structured result dict suitable for ClickHouse schema synchronisation.

    Result dict keys
    ----------------
    action        : str  – one of "create_table", "alter_table", "drop_table",
                           "rename_table", "unknown"
    table_name    : str  – schema-qualified table name as it appears in the SQL
    columns       : list[dict]  – present for "create_table" and add-column alters
                    Each column dict has:
                        column_name   : str
                        pg_type       : str  (raw PostgreSQL type text)
                        ch_type       : str  (mapped ClickHouse type)
                        nullable      : bool
                        primary_key   : bool
    alter_cmds    : list[dict]  – present for "alter_table"; each dict has:
                        op            : str  ("add_column","drop_column",
                                              "alter_type","rename_column",
                                              "other")
                        column_name   : str  (may be "" for "other")
                        pg_type       : str  (for add_column / alter_type)
                        ch_type       : str  (for add_column / alter_type)
    new_name      : str  – present for "rename_table"
    clickhouse_sql: str | None – a best-effort ClickHouse DDL string for
                    "create_table" actions; None for ALTER / DROP / RENAME
                    because the Java connector handles those.
    raw_sql       : str  – the original source text of the matched statement
    """

    # ------------------------------------------------------------------ init
    def __init__(self) -> None:
        # Accumulator for column defs inside a CREATE TABLE being visited
        self._pending_columns: list[dict] = []
        # Primary-key column names found in the CREATE TABLE body
        self._primary_keys: list[str] = []
        # Final result
        self._result: dict[str, Any] = {"action": "unknown", "table_name": ""}

    # ------------------------------------------------------------------ helpers
    def _reset_create_state(self) -> None:
        self._pending_columns = []
        self._primary_keys = []

    # ------------------------------------------------------------------ columnDef
    # Called for every column definition inside CREATE TABLE (…)
    def exitColumnDef(self, ctx: PostgreSQLParser.ColumnDefContext) -> None:
        col_name = _text(ctx.colid())
        pg_type_text = _text(ctx.typename())
        ch_type = map_pg_type(pg_type_text)

        nullable = True
        is_pk = False

        # Walk colquallist → colconstraint* → colconstraintelem
        colquallist = ctx.colquallist()
        if colquallist:
            for colconstraint in colquallist.colconstraint():
                elem = colconstraint.colconstraintelem()
                if elem is None:
                    continue
                elem_text = _text(elem).upper()
                if "NOT NULL" in elem_text:
                    nullable = False
                elif elem_text.strip() == "NULL":
                    nullable = True
                if "PRIMARY KEY" in elem_text:
                    is_pk = True
                    self._primary_keys.append(col_name)

        col_dict: dict[str, Any] = {
            "column_name": col_name,
            "pg_type": pg_type_text,
            "ch_type": ch_type,
            "nullable": nullable,
            "primary_key": is_pk,
        }
        logger.debug("columnDef: %s", col_dict)
        self._pending_columns.append(col_dict)

    # ------------------------------------------------------------------ createstmt
    def enterCreatestmt(self, ctx: PostgreSQLParser.CreatestmtContext) -> None:
        self._reset_create_state()

    def exitCreatestmt(self, ctx: PostgreSQLParser.CreatestmtContext) -> None:
        table_name = _text(ctx.qualified_name(0))
        raw = _text(ctx)

        # Build ClickHouse CREATE TABLE DDL (best-effort)
        pk_cols = self._primary_keys or []
        order_by = ", ".join(pk_cols) if pk_cols else "tuple()"

        ch_cols: list[str] = []
        for col in self._pending_columns:
            ch_type = col["ch_type"]
            if col["nullable"]:
                ch_type = f"Nullable({ch_type})"
            ch_cols.append(f"    `{col['column_name']}` {ch_type}")

        # Synthetic CDC columns
        ch_cols.append("    `_version` Nullable(UInt64)")
        ch_cols.append("    `is_deleted` UInt8 DEFAULT 0")

        ch_sql = (
            f"CREATE TABLE {table_name} (\n"
            + ",\n".join(ch_cols)
            + f"\n) ENGINE = ReplacingMergeTree(_version, is_deleted)"
            f"\nORDER BY ({order_by})"
        )

        self._result = {
            "action": "create_table",
            "table_name": table_name,
            "columns": list(self._pending_columns),
            "primary_keys": pk_cols,
            "clickhouse_sql": ch_sql,
            "raw_sql": raw,
        }
        logger.info("create_table: %s  columns=%d", table_name, len(self._pending_columns))
        self._reset_create_state()

    # ------------------------------------------------------------------ altertablestmt
    def exitAltertablestmt(self, ctx: PostgreSQLParser.AltertablestmtContext) -> None:
        # relation_expr holds the table name
        rel = ctx.relation_expr()
        if rel is None:
            return
        table_name = _text(rel)
        raw = _text(ctx)

        # Collect alter_table_cmd results that were already processed
        alter_cmds: list[dict] = getattr(self, "_current_alter_cmds", [])

        self._result = {
            "action": "alter_table",
            "table_name": table_name,
            "alter_cmds": alter_cmds,
            "clickhouse_sql": None,
            "raw_sql": raw,
        }
        # reset for next statement
        self._current_alter_cmds = []
        logger.info(
            "alter_table: %s  cmds=%d", table_name, len(alter_cmds)
        )

    def enterAltertablestmt(self, ctx: PostgreSQLParser.AltertablestmtContext) -> None:
        self._current_alter_cmds: list[dict] = []

    def exitAlter_table_cmd(
        self, ctx: PostgreSQLParser.Alter_table_cmdContext
    ) -> None:
        """
        Decode one sub-command from an ALTER TABLE statement.

        Grammar alternatives (subset relevant to CDC schema drift):
          ADD_P [COLUMN] [IF NOT EXISTS] columnDef
          DROP [COLUMN] [IF EXISTS] colid
          ALTER [COLUMN] colid ... TYPE typename ...
          ALTER [COLUMN] colid SET/DROP NOT NULL
        """
        if not hasattr(self, "_current_alter_cmds"):
            self._current_alter_cmds = []

        P = PostgreSQLParser
        cmd: dict[str, Any] = {"op": "other", "column_name": "", "pg_type": "", "ch_type": ""}

        # ---- ADD COLUMN ----
        # Grammar: ADD_P columnDef  |  ADD_P COLUMN columnDef  |  ADD_P [COLUMN] IF NOT EXISTS columnDef
        col_def = ctx.columnDef()
        if col_def and _has_token(ctx, P.ADD_P):
            col_name = _text(col_def.colid())
            pg_type_text = _text(col_def.typename())
            ch_type = map_pg_type(pg_type_text)
            cmd = {
                "op": "add_column",
                "column_name": col_name,
                "pg_type": pg_type_text,
                "ch_type": ch_type,
            }

        # ---- DROP COLUMN ----
        # Grammar: DROP [COLUMN] [IF EXISTS] colid drop_behavior_?
        elif _has_token(ctx, P.DROP) and ctx.colid():
            col_name = _text(ctx.colid())
            cmd = {"op": "drop_column", "column_name": col_name, "pg_type": "", "ch_type": ""}

        # ---- ALTER COLUMN … TYPE ----
        # Grammar: ALTER [COLUMN] colid set_data_? TYPE_P typename …
        elif _has_token(ctx, P.ALTER) and _has_token(ctx, P.TYPE_P) and ctx.colid() and ctx.typename():
            col_name = _text(ctx.colid())
            pg_type_text = _text(ctx.typename())
            ch_type = map_pg_type(pg_type_text)
            cmd = {
                "op": "alter_type",
                "column_name": col_name,
                "pg_type": pg_type_text,
                "ch_type": ch_type,
            }

        # ---- ALTER COLUMN … SET/DROP NOT NULL ----
        elif _has_token(ctx, P.ALTER) and ctx.colid():
            col_name = _text(ctx.colid())
            cmd = {"op": "other", "column_name": col_name, "pg_type": "", "ch_type": ""}

        logger.debug("alter_table_cmd: %s", cmd)
        self._current_alter_cmds.append(cmd)

    # ------------------------------------------------------------------ dropstmt
    def exitDropstmt(self, ctx: PostgreSQLParser.DropstmtContext) -> None:
        raw = _text(ctx)

        # dropstmt: DROP object_type_any_name [IF EXISTS] any_name_list_  …
        # We only care about TABLE drops; object_type_any_name starts with TABLE token.
        obj_type_ctx = ctx.object_type_any_name()
        if obj_type_ctx is None:
            return
        obj_type_text = _text(obj_type_ctx).upper().strip()
        if "TABLE" not in obj_type_text:
            return

        # any_name_list_ contains the table names
        any_name_list_ctx = ctx.any_name_list_()
        table_names_text = _text(any_name_list_ctx) if any_name_list_ctx else ""

        self._result = {
            "action": "drop_table",
            "table_name": table_names_text,
            "clickhouse_sql": None,
            "raw_sql": raw,
        }
        logger.info("drop_table: %s", table_names_text)

    # ------------------------------------------------------------------ renamestmt
    def exitRenamestmt(self, ctx: PostgreSQLParser.RenamestmtContext) -> None:
        raw = _text(ctx)

        # Only handle:
        #   ALTER TABLE relation_expr RENAME TO name
        #   ALTER TABLE IF EXISTS relation_expr RENAME TO name
        # Check for TABLE token and RENAME token (both must be present).
        if not (_has_token(ctx, PostgreSQLParser.TABLE) and _has_token(ctx, PostgreSQLParser.RENAME)):
            return

        rel = ctx.relation_expr()
        if rel is None:
            return
        table_name = _text(rel)

        # The new name is the last `name` rule child in the rule.
        # Grammar: … RENAME TO name  (name is always last)
        name_ctxs = [ctx.getChild(i) for i in range(ctx.getChildCount())
                     if hasattr(ctx.getChild(i), 'getRuleIndex') and
                     ctx.getChild(i).getRuleIndex() == PostgreSQLParser.RULE_name]
        new_name = _text(name_ctxs[-1]) if name_ctxs else ""

        self._result = {
            "action": "rename_table",
            "table_name": table_name,
            "new_name": new_name,
            "clickhouse_sql": None,
            "raw_sql": raw,
        }
        logger.info("rename_table: %s → %s", table_name, new_name)

    # ------------------------------------------------------------------ public API
    def get_result(self) -> dict[str, Any]:
        """
        Return the structured result of the last top-level DDL statement
        that was matched.  If no relevant DDL was found, returns
        ``{"action": "unknown", "table_name": ""}``.
        """
        return self._result
