#!/usr/bin/env python3
# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : auto_diff.py
# -- Date         : 2026-03-03
# -- Summary      : Automatic diff collection for PostgreSQL -> ClickHouse
# --                checksum failures.
# --
# --                When a table's checksum fails and auto_diff is enabled,
# --                performs a binary search to locate divergent rows using
# --                XOR-aggregate chunk hashing. Writes results to a structured
# --                diff file (JSON or text).
# --
# -- Algorithm    : Split PK range into N chunks, compute XOR-aggregate of
# --                per-row MD5 hashes on both PG and CH. Mismatched chunks
# --                are recursively subdivided until individual divergent rows
# --                are found or max_depth is reached.
# --
# -- Constraints  : Python 3.6 compatible (NO f-strings, NO walrus operator,
# --                NO capture_output). Uses format() / %s string formatting.
# --
# -- Usage        : Called from top_level_postgres_checksum.py after Phase B
# --                when a table FAILs checksum and auto_diff.enabled=true.
# --
"""
import logging
import time
import json
import os
from datetime import datetime, timezone

from ch_sink_tools.db.clickhouse import clickhouse_connection, execute_sql
from ch_sink_tools.db.postgres import execute_pg
from ch_sink_tools.db_compare.postgres_table_checksum import build_pg_select_expression
from ch_sink_tools.db_compare._expressions import _build_ch_col_expr

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Maximum uint64 value (2^64) for signed->unsigned conversion
_UINT64_MAX = 2 ** 64


# ---------------------------------------------------------------------------
# Column expression builders (reuse existing functions)
# ---------------------------------------------------------------------------

def _build_pg_row_concat(columns_meta, skip_columns, include_floating_point,
                         include_json):
    """
    Build a PG SQL expression that concatenates all column values into a
    single string for hashing. Uses the same build_pg_select_expression()
    as the main checksum tool.

    Returns (concat_expr, included_col_names) or (None, []) if no columns.
    """
    col_exprs = []
    included_cols = []

    skip_set = set(skip_columns) if skip_columns else set()

    for col in columns_meta:
        col_name = col['column_name']
        if col_name in skip_set:
            continue

        pg_type = col['pg_type']
        is_nullable = col['nullable']
        udt_name = col.get('udt_name', '')

        (expr, skip) = build_pg_select_expression(
            col_name, pg_type, is_nullable, udt_name,
            include_floating_point=include_floating_point,
            include_json=include_json,
            excluded_columns=None,
        )
        if skip:
            continue

        col_exprs.append(expr)
        included_cols.append(col_name)

    if not col_exprs:
        return (None, [])

    # Concatenate with '|' separator (matching the design doc approach
    # for auto-diff, simpler than concat_ws(chr(1),...) used by checksum)
    concat = " || '|' || ".join(col_exprs)
    return (concat, included_cols)


def _build_ch_row_concat(columns_meta, skip_columns, include_floating_point,
                         include_json, _build_ch_col_expr_fn):
    """
    Build a CH SQL expression that concatenates all column values into a
    single string for hashing. Uses _build_ch_col_expr() from
    top_level_postgres_checksum.py.

    Returns (concat_expr, included_col_names) or (None, []) if no columns.
    """
    col_exprs = []
    included_cols = []

    skip_set = set(skip_columns) if skip_columns else set()

    for col in columns_meta:
        col_name = col['column_name']
        if col_name in skip_set:
            continue

        pg_type = col.get('pg_type', '')
        pg_type_lower = pg_type.lower().strip() if pg_type else ''
        udt_name = (col.get('udt_name') or '').lower().strip()
        is_nullable = col.get('nullable', True)

        # Skip floating-point unless included
        if not include_floating_point and pg_type_lower in (
                'real', 'float4', 'double precision', 'float8', 'float'):
            continue

        # Skip json/jsonb unless included
        if not include_json and pg_type_lower in ('json', 'jsonb'):
            continue

        # Skip bytea
        if pg_type_lower == 'bytea':
            continue

        # Skip range types
        if pg_type_lower in ('tstzrange', 'tsrange', 'daterange',
                             'int4range', 'int8range', 'numrange') \
                or udt_name in ('tstzrange', 'tsrange', 'daterange',
                                'int4range', 'int8range', 'numrange'):
            continue

        expr = _build_ch_col_expr_fn(col_name, pg_type, is_nullable)
        col_exprs.append(expr)
        included_cols.append(col_name)

    if not col_exprs:
        return (None, [])

    # Concatenate with '|' separator using concat()
    # concat(expr1, '|', expr2, '|', ...)
    parts = []
    for i, expr in enumerate(col_exprs):
        if i > 0:
            parts.append("'|'")
        parts.append(expr)

    concat = "concat({parts})".format(parts=", ".join(parts))
    return (concat, included_cols)


# ---------------------------------------------------------------------------
# PG signed bigint -> unsigned conversion
# ---------------------------------------------------------------------------

def _pg_xor_to_uint64(val):
    """
    Convert PG bit_xor result (signed bigint) to unsigned uint64 for
    comparison with CH groupBitXor (UInt64).
    """
    if val is None:
        return 0
    val = int(val)
    if val < 0:
        val += _UINT64_MAX
    return val


# ---------------------------------------------------------------------------
# Chunk hash computation
# ---------------------------------------------------------------------------

def _compute_chunk_hash_pg(pg_conn, table_name, pk_column, pg_schema,
                           pg_concat_expr, id_start, id_end):
    """
    Compute XOR-aggregate chunk hash on PG for [id_start, id_end).

    Returns (count, xor_hash_uint64).
    """
    sql = (
        'SELECT count(*), '
        "bit_xor(('x' || substr(md5({concat}), 1, 16))::bit(64)::bigint)::text "
        'FROM "{schema}"."{table}" '
        'WHERE "{pk}" >= %s AND "{pk}" < %s'
    ).format(
        concat=pg_concat_expr,
        schema=pg_schema,
        table=table_name,
        pk=pk_column,
    )
    rows = execute_pg(pg_conn, sql, (id_start, id_end))
    if not rows:
        return (0, 0)
    row = rows[0]
    cnt = int(row.get('count', 0) or 0)
    xor_val = row.get('bit_xor') or row.get('text', None)
    # execute_pg returns RealDictRow; the column alias may vary
    # Try multiple key patterns
    xor_raw = None
    for key in row:
        if key != 'count':
            xor_raw = row[key]
            break
    return (cnt, _pg_xor_to_uint64(xor_raw))


def _compute_chunk_hash_ch(ch_conn, table_name, pk_column, ch_database,
                           ch_concat_expr, id_start, id_end):
    """
    Compute XOR-aggregate chunk hash on CH for [id_start, id_end).

    Returns (count, xor_hash_uint64).
    """
    sql = (
        'SELECT count(), '
        'toString(groupBitXor('
        'reinterpretAsUInt64(reverse(unhex('
        'substring(lower(hex(MD5({concat}))), 1, 16)'
        '))))) '
        'FROM `{database}`.`{table}` FINAL '
        'WHERE is_deleted = 0 '
        'AND "{pk}" >= {start} AND "{pk}" < {end}'
    ).format(
        concat=ch_concat_expr,
        database=ch_database,
        table=table_name,
        pk=pk_column,
        start=id_start,
        end=id_end,
    )
    (rows, cnt) = execute_sql(ch_conn, sql)
    if not rows:
        return (0, 0)
    row = rows[0]
    count = int(row[0] or 0)
    xor_val = int(row[1] or 0) if row[1] is not None else 0
    return (count, xor_val)


# ---------------------------------------------------------------------------
# Per-row hash comparison
# ---------------------------------------------------------------------------

def _get_per_row_hashes_pg(pg_conn, table_name, pk_column, pg_schema,
                           pg_concat_expr, id_start, id_end):
    """
    Get per-row MD5 hashes from PG for [id_start, id_end).

    Returns dict {pk_value: hash_string}.
    """
    sql = (
        'SELECT "{pk}", md5({concat}) AS row_hash '
        'FROM "{schema}"."{table}" '
        'WHERE "{pk}" >= %s AND "{pk}" < %s '
        'ORDER BY "{pk}"'
    ).format(
        pk=pk_column,
        concat=pg_concat_expr,
        schema=pg_schema,
        table=table_name,
    )
    rows = execute_pg(pg_conn, sql, (id_start, id_end))
    result = {}
    for r in rows:
        pk_val = r[pk_column]
        result[pk_val] = r['row_hash']
    return result


def _get_per_row_hashes_ch(ch_conn, table_name, pk_column, ch_database,
                           ch_concat_expr, id_start, id_end):
    """
    Get per-row MD5 hashes from CH for [id_start, id_end).

    Returns dict {pk_value: hash_string}.
    """
    sql = (
        'SELECT "{pk}", lower(hex(MD5({concat}))) AS row_hash '
        'FROM `{database}`.`{table}` FINAL '
        'WHERE is_deleted = 0 '
        'AND "{pk}" >= {start} AND "{pk}" < {end} '
        'ORDER BY "{pk}"'
    ).format(
        pk=pk_column,
        concat=ch_concat_expr,
        database=ch_database,
        table=table_name,
        start=id_start,
        end=id_end,
    )
    (rows, cnt) = execute_sql(ch_conn, sql)
    result = {}
    for r in rows:
        result[r[0]] = r[1]
    return result


# ---------------------------------------------------------------------------
# Find divergent rows in a small range
# ---------------------------------------------------------------------------

def _find_divergent_rows_in_range(pg_conn, ch_conn, table_name, pk_column,
                                  pg_schema, ch_database,
                                  pg_concat_expr, ch_concat_expr,
                                  id_start, id_end, max_rows, found_rows):
    """
    For a small PK range, compare individual row hashes to find divergent rows.

    Returns list of dicts: [{'pk': val, 'type': 'modified'|'pg_only'|'ch_only',
                             'pg_hash': ..., 'ch_hash': ...}]
    """
    pg_hashes = _get_per_row_hashes_pg(
        pg_conn, table_name, pk_column, pg_schema,
        pg_concat_expr, id_start, id_end)
    ch_hashes = _get_per_row_hashes_ch(
        ch_conn, table_name, pk_column, ch_database,
        ch_concat_expr, id_start, id_end)

    divergent = []
    remaining = max_rows - len(found_rows)

    # Check PG rows
    all_pks = sorted(set(list(pg_hashes.keys()) + list(ch_hashes.keys())))
    for pk_val in all_pks:
        if len(divergent) >= remaining:
            break

        pg_hash = pg_hashes.get(pk_val)
        ch_hash = ch_hashes.get(pk_val)

        if pg_hash is not None and ch_hash is not None:
            if pg_hash != ch_hash:
                divergent.append({
                    'pk': pk_val,
                    'type': 'modified',
                    'pg_hash': pg_hash,
                    'ch_hash': ch_hash,
                })
        elif pg_hash is not None and ch_hash is None:
            divergent.append({
                'pk': pk_val,
                'type': 'pg_only',
                'pg_hash': pg_hash,
                'ch_hash': None,
            })
        elif ch_hash is not None and pg_hash is None:
            divergent.append({
                'pk': pk_val,
                'type': 'ch_only',
                'pg_hash': None,
                'ch_hash': ch_hash,
            })

    return divergent


# ---------------------------------------------------------------------------
# Binary search (recursive)
# ---------------------------------------------------------------------------

def _binary_search_chunks(pg_conn, ch_conn, table_name, pk_column,
                          pg_schema, ch_database,
                          pg_concat_expr, ch_concat_expr,
                          id_min, id_max, num_chunks, max_depth,
                          per_row_threshold, max_rows,
                          found_rows, skipped_chunks, depth,
                          timeout_deadline, stats):
    """
    Recursive binary search. Subdivides the [id_min, id_max) range into
    num_chunks sub-chunks, compares XOR-aggregate hashes, and recurses
    into mismatched chunks.

    Modifies found_rows (list) and skipped_chunks (list) in place.
    """
    # Check early termination conditions
    if len(found_rows) >= max_rows:
        return
    if timeout_deadline and time.time() > timeout_deadline:
        logging.warning(
            "AUTO_DIFF: [%s] timeout reached at depth %d, stopping search",
            table_name, depth)
        return

    # Compute chunk boundaries
    range_size = id_max - id_min
    if range_size <= 0:
        return

    chunk_size = max(1, range_size // num_chunks)
    chunks = []
    current = id_min
    for i in range(num_chunks):
        chunk_start = current
        if i == num_chunks - 1:
            chunk_end = id_max
        else:
            chunk_end = current + chunk_size
        if chunk_start >= id_max:
            break
        chunks.append((chunk_start, chunk_end))
        current = chunk_end

    # Compare chunk hashes
    mismatched = []
    for (c_start, c_end) in chunks:
        if len(found_rows) >= max_rows:
            return
        if timeout_deadline and time.time() > timeout_deadline:
            return

        (pg_cnt, pg_xor) = _compute_chunk_hash_pg(
            pg_conn, table_name, pk_column, pg_schema,
            pg_concat_expr, c_start, c_end)
        (ch_cnt, ch_xor) = _compute_chunk_hash_ch(
            ch_conn, table_name, pk_column, ch_database,
            ch_concat_expr, c_start, c_end)

        stats['total_chunk_queries'] += 2

        if pg_xor != ch_xor or pg_cnt != ch_cnt:
            mismatched.append((c_start, c_end, pg_cnt, ch_cnt))
            logging.info(
                "AUTO_DIFF: [%s] depth=%d chunk [%s, %s) MISMATCH "
                "pg_cnt=%d ch_cnt=%d pg_xor=%s ch_xor=%s",
                table_name, depth, c_start, c_end,
                pg_cnt, ch_cnt, pg_xor, ch_xor)
        else:
            logging.debug(
                "AUTO_DIFF: [%s] depth=%d chunk [%s, %s) MATCH cnt=%d",
                table_name, depth, c_start, c_end, pg_cnt)

    if not mismatched:
        return

    stats['levels_explored'] = max(stats['levels_explored'], depth + 1)

    # Process mismatched chunks
    for (c_start, c_end, pg_cnt, ch_cnt) in mismatched:
        if len(found_rows) >= max_rows:
            return
        if timeout_deadline and time.time() > timeout_deadline:
            return

        chunk_rows = max(pg_cnt, ch_cnt)

        # If chunk is small enough or at max depth, do per-row comparison
        if chunk_rows <= per_row_threshold or depth >= max_depth:
            if chunk_rows > per_row_threshold:
                logging.warning(
                    "AUTO_DIFF: [%s] chunk [%s, %s) has %d rows at max_depth=%d, "
                    "exceeds per_row_threshold=%d — skipping",
                    table_name, c_start, c_end, chunk_rows, depth,
                    per_row_threshold)
                skipped_chunks.append({
                    'pk_range': [c_start, c_end],
                    'reason': 'exceeds per_row_threshold at max_depth',
                    'row_estimate': chunk_rows,
                })
                continue

            logging.info(
                "AUTO_DIFF: [%s] depth=%d per-row comparison [%s, %s) "
                "(%d rows)",
                table_name, depth, c_start, c_end, chunk_rows)

            stats['total_per_row_queries'] += 2
            new_divergent = _find_divergent_rows_in_range(
                pg_conn, ch_conn, table_name, pk_column,
                pg_schema, ch_database,
                pg_concat_expr, ch_concat_expr,
                c_start, c_end, max_rows, found_rows)

            for d in new_divergent:
                if len(found_rows) >= max_rows:
                    break
                found_rows.append(d)
                logging.info(
                    "AUTO_DIFF: [%s] found divergent row pk=%s type=%s",
                    table_name, d['pk'], d['type'])
        else:
            # Recurse deeper
            _binary_search_chunks(
                pg_conn, ch_conn, table_name, pk_column,
                pg_schema, ch_database,
                pg_concat_expr, ch_concat_expr,
                c_start, c_end, num_chunks, max_depth,
                per_row_threshold, max_rows,
                found_rows, skipped_chunks, depth + 1,
                timeout_deadline, stats)


# ---------------------------------------------------------------------------
# Fetch full row data for divergent rows
# ---------------------------------------------------------------------------

def _fetch_full_rows(pg_conn, ch_conn, table_name, pk_column,
                     pg_schema, ch_database, divergent_rows,
                     columns_meta, skip_columns, include_floating_point,
                     include_json, _build_ch_col_expr_fn, per_column_diff):
    """
    Fetch full row data from both PG and CH for divergent rows.
    Optionally compute per-column diff.

    Returns list of dicts with full row comparison data.
    """
    if not divergent_rows:
        return []

    skip_set = set(skip_columns) if skip_columns else set()

    # Build list of columns to fetch (only included ones)
    fetch_cols = []
    for col in columns_meta:
        col_name = col['column_name']
        if col_name in skip_set:
            continue
        pg_type = col['pg_type'].lower().strip()
        udt_name = (col.get('udt_name') or '').lower().strip()
        if not include_floating_point and pg_type in (
                'real', 'float4', 'double precision', 'float8', 'float'):
            continue
        if not include_json and pg_type in ('json', 'jsonb'):
            continue
        if pg_type == 'bytea':
            continue
        if pg_type in ('tstzrange', 'tsrange', 'daterange',
                       'int4range', 'int8range', 'numrange') \
                or udt_name in ('tstzrange', 'tsrange', 'daterange',
                                'int4range', 'int8range', 'numrange'):
            continue
        fetch_cols.append(col)

    results = []

    for div_row in divergent_rows:
        pk_val = div_row['pk']
        diff_type = div_row['type']

        entry = {
            'pk_value': pk_val,
            'diff_type': diff_type,
            'pg_row_hash': div_row.get('pg_hash'),
            'ch_row_hash': div_row.get('ch_hash'),
            'columns': None,
        }

        pg_row_data = None
        ch_row_data = None

        # Fetch PG row
        if diff_type in ('modified', 'pg_only'):
            pg_select_parts = []
            for col in fetch_cols:
                col_name = col['column_name']
                if col_name == pk_column:
                    continue
                pg_type = col['pg_type']
                is_nullable = col['nullable']
                udt_name = col.get('udt_name', '')
                (expr, skip) = build_pg_select_expression(
                    col_name, pg_type, is_nullable, udt_name,
                    include_floating_point=include_floating_point,
                    include_json=include_json,
                    excluded_columns=None,
                )
                if skip:
                    continue
                pg_select_parts.append(
                    '{expr} AS "{col}"'.format(expr=expr, col=col_name))

            if pg_select_parts:
                pg_sql = (
                    'SELECT {cols} FROM "{schema}"."{table}" '
                    'WHERE "{pk}" = %s'
                ).format(
                    cols=", ".join(pg_select_parts),
                    schema=pg_schema,
                    table=table_name,
                    pk=pk_column,
                )
                pg_rows = execute_pg(pg_conn, pg_sql, (pk_val,))
                if pg_rows:
                    pg_row_data = dict(pg_rows[0])

        # Fetch CH row
        if diff_type in ('modified', 'ch_only'):
            ch_select_parts = []
            for col in fetch_cols:
                col_name = col['column_name']
                if col_name == pk_column:
                    continue
                pg_type = col.get('pg_type', '')
                is_nullable = col.get('nullable', True)
                pg_type_lower = pg_type.lower().strip() if pg_type else ''
                udt_name = (col.get('udt_name') or '').lower().strip()
                # Skip same types as concat builder
                if not include_floating_point and pg_type_lower in (
                        'real', 'float4', 'double precision',
                        'float8', 'float'):
                    continue
                if not include_json and pg_type_lower in ('json', 'jsonb'):
                    continue
                if pg_type_lower == 'bytea':
                    continue
                if pg_type_lower in ('tstzrange', 'tsrange', 'daterange',
                                     'int4range', 'int8range', 'numrange') \
                        or udt_name in ('tstzrange', 'tsrange', 'daterange',
                                        'int4range', 'int8range', 'numrange'):
                    continue

                expr = _build_ch_col_expr_fn(col_name, pg_type, is_nullable)
                ch_select_parts.append(
                    '{expr} AS `{col}`'.format(expr=expr, col=col_name))

            if ch_select_parts:
                ch_sql = (
                    'SELECT {cols} FROM `{database}`.`{table}` FINAL '
                    'WHERE is_deleted = 0 AND "{pk}" = {pk_val}'
                ).format(
                    cols=", ".join(ch_select_parts),
                    database=ch_database,
                    table=table_name,
                    pk=pk_column,
                    pk_val=pk_val,
                )
                (ch_rows, ch_cnt) = execute_sql(ch_conn, ch_sql)
                if ch_rows:
                    ch_col_names = [c['column_name'] for c in fetch_cols
                                    if c['column_name'] not in skip_set]
                    # Re-filter for the same exclusion logic
                    actual_ch_cols = []
                    for col in fetch_cols:
                        cn = col['column_name']
                        if cn == pk_column:
                            continue
                        pt = col['pg_type'].lower().strip() if col.get('pg_type') else ''
                        un = (col.get('udt_name') or '').lower().strip()
                        if not include_floating_point and pt in (
                                'real', 'float4', 'double precision',
                                'float8', 'float'):
                            continue
                        if not include_json and pt in ('json', 'jsonb'):
                            continue
                        if pt == 'bytea':
                            continue
                        if pt in ('tstzrange', 'tsrange', 'daterange',
                                  'int4range', 'int8range', 'numrange') \
                                or un in ('tstzrange', 'tsrange', 'daterange',
                                          'int4range', 'int8range', 'numrange'):
                            continue
                        actual_ch_cols.append(cn)

                    ch_row_data = {}
                    for i, col_name in enumerate(actual_ch_cols):
                        if i < len(ch_rows[0]):
                            val = ch_rows[0][i]
                            ch_row_data[col_name] = (
                                str(val) if val is not None else None)

        # Compute per-column diff if requested
        if per_column_diff and diff_type == 'modified' \
                and pg_row_data and ch_row_data:
            columns_diff = {}
            for col in fetch_cols:
                col_name = col['column_name']
                if col_name == pk_column:
                    continue
                pg_val = pg_row_data.get(col_name)
                ch_val = ch_row_data.get(col_name)
                # Convert to string for comparison
                pg_str = str(pg_val) if pg_val is not None else None
                ch_str = str(ch_val) if ch_val is not None else None
                match = (pg_str == ch_str)
                columns_diff[col_name] = {
                    'pg': pg_str,
                    'ch': ch_str,
                    'match': match,
                }
            entry['columns'] = columns_diff
        elif diff_type == 'pg_only' and pg_row_data:
            entry['pg_row'] = {}
            for k, v in pg_row_data.items():
                entry['pg_row'][k] = str(v) if v is not None else None
        elif diff_type == 'ch_only' and ch_row_data:
            entry['ch_row'] = {}
            for k, v in ch_row_data.items():
                entry['ch_row'][k] = str(v) if v is not None else None

        results.append(entry)

    return results


# ---------------------------------------------------------------------------
# Get PK range
# ---------------------------------------------------------------------------

def _get_id_range(pg_conn, ch_conn, table_name, pk_column, pg_schema,
                  ch_database):
    """
    Get MIN/MAX PK from both PG and CH, return intersection range.

    Returns (id_min, id_max) or (None, None) if empty.
    """
    # PG min/max
    pg_sql = (
        'SELECT min("{pk}") AS mn, max("{pk}") AS mx '
        'FROM "{schema}"."{table}"'
    ).format(pk=pk_column, schema=pg_schema, table=table_name)
    pg_rows = execute_pg(pg_conn, pg_sql)
    if not pg_rows or pg_rows[0]['mn'] is None:
        return (None, None)
    pg_min = int(pg_rows[0]['mn'])
    pg_max = int(pg_rows[0]['mx'])

    # CH min/max
    ch_sql = (
        'SELECT min("{pk}") AS mn, max("{pk}") AS mx '
        'FROM `{database}`.`{table}` FINAL '
        'WHERE is_deleted = 0'
    ).format(pk=pk_column, database=ch_database, table=table_name)
    (ch_rows, ch_cnt) = execute_sql(ch_conn, ch_sql)
    if not ch_rows or ch_rows[0][0] is None:
        return (None, None)
    ch_min = int(ch_rows[0][0])
    ch_max = int(ch_rows[0][1])

    # Use the wider range (union) to catch rows in only one side
    id_min = min(pg_min, ch_min)
    # +1 because our range is [min, max+1) to include the max row
    id_max = max(pg_max, ch_max) + 1

    return (id_min, id_max)


# ---------------------------------------------------------------------------
# Write diff file
# ---------------------------------------------------------------------------

def _write_diff_file(table_name, diff_data, output_dir, output_format,
                     run_timestamp):
    """
    Write diff results to file. Supports 'json' and 'text' formats.

    Returns the path to the written file.
    """
    if not os.path.exists(output_dir):
        try:
            os.makedirs(output_dir)
        except OSError:
            pass  # may already exist in race condition

    ts_str = run_timestamp.strftime('%Y%m%d_%H%M%S')
    if output_format == 'text':
        filename = 'checksum_diff_{table}_{ts}.txt'.format(
            table=table_name, ts=ts_str)
    else:
        filename = 'checksum_diff_{table}_{ts}.json'.format(
            table=table_name, ts=ts_str)

    filepath = os.path.join(output_dir, filename)

    if output_format == 'text':
        _write_text_format(filepath, table_name, diff_data)
    else:
        _write_json_format(filepath, diff_data)

    return filepath


def _write_json_format(filepath, diff_data):
    """Write diff data as JSON."""
    # Convert any non-serializable types
    def _default(obj):
        if isinstance(obj, datetime):
            return obj.isoformat()
        if hasattr(obj, '__int__'):
            return int(obj)
        return str(obj)

    with open(filepath, 'w') as f:
        json.dump(diff_data, f, indent=2, default=_default)


def _write_text_format(filepath, table_name, diff_data):
    """Write diff data in human-readable text format."""
    meta = diff_data.get('metadata', {})
    divergent = diff_data.get('divergent_rows', [])
    summary = diff_data.get('summary', {})
    skipped = diff_data.get('skipped_chunks', [])

    lines = []
    lines.append('=== Auto-Diff Report: {table} ==='.format(table=table_name))
    lines.append('Run: {ts}'.format(
        ts=meta.get('run_timestamp', 'N/A')))
    pk_range = meta.get('pk_range', [])
    if pk_range:
        lines.append('PK Range: [{mn}, {mx}]'.format(
            mn=pk_range[0], mx=pk_range[1]))
    lines.append('PG Count: {pg}  CH Count: {ch}'.format(
        pg=meta.get('row_count_pg', 'N/A'),
        ch=meta.get('row_count_ch', 'N/A')))
    lines.append('')

    stats = meta.get('binary_search_stats', {})
    lines.append('Search Stats: levels={levels} chunks_compared={chunks} '
                 'per_row_queries={prq} time={time:.1f}s'.format(
                     levels=stats.get('levels_explored', 0),
                     chunks=stats.get('total_chunk_queries', 0),
                     prq=stats.get('total_per_row_queries', 0),
                     time=stats.get('elapsed_seconds', 0)))
    lines.append('')
    lines.append('Divergent Rows Found: {n}'.format(n=len(divergent)))
    lines.append('')

    for i, row in enumerate(divergent):
        lines.append('--- Row {idx}: {pk}={val} ({dtype}) ---'.format(
            idx=i + 1,
            pk=meta.get('pk_column', 'id'),
            val=row.get('pk_value', '?'),
            dtype=row.get('diff_type', '?')))

        columns = row.get('columns')
        if columns:
            match_count = 0
            for col_name in sorted(columns.keys()):
                col_data = columns[col_name]
                if col_data.get('match', True):
                    match_count += 1
                else:
                    lines.append(
                        '  {col}:  PG=[{pg}]  CH=[{ch}]  X'.format(
                            col=col_name,
                            pg=col_data.get('pg', ''),
                            ch=col_data.get('ch', '')))
            if match_count > 0:
                lines.append(
                    '  ({n} columns match)'.format(n=match_count))
        elif row.get('diff_type') == 'pg_only':
            lines.append('  Row exists in PG only (missing from CH)')
            pg_row = row.get('pg_row', {})
            if pg_row:
                for k in sorted(pg_row.keys()):
                    lines.append('    {k}={v}'.format(
                        k=k, v=pg_row[k]))
        elif row.get('diff_type') == 'ch_only':
            lines.append('  Row exists in CH only (orphan in CH)')
            ch_row = row.get('ch_row', {})
            if ch_row:
                for k in sorted(ch_row.keys()):
                    lines.append('    {k}={v}'.format(
                        k=k, v=ch_row[k]))
        lines.append('')

    if skipped:
        lines.append('Skipped Chunks: {n}'.format(n=len(skipped)))
        for sc in skipped:
            lines.append('  range={r} reason={reason} rows~={est}'.format(
                r=sc.get('pk_range', []),
                reason=sc.get('reason', ''),
                est=sc.get('row_estimate', 0)))
        lines.append('')

    lines.append('Summary: total_divergent={total} modified={mod} '
                 'pg_only={po} ch_only={co}'.format(
                     total=summary.get('total_divergent', 0),
                     mod=summary.get('modified', 0),
                     po=summary.get('pg_only', 0),
                     co=summary.get('ch_only', 0)))

    with open(filepath, 'w') as f:
        f.write('\n'.join(lines))
        f.write('\n')


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def run_auto_diff_for_table(table_name, pg_conn, pg_schema,
                            ch_host, ch_user, ch_password, ch_port,
                            ch_database, ch_secure,
                            ch_exclude_columns,
                            columns_meta, skip_columns,
                            include_floating_point, include_json,
                            auto_diff_cfg, run_timestamp):
    """
    Entry point: binary search + diff output for one table.

    Parameters
    ----------
    table_name          : bare table name (no schema prefix)
    pg_conn             : shared psycopg2 REPEATABLE READ connection
    pg_schema           : PG schema (usually 'public')
    ch_host/user/...    : CH connection parameters
    ch_database         : CH database name
    ch_secure           : CH TLS flag
    ch_exclude_columns  : set of CH columns to exclude (CDC internals)
    columns_meta        : list of column metadata dicts from get_table_columns()
    skip_columns        : list of column names to skip for this table
    include_floating_point : bool
    include_json        : bool
    auto_diff_cfg       : dict with auto_diff config keys
    run_timestamp       : datetime of the checksum run start

    Returns
    -------
    dict with keys: 'diff_file', 'divergent_rows', 'stats'
    """
    # _build_ch_col_expr is now imported at module level from _expressions.py
    # (no longer a circular import risk)

    t0 = time.time()

    # Parse config
    max_divergent_rows = int(auto_diff_cfg.get('max_divergent_rows', 10))
    num_chunks = int(auto_diff_cfg.get('num_chunks', 10))
    max_depth = int(auto_diff_cfg.get('max_depth', 6))
    per_row_threshold = int(auto_diff_cfg.get('per_row_threshold', 100000))
    timeout_seconds = int(auto_diff_cfg.get('timeout_seconds', 600))
    output_dir = auto_diff_cfg.get('output_dir', '.')
    output_format = auto_diff_cfg.get('output_format', 'json')
    per_column_diff = bool(auto_diff_cfg.get('per_column_diff', True))

    timeout_deadline = (time.time() + timeout_seconds) if timeout_seconds > 0 else None

    logging.info(
        "AUTO_DIFF: [%s] starting binary search "
        "(max_rows=%d, num_chunks=%d, max_depth=%d, per_row_threshold=%d, "
        "timeout=%ds)",
        table_name, max_divergent_rows, num_chunks, max_depth,
        per_row_threshold, timeout_seconds)

    # Find PK column from columns_meta
    # The PK is assumed to be the first column (standard for our tables)
    # but we also try to detect it from the metadata
    pk_column = None
    if columns_meta:
        # Check if 'id' column exists (most common PK name)
        for col in columns_meta:
            if col['column_name'] == 'id':
                pk_column = 'id'
                break
        # If no 'id', use the first integer column
        if pk_column is None:
            for col in columns_meta:
                pg_t = col['pg_type'].lower().strip()
                if pg_t in ('integer', 'bigint', 'smallint', 'int',
                            'int2', 'int4', 'int8', 'serial', 'bigserial'):
                    pk_column = col['column_name']
                    break

    if pk_column is None:
        logging.warning(
            "AUTO_DIFF: [%s] no integer PK column found, skipping auto-diff",
            table_name)
        return {
            'diff_file': None,
            'divergent_rows': [],
            'stats': {'skipped': True, 'reason': 'no_integer_pk'},
        }

    # Create CH connection for this table
    ch_conn = clickhouse_connection(
        ch_host, database=ch_database,
        user=ch_user, password=ch_password,
        port=ch_port, secure=ch_secure,
    )

    try:
        # Build concat expressions
        (pg_concat_expr, pg_included_cols) = _build_pg_row_concat(
            columns_meta, skip_columns,
            include_floating_point, include_json)

        if pg_concat_expr is None:
            logging.warning(
                "AUTO_DIFF: [%s] no columns available for hashing",
                table_name)
            return {
                'diff_file': None,
                'divergent_rows': [],
                'stats': {'skipped': True, 'reason': 'no_columns'},
            }

        (ch_concat_expr, ch_included_cols) = _build_ch_row_concat(
            columns_meta, skip_columns,
            include_floating_point, include_json,
            _build_ch_col_expr)

        if ch_concat_expr is None:
            logging.warning(
                "AUTO_DIFF: [%s] no CH columns available for hashing",
                table_name)
            return {
                'diff_file': None,
                'divergent_rows': [],
                'stats': {'skipped': True, 'reason': 'no_ch_columns'},
            }

        logging.info(
            "AUTO_DIFF: [%s] using %d columns for diff hashing, pk=%s",
            table_name, len(pg_included_cols), pk_column)

        # Get PK range
        (id_min, id_max) = _get_id_range(
            pg_conn, ch_conn, table_name, pk_column, pg_schema, ch_database)

        if id_min is None:
            logging.warning(
                "AUTO_DIFF: [%s] empty table or no PK range, skipping",
                table_name)
            return {
                'diff_file': None,
                'divergent_rows': [],
                'stats': {'skipped': True, 'reason': 'empty_table'},
            }

        logging.info(
            "AUTO_DIFF: [%s] PK range: [%s, %s)",
            table_name, id_min, id_max)

        # Run binary search
        found_rows = []
        skipped_chunks = []
        stats = {
            'levels_explored': 0,
            'total_chunk_queries': 0,
            'total_per_row_queries': 0,
        }

        _binary_search_chunks(
            pg_conn, ch_conn, table_name, pk_column,
            pg_schema, ch_database,
            pg_concat_expr, ch_concat_expr,
            id_min, id_max, num_chunks, max_depth,
            per_row_threshold, max_divergent_rows,
            found_rows, skipped_chunks, 0,
            timeout_deadline, stats)

        elapsed = time.time() - t0
        stats['elapsed_seconds'] = elapsed

        logging.info(
            "AUTO_DIFF: [%s] binary search complete: %d divergent rows found "
            "in %.1fs (%d chunk queries, %d per-row queries, %d levels)",
            table_name, len(found_rows), elapsed,
            stats['total_chunk_queries'],
            stats['total_per_row_queries'],
            stats['levels_explored'])

        # Fetch full row data for divergent rows
        detailed_rows = []
        if found_rows and per_column_diff:
            logging.info(
                "AUTO_DIFF: [%s] fetching full row data for %d divergent rows",
                table_name, len(found_rows))
            detailed_rows = _fetch_full_rows(
                pg_conn, ch_conn, table_name, pk_column,
                pg_schema, ch_database, found_rows,
                columns_meta, skip_columns,
                include_floating_point, include_json,
                _build_ch_col_expr, per_column_diff)
        else:
            # Basic entries without per-column diff
            for d in found_rows:
                detailed_rows.append({
                    'pk_value': d['pk'],
                    'diff_type': d['type'],
                    'pg_row_hash': d.get('pg_hash'),
                    'ch_row_hash': d.get('ch_hash'),
                    'columns': None,
                })

        # Count by type
        modified_count = sum(
            1 for d in detailed_rows if d.get('diff_type') == 'modified')
        pg_only_count = sum(
            1 for d in detailed_rows if d.get('diff_type') == 'pg_only')
        ch_only_count = sum(
            1 for d in detailed_rows if d.get('diff_type') == 'ch_only')

        # Build output structure
        diff_data = {
            'metadata': {
                'table': table_name,
                'schema': pg_schema,
                'database_ch': ch_database,
                'run_timestamp': run_timestamp.isoformat() if run_timestamp else None,
                'diff_timestamp': datetime.now(timezone.utc).isoformat(),
                'pk_column': pk_column,
                'pk_range': [id_min, id_max],
                'binary_search_config': {
                    'num_chunks': num_chunks,
                    'max_depth': max_depth,
                    'max_divergent_rows': max_divergent_rows,
                    'per_row_threshold': per_row_threshold,
                },
                'binary_search_stats': stats,
            },
            'divergent_rows': detailed_rows,
            'skipped_chunks': skipped_chunks,
            'summary': {
                'total_divergent': len(detailed_rows),
                'modified': modified_count,
                'pg_only': pg_only_count,
                'ch_only': ch_only_count,
                'truncated': len(found_rows) >= max_divergent_rows,
                'skipped_chunks': len(skipped_chunks),
            },
        }

        # Write diff file
        diff_file = _write_diff_file(
            table_name, diff_data, output_dir, output_format,
            run_timestamp or datetime.now(timezone.utc))

        logging.info(
            "AUTO_DIFF: [%s] diff file written: %s",
            table_name, diff_file)

        return {
            'diff_file': diff_file,
            'divergent_rows': detailed_rows,
            'stats': stats,
        }

    finally:
        ch_conn.close()
