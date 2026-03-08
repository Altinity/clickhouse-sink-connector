# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : override_reconciler.py
# -- Date         :
# -- Summary      : Override config drift detection and reconciliation.
# --
# --   When a ClickHouse table already exists, this module compares the
# --   existing column definitions against the column type override config
# --   and takes corrective action:
# --
# --   ALIAS overrides  → AUTO-ALTER the table to add/modify ALIAS columns
# --   DIRECT overrides → RAISE a detailed error if column types don't match
# --
# -- ============================================================================
"""

import re
import logging

from ch_sink_tools.config.column_type_overrides import ColumnTypeOverrideConfig

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Custom exception
# ---------------------------------------------------------------------------

class ColumnTypeOverrideMismatchError(Exception):
    """Raised when a direct type override doesn't match the existing ClickHouse column type."""
    pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def ch_table_exists(ch_conn, database: str, table: str) -> bool:
    """Check whether a table exists in ClickHouse.

    Parameters
    ----------
    ch_conn  : clickhouse-driver connection (from clickhouse_connection())
    database : ClickHouse database name
    table    : ClickHouse table name

    Returns
    -------
    bool — True if the table exists
    """
    cursor = ch_conn.cursor()
    cursor.execute(f"EXISTS TABLE `{database}`.`{table}`")
    result = cursor.fetchall()
    return result[0][0] == 1


def _strip_nullable(ch_type: str) -> str:
    """Strip Nullable() wrapper from a ClickHouse type string.

    Examples:
        'Nullable(DateTime64(3))'  → 'DateTime64(3)'
        'DateTime64(3)'            → 'DateTime64(3)'
        'Nullable(String)'         → 'String'
        'String'                   → 'String'
    """
    m = re.match(r'^Nullable\((.+)\)$', ch_type.strip())
    if m:
        return m.group(1)
    return ch_type.strip()


def _get_existing_columns(ch_conn, ch_database: str, table_name: str) -> list:
    """Query system.columns for an existing ClickHouse table.

    Returns a list of dicts with keys: name, type, default_kind, default_expression.
    """
    sql = (
        f"SELECT name, type, default_kind, default_expression "
        f"FROM system.columns "
        f"WHERE database = '{ch_database}' AND table = '{table_name}'"
    )
    cursor = ch_conn.cursor()
    cursor.execute(sql)
    rows = cursor.fetchall()
    result = []
    for row in rows:
        result.append({
            'name': row[0],
            'type': row[1],
            'default_kind': row[2],
            'default_expression': row[3],
        })
    return result


# ---------------------------------------------------------------------------
# Main reconciliation function
# ---------------------------------------------------------------------------

def reconcile_overrides_with_existing_table(
    ch_conn,
    ch_database: str,
    table_name: str,
    schema: str,
    override_config: ColumnTypeOverrideConfig,
    logger_override=None,
    database: str = "*",
) -> None:
    """Check existing ClickHouse table against override config and reconcile.

    For ALIAS overrides: AUTO-ALTER table to add/modify ALIAS columns so they
    match the configured definitions.

    For DIRECT overrides: RAISE ``ColumnTypeOverrideMismatchError`` if the
    existing column type doesn't match the configured override type.  The error
    message is detailed and actionable, guiding the user on how to fix it.

    Parameters
    ----------
    ch_conn          : clickhouse-driver connection
    ch_database      : ClickHouse database name
    table_name       : ClickHouse table name
    schema           : PostgreSQL schema (e.g. "public") — used for override lookups
    override_config  : ColumnTypeOverrideConfig instance
    logger_override  : optional logger; falls back to module-level logger
    database         : PostgreSQL database name (e.g. "mydb") — used for override lookups

    Raises
    ------
    ColumnTypeOverrideMismatchError
        When a direct override type does not match the existing column type.
    """
    log = logger_override or logger

    if not override_config or not override_config.has_overrides():
        log.debug(
            f"No overrides configured — skipping reconciliation for "
            f"{ch_database}.{table_name}"
        )
        return

    # Step 1: Query existing ClickHouse columns
    existing_columns = _get_existing_columns(ch_conn, ch_database, table_name)
    if not existing_columns:
        log.debug(
            f"No existing columns found for {ch_database}.{table_name} — "
            f"skipping reconciliation"
        )
        return

    # Build lookup dicts for quick access
    col_by_name = {col['name']: col for col in existing_columns}

    # Step 2: Check DIRECT overrides
    direct_overrides = override_config.get_direct_overrides(database, schema, table_name)
    for override in direct_overrides:
        col_name = override.column
        configured_type = override.target_type

        existing_col = col_by_name.get(col_name)
        if existing_col is None:
            # Column doesn't exist in CH table — this will be handled at
            # CREATE TABLE time; skip for reconciliation purposes
            log.debug(
                f"Direct override column '{col_name}' not found in "
                f"{ch_database}.{table_name} — skipping (will be created)"
            )
            continue

        existing_type = existing_col['type']

        # Compare types, stripping Nullable wrapper for flexible matching
        # e.g. Nullable(DateTime64(3)) matches config DateTime64(3)
        existing_bare = _strip_nullable(existing_type)
        configured_bare = _strip_nullable(configured_type)

        if existing_bare == configured_bare:
            log.debug(
                f"Direct override for '{col_name}': existing type "
                f"'{existing_type}' matches configured '{configured_type}' — OK"
            )
            continue

        # Types don't match — raise detailed error
        fq_table = f"{ch_database}.{table_name}"

        # Build the Nullable-wrapped version of the configured type for the
        # ALTER suggestion (if the existing column was Nullable, keep it Nullable)
        if existing_type.startswith('Nullable(') and not configured_type.startswith('Nullable('):
            suggested_alter_type = f"Nullable({configured_type})"
        else:
            suggested_alter_type = configured_type

        error_msg = (
            f"\n"
            f"ERROR: Column type override mismatch detected for table '{fq_table}'.\n"
            f"\n"
            f"Column '{col_name}':\n"
            f"  - Configured override type: {configured_type}\n"
            f"  - Actual ClickHouse type:   {existing_type}\n"
            f"\n"
            f"The table already exists with a different column type than your override config specifies.\n"
            f"To fix this, you have the following options:\n"
            f"\n"
            f"  1. DROP and recreate the table:\n"
            f"     DROP TABLE {fq_table};\n"
            f"     Then re-run the dumper to create it with the correct type.\n"
            f"\n"
            f"  2. ALTER the column type manually (if safe):\n"
            f"     ALTER TABLE {fq_table} MODIFY COLUMN `{col_name}` {suggested_alter_type};\n"
            f"     WARNING: This may fail if existing data cannot be converted.\n"
            f"\n"
            f"  3. Update your override config to match the existing table:\n"
            f"     Change the direct override for '{col_name}' to '{existing_bare}' in your config file.\n"
            f"\n"
            f"  4. Remove the direct override to use the default PG→CH type mapping.\n"
        )

        log.error(error_msg)
        raise ColumnTypeOverrideMismatchError(error_msg)

    # Step 3: Check ALIAS overrides
    alias_overrides = override_config.get_alias_overrides(database, schema, table_name)
    for alias_override in alias_overrides:
        alias_col_name = alias_override.alias_column_name
        alias_type = alias_override.alias_type
        expression = alias_override.expression

        existing_col = col_by_name.get(alias_col_name)

        if existing_col is None:
            # Alias column doesn't exist → ADD it
            alter_sql = (
                f"ALTER TABLE `{ch_database}`.`{table_name}` "
                f"ADD COLUMN `{alias_col_name}` {alias_type} ALIAS {expression}"
            )
            log.info(
                f"Adding ALIAS column '{alias_col_name}' to "
                f"{ch_database}.{table_name}: {alter_sql}"
            )
            cursor = ch_conn.cursor()
            cursor.execute(alter_sql)
            log.info(f"Successfully added ALIAS column '{alias_col_name}'")

        else:
            # Alias column exists — check if type or expression match
            existing_type = existing_col['type']
            existing_expr = existing_col['default_expression']
            existing_kind = existing_col['default_kind']

            existing_type_bare = _strip_nullable(existing_type)
            configured_type_bare = _strip_nullable(alias_type)

            needs_modify = False
            reasons = []

            if existing_type_bare != configured_type_bare:
                needs_modify = True
                reasons.append(
                    f"type mismatch: existing='{existing_type}' "
                    f"vs configured='{alias_type}'"
                )

            if existing_expr != expression:
                needs_modify = True
                reasons.append(
                    f"expression mismatch: existing='{existing_expr}' "
                    f"vs configured='{expression}'"
                )

            if existing_kind != 'ALIAS':
                needs_modify = True
                reasons.append(
                    f"default_kind mismatch: existing='{existing_kind}' "
                    f"vs expected='ALIAS'"
                )

            if needs_modify:
                alter_sql = (
                    f"ALTER TABLE `{ch_database}`.`{table_name}` "
                    f"MODIFY COLUMN `{alias_col_name}` {alias_type} ALIAS {expression}"
                )
                log.info(
                    f"Modifying ALIAS column '{alias_col_name}' in "
                    f"{ch_database}.{table_name} ({'; '.join(reasons)}): {alter_sql}"
                )
                cursor = ch_conn.cursor()
                cursor.execute(alter_sql)
                log.info(
                    f"Successfully modified ALIAS column '{alias_col_name}'"
                )
            else:
                log.debug(
                    f"ALIAS column '{alias_col_name}' in "
                    f"{ch_database}.{table_name} already matches config — "
                    f"no action needed"
                )
