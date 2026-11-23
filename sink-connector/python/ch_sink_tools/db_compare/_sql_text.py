"""
Small SQL text helpers shared by the MySQL and ClickHouse checksum side scripts
(spec 11.02 sections 3.3 and 3.10).

Kept free of database client imports so the unit tests and both side scripts
can import it without a client library installed.
"""

PARTITION_PLACEHOLDER = "{partition_expression}"


def clickhouse_identifier(name):
    """``name`` as a double-quoted ClickHouse identifier (backslash and quote escaped)."""
    return '"' + str(name).replace("\\", "\\\\").replace('"', '\\"') + '"'


def clickhouse_literal(value):
    """``value`` as a single-quoted ClickHouse string literal (backslash and quote escaped)."""
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def clickhouse_table_reference(database, table):
    """``database.table`` with both parts quoted."""
    return clickhouse_identifier(database) + "." + clickhouse_identifier(table)


def substitute_partition_expression(where, partition_expression, table_label):
    """``where`` with ``{partition_expression}`` replaced by the table's partition
    expression (spec 11.02 section 3.10).

    A ``where`` without the placeholder is returned unchanged. A ``where`` that
    has it for a table with no partition expression (not partitioned, or the
    catalog returned nothing) raises ValueError: substituting an empty string
    would send ``WHERE  >= ...`` to the server, and dropping the predicate would
    silently widen a date slice to the whole table. The table is then reported
    as an error by the driver, never as a match."""
    if not where or PARTITION_PLACEHOLDER not in where:
        return where
    expression = "" if partition_expression is None else str(partition_expression).strip()
    if not expression:
        raise ValueError(
            f"{table_label} has no partition expression, but the filter uses {PARTITION_PLACEHOLDER} "
            "(--partition_date or a table where clause): restrict the run to partitioned tables "
            "or drop the placeholder for this table")
    return where.replace(PARTITION_PLACEHOLDER, expression)

