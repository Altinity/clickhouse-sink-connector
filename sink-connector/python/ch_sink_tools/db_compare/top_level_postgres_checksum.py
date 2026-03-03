#!/usr/bin/env python3
# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : top_level_postgres_checksum
# -- Date         :
# -- Summary      : Orchestrate PostgreSQL → ClickHouse periodic checksum
# --                verification for the awacs-qa CDC replication pipeline.
# --
# --                Mirrors top_level_table_checksum.py (MySQL version) but:
# --                  - Source is PostgreSQL (psycopg2 via db.postgres)
# --                  - Uses LSN-wait instead of FLUSH TABLE WITH READ LOCK
# --                  - Three-tier checksum: Tier-1 (full MD5), Tier-2 (PK MD5),
# --                    Tier-3 (count + max metrics only)
# --                  - ClickHouse queries always use FINAL + is_deleted=0
# --
# -- Usage        : python3 top_level_postgres_checksum.py --config config.yml
# --                        [--table TABLE] [--no-checksum] [--verbose]
# --
"""
import yaml
import sys
import os
import argparse
import logging
import traceback
import json
import time
import hashlib
import signal
import concurrent.futures
try:
    from urllib.request import urlopen, Request
    from urllib.error import URLError, HTTPError
except ImportError:
    from urllib2 import urlopen, Request, URLError, HTTPError
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any, Tuple

from ch_sink_tools.db.postgres import (
    get_postgres_connection,
    execute_pg,
    get_tables,
    get_table_columns,
    get_table_pk,
    get_table_row_count,
    get_current_lsn,
    get_standby_lsn,
    resolve_credentials_from_pgpass,
    pause_wal_replay,
    resume_wal_replay,
    is_in_recovery,
    is_wal_replay_paused,
)
from ch_sink_tools.db.clickhouse import (
    clickhouse_connection,
    execute_sql,
    resolve_credentials_from_config,
)
from ch_sink_tools.db_compare.postgres_table_checksum import (
    get_postgres_table_checksum,
    build_pg_select_expression,
)
from ch_sink_tools.db_compare._expressions import _build_ch_col_expr

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_CH_EXCLUDE_COLUMNS = {'_version', 'is_deleted', '_is_deleted', '__is_deleted'}

# ---------------------------------------------------------------------------
# Result class (plain class for Python 3.6 compatibility — no dataclasses)
# ---------------------------------------------------------------------------

class ChecksumResult(object):
    """Holds the comparison result for a single table."""
    def __init__(self, table, tier, pg_count, ch_count, count_delta,
                 count_delta_pct, checksum_match, pg_checksum, ch_checksum,
                 pg_max_pk, ch_max_pk, pg_max_ts, ch_max_ts, status, detail=''):
        self.table = table
        self.tier = tier
        self.pg_count = pg_count
        self.ch_count = ch_count
        self.count_delta = count_delta
        self.count_delta_pct = count_delta_pct
        self.checksum_match = checksum_match   # None for Tier-3
        self.pg_checksum = pg_checksum
        self.ch_checksum = ch_checksum
        self.pg_max_pk = pg_max_pk
        self.ch_max_pk = ch_max_pk
        self.pg_max_ts = pg_max_ts
        self.ch_max_ts = ch_max_ts
        self.status = status   # 'PASS', 'WARN', 'FAIL', 'MISSING', 'ERROR'
        self.detail = detail


# ---------------------------------------------------------------------------
# YAML config parser
# ---------------------------------------------------------------------------

def parse_config(config_file: str) -> dict:
    """Load and return the YAML configuration dict."""
    try:
        with open(config_file, 'r') as f:
            config = yaml.safe_load(f)
        return config
    except FileNotFoundError:
        logging.error(f"Config file not found: {config_file}")
        sys.exit(1)
    except yaml.YAMLError as e:
        logging.error(f"YAML parse error: {e}")
        sys.exit(1)


def validate_config(config: dict) -> bool:
    """Basic sanity check on config structure."""
    required_paths = [
        ('source', 'postgres', 'host'),
        ('clickhouse', 'host'),
        ('clickhouse', 'database'),
        ('connector', 'offset_db'),
        ('connector', 'offset_table'),
    ]
    for path in required_paths:
        d = config
        for key in path:
            if not isinstance(d, dict) or key not in d:
                logging.error(f"Missing config key: {' → '.join(path)}")
                return False
            d = d[key]
    return True


# ---------------------------------------------------------------------------
# LSN wait logic
# ---------------------------------------------------------------------------

def wait_for_ch_lsn(ch_conn, offset_db: str, offset_table: str,
                     target_lsn_int: int,
                     max_wait_seconds: int = 300,
                     poll_interval: int = 10,
                     tolerance_bytes: int = 0) -> bool:
    """
    Poll the ClickHouse offset table until the stored LSN integer
    is >= target_lsn_int (minus tolerance_bytes).

    The offset table stores a JSON string in `offset_val` with:
      - `lsn`     : full 64-bit integer (high_segment*2^32 + low_segment)

    Parameters
    ----------
    tolerance_bytes : int
        When > 0, accept ch_lsn >= (target_lsn_int - tolerance_bytes).
        This is used with wal_replay_pause=true where a small gap between
        the standby's physical WAL replay position and the logical
        replication slot's consumed position is expected and harmless.

    Returns
    -------
    bool
        True if CH reached target LSN (within tolerance) before timeout,
        False otherwise.
    """
    effective_target = target_lsn_int - tolerance_bytes
    full_table = f"{offset_db}.{offset_table}"
    deadline = time.time() + max_wait_seconds
    logged_first = False

    if tolerance_bytes > 0:
        logging.info(
            f"LSN wait tolerance: {tolerance_bytes} bytes "
            f"(effective_target={effective_target}, original={target_lsn_int})"
        )

    while time.time() < deadline:
        try:
            # Fetch the highest LSN from the offset table.
            # Using MAX on lsn ensures a stale bootstrap row with a low lsn
            # value never hides a newer, higher-lsn row.
            sql = f"""
                SELECT
                    max(toInt64OrZero(JSONExtractRaw(offset_val, 'lsn'))) AS ch_lsn
                FROM {full_table} FINAL
                WHERE isValidJSON(offset_val) AND JSONHas(offset_val, 'lsn')
            """
            (rows, cnt) = execute_sql(ch_conn, sql)
            if cnt > 0 and rows[0][0] is not None:
                try:
                    ch_lsn = int(rows[0][0])
                except (TypeError, ValueError):
                    ch_lsn = 0

                lag = target_lsn_int - ch_lsn
                if ch_lsn >= effective_target:
                    if ch_lsn >= target_lsn_int:
                        logging.info(
                            f"ClickHouse caught up exactly: ch_lsn={ch_lsn} >= target={target_lsn_int}"
                        )
                    else:
                        logging.info(
                            f"ClickHouse within tolerance: ch_lsn={ch_lsn}, "
                            f"target={target_lsn_int}, residual_lag={lag} bytes "
                            f"(<= tolerance={tolerance_bytes})"
                        )
                    return True

                if not logged_first:
                    logging.info(
                        f"Waiting for ClickHouse to catch up: "
                        f"ch_lsn={ch_lsn}, target={target_lsn_int}, lag_bytes={lag}"
                    )
                    logged_first = True
                else:
                    logging.info(
                        f"  still waiting: ch_lsn={ch_lsn}, lag_bytes={lag}, "
                        f"remaining={int(deadline - time.time())}s"
                    )
            else:
                logging.warning(f"No rows in offset table {full_table}")
        except Exception as e:
            logging.warning(f"LSN poll error: {e}")

        time.sleep(poll_interval)

    logging.warning(
        f"Timeout after {max_wait_seconds}s: ClickHouse may still be behind. "
        f"Running checksum anyway — results may show false positives."
    )
    return False


# ---------------------------------------------------------------------------
# ClickHouse column discovery
# ---------------------------------------------------------------------------

def get_ch_columns(ch_conn, ch_database: str, table_name: str,
                    exclude_columns: set) -> List[str]:
    """
    Return the ordered list of column names for a ClickHouse table,
    excluding CDC-internal columns (_version, is_deleted, etc.).
    """
    sql = f"""
        SELECT name
        FROM system.columns
        WHERE database = '{ch_database}'
          AND table = '{table_name}'
          AND name NOT IN ({', '.join(repr(c) for c in exclude_columns)})
        ORDER BY position
    """
    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        return [r[0] for r in rows]
    except Exception as e:
        logging.error(f"Error getting CH columns for {ch_database}.{table_name}: {e}")
        return []


def get_ch_columns_meta(ch_conn, ch_database: str, table_name: str,
                         exclude_columns: set) -> List[Dict[str, Any]]:
    """
    Return column metadata from ClickHouse system.columns for checksum
    computation in Phase A (when PG metadata is not available).

    Each dict has keys: 'column_name', 'ch_type', 'pg_type', 'nullable'
    The 'pg_type' is inferred from the CH type to produce compatible
    expressions via _build_ch_col_expr().

    Excludes CDC-internal columns (_version, is_deleted, etc.).
    """
    sql = f"""
        SELECT name, type
        FROM system.columns
        WHERE database = '{ch_database}'
          AND table = '{table_name}'
          AND name NOT IN ({', '.join(repr(c) for c in exclude_columns)})
        ORDER BY position
    """
    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        result = []
        for r in rows:
            col_name = r[0]
            ch_type = r[1]
            is_nullable = ch_type.startswith('Nullable(')
            # Map CH type → approximate PG type for _build_ch_col_expr()
            pg_type = _ch_type_to_pg_type(ch_type)
            result.append({
                'column_name': col_name,
                'ch_type': ch_type,
                'pg_type': pg_type,
                'nullable': is_nullable,
            })
        return result
    except Exception as e:
        logging.error(f"Error getting CH columns meta for {ch_database}.{table_name}: {e}")
        return []


def _ch_type_to_pg_type(ch_type: str) -> str:
    """
    Map a ClickHouse column type string to an approximate PostgreSQL type
    string, for use with _build_ch_col_expr() which selects normalisation
    based on pg_type.

    This mapping only needs to cover the cases that _build_ch_col_expr()
    checks: boolean, timestamptz, json/jsonb, and 'everything else'.
    Float/bytea/range exclusion is handled separately.
    """
    # Strip Nullable wrapper
    t = ch_type
    if t.startswith('Nullable(') and t.endswith(')'):
        t = t[9:-1]

    t_lower = t.lower()

    # UInt8 → boolean (Debezium maps PG boolean to CH UInt8)
    if t_lower == 'uint8':
        return 'boolean'

    # DateTime64 with timezone → timestamp with time zone
    if 'datetime64' in t_lower and "'" in t:
        # e.g. DateTime64(6, 'UTC') → timestamptz
        return 'timestamp with time zone'
    if 'datetime64' in t_lower:
        return 'timestamp without time zone'
    if t_lower == 'datetime':
        return 'timestamp without time zone'

    # Date / Date32
    if t_lower in ('date', 'date32'):
        return 'date'

    # Float types → float (will be excluded by include_floating_point check)
    if t_lower in ('float32', 'float64'):
        return 'double precision'

    # String → could be json/text/varchar/bytea — default to text
    # (json exclusion is handled by include_json flag on the column name)
    if t_lower == 'string':
        return 'text'

    # Int types
    if 'int' in t_lower:
        return 'integer'

    # Decimal
    if 'decimal' in t_lower:
        return 'numeric'

    # UUID
    if t_lower == 'uuid':
        return 'uuid'

    # Array types
    if t_lower.startswith('array('):
        return 'text[]'

    # Default: text (safe — toString() will be used)
    return 'text'


def ch_table_exists(ch_conn, ch_database: str, table_name: str) -> bool:
    """Check whether a table exists in ClickHouse."""
    sql = f"""
        SELECT count() FROM system.tables
        WHERE database = '{ch_database}' AND name = '{table_name}'
    """
    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        return rows[0][0] > 0
    except Exception:
        return False


# ---------------------------------------------------------------------------
# ClickHouse count (Tier-1/2/3)
# ---------------------------------------------------------------------------

def get_ch_count(ch_conn, ch_database: str, table_name: str) -> int:
    """
    Exact count from ClickHouse using FINAL + is_deleted=0.
    No _version filter — relies on querying CH immediately after LSN catch-up
    to minimise CDC drift.
    """
    sql = f"""
        SELECT count()
        FROM `{ch_database}`.`{table_name}` FINAL
        WHERE is_deleted = 0
        SETTINGS do_not_merge_across_partitions_select_final = 1
    """
    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        return int(rows[0][0]) if rows else 0
    except Exception as e:
        logging.error(f"CH count error for {ch_database}.{table_name}: {e}")
        return -1


# ---------------------------------------------------------------------------
# ClickHouse Tier-3 metrics (count + max_pk + max_updated_at)
# ---------------------------------------------------------------------------

def get_ch_tier3_metrics(ch_conn, ch_database: str, table_name: str,
                          pk_col: Optional[str],
                          has_updated_at: bool) -> Dict[str, Any]:
    """
    Tier-3 metrics from ClickHouse: count, max_pk, max_updated_at.
    Always uses FINAL + is_deleted=0.
    No _version filter — relies on querying CH immediately after LSN catch-up.
    """
    selects = ["count() AS cnt"]
    if pk_col:
        selects.append(f'max("{pk_col}") AS max_pk')
    if has_updated_at:
        selects.append('max("updated_at") AS max_updated_at')

    sql = f"""
        SELECT {', '.join(selects)}
        FROM `{ch_database}`.`{table_name}` FINAL
        WHERE is_deleted = 0
        SETTINGS do_not_merge_across_partitions_select_final = 1,
                 max_threads = 4
    """
    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        if not rows:
            return {'count': 0, 'max_pk': None, 'max_updated_at': None}
        row = rows[0]
        result = {'count': int(row[0])}
        idx = 1
        if pk_col:
            result['max_pk'] = row[idx]
            idx += 1
        else:
            result['max_pk'] = None
        if has_updated_at:
            result['max_updated_at'] = row[idx]
        else:
            result['max_updated_at'] = None
        return result
    except Exception as e:
        logging.error(f"CH Tier-3 metrics error for {ch_database}.{table_name}: {e}")
        return {'count': -1, 'max_pk': None, 'max_updated_at': None}


# ---------------------------------------------------------------------------
# PostgreSQL Tier-3 metrics
# ---------------------------------------------------------------------------

def get_pg_tier3_metrics(pg_conn, pg_schema: str, table_name: str,
                          pk_col: Optional[str],
                          has_updated_at: bool) -> Dict[str, Any]:
    """
    Tier-3 metrics from PostgreSQL: exact count, max_pk, max_updated_at.
    Uses index-efficient aggregates — no full scan needed.
    """
    selects = ["COUNT(*) AS cnt"]
    if pk_col:
        selects.append(f'max("{pk_col}") AS max_pk')
    if has_updated_at:
        selects.append('max("updated_at") AS max_updated_at')

    sql = f"""
        SELECT {', '.join(selects)}
        FROM "{pg_schema}"."{table_name}"
    """
    try:
        rows = execute_pg(pg_conn, sql)
        if not rows:
            return {'count': 0, 'max_pk': None, 'max_updated_at': None}
        row = rows[0]
        result = {'count': int(row['cnt'])}
        result['max_pk'] = row.get('max_pk') if pk_col else None
        result['max_updated_at'] = row.get('max_updated_at') if has_updated_at else None
        return result
    except Exception as e:
        logging.error(f"PG Tier-3 metrics error for {pg_schema}.{table_name}: {e}")
        return {'count': -1, 'max_pk': None, 'max_updated_at': None}


# ---------------------------------------------------------------------------
# ClickHouse Tier-1/2 checksum
# ---------------------------------------------------------------------------
# _build_ch_col_expr is imported from ch_sink_tools.db_compare._expressions
# (see import block at top of file)
# ---------------------------------------------------------------------------


def get_ch_checksum(ch_conn, ch_database: str, table_name: str,
                     col_names: List[str], tier: int,
                     pk_col: Optional[str] = None,
                     columns_meta: Optional[List[Dict]] = None,
                     max_memory_usage: int = 80000000000) -> Optional[str]:
    """
    Compute the Tier-1 (full column MD5) or Tier-2 (PK-list MD5) checksum
    from ClickHouse using FINAL + is_deleted=0.

    The concat format matches postgres_table_checksum.py exactly:
      concat_ws(char(1), expr1, expr2, ...) + null_indicator_suffix

    columns_meta: list of dicts with keys 'column_name', 'pg_type', 'nullable'
                  If provided, used to build type-aware CH expressions.

    No _version filter — relies on querying CH immediately after LSN catch-up.
    """
    if tier == 1:
        if not col_names:
            logging.warning("No columns for CH Tier-1 checksum of %s.%s", ch_database, table_name)
            return None

        # Build column expressions, matching PG side normalizations
        # Use columns_meta if available for type-aware expressions
        meta_by_name = {}
        if columns_meta:
            meta_by_name = {c['column_name']: c for c in columns_meta}

        parts = []          # concat_ws arguments (stable text per column)
        null_indicators = [] # only for actually-nullable columns (matches PG)
        nullable_cols = []

        for col in col_names:
            meta = meta_by_name.get(col)
            if meta:
                pg_type = meta.get('pg_type', '')
                is_nullable = meta.get('nullable', True)
            else:
                pg_type = ''
                is_nullable = True  # safe default

            expr = _build_ch_col_expr(col, pg_type, is_nullable)
            parts.append(expr)
            if is_nullable:
                nullable_cols.append(f'"{col}"')

        if not parts:
            return None

        # Mirror PG: concat_ws(chr(1), v1, v2, ...) + chr(1) + null_bits (if any nullable cols)
        concat_expr = "concat_ws(char(1), " + ", ".join(parts) + ")"
        if nullable_cols:
            null_bits = " || ".join(
                f"(case when {c} is null then '1' else '0' end)"
                for c in nullable_cols
            )
            concat_expr = concat_expr + " || char(1) || " + null_bits

        select_expr = concat_expr

    elif tier == 2:
        if not pk_col:
            logging.warning("No PK for CH Tier-2 checksum of %s.%s", ch_database, table_name)
            return None
        select_expr = f'toString("{pk_col}")'
    else:
        return None

    where = "is_deleted = 0"

    sql = f"""
        SELECT
            count(*) AS cnt,
            coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash,  1, 8))))), 0) AS a,
            coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash,  9, 8))))), 0) AS b,
            coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))), 0) AS c,
            coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))), 0) AS d
        FROM (
            SELECT hex(MD5({select_expr})) AS hash
            FROM `{ch_database}`.`{table_name}` FINAL
            WHERE {where}
        ) t
        SETTINGS do_not_merge_across_partitions_select_final = 1,
                 max_memory_usage = {max_memory_usage}
    """

    try:
        (rows, cnt) = execute_sql(ch_conn, sql)
        if not rows or rows[0][0] == 0:
            # Empty table: return the well-known MD5 of the zero-tuple
            md5_input = '#'.join(['0', '0', '0', '0', '0']) + '#'
            m = hashlib.md5()
            m.update(md5_input.encode('utf-8'))
            return m.hexdigest()

        row = rows[0]
        values = (int(row[0] or 0), int(row[1] or 0), int(row[2] or 0),
                  int(row[3] or 0), int(row[4] or 0))
        md5_input = '#'.join(str(x) for x in values) + '#'
        m = hashlib.md5()
        m.update(md5_input.encode('utf-8'))
        return m.hexdigest()
    except Exception as e:
        logging.error(f"CH checksum error for {ch_database}.{table_name}: {e}")
        return None


# ---------------------------------------------------------------------------
# Per-table comparison logic
# ---------------------------------------------------------------------------

def compare_table(table_name: str,
                  pg_host, pg_user, pg_password, pg_port, pg_database, pg_schema,
                  ch_host, ch_user, ch_password, ch_port, ch_database, ch_secure,
                  ch_exclude_columns: set,
                  tier1_max_rows: int,
                  tier2_max_rows: int,
                  chunk_size: int,
                  threads_per_table: int,
                  alert_count_delta_pct: float,
                  alert_count_delta_abs: int,
                  include_floating_point: bool,
                  include_json: bool,
                  no_checksum: bool,
                  pg_snapshot_conn=None,
                  skip_columns: Optional[List[str]] = None,
                  ch_precomputed: Optional[Dict[str, Any]] = None) -> ChecksumResult:
    """
    Full comparison for a single table.

    When pg_snapshot_conn is provided (snapshot_mode=True), all PG queries
    run on that shared REPEATABLE READ connection instead of opening a new one.
    The caller is responsible for the transaction lifecycle (BEGIN/COMMIT).

    When pg_snapshot_conn is None (legacy mode), opens its own PG connection
    per call — behavior identical to the original implementation.

    When ch_precomputed is provided (snapshot_mode Phase A/B), CH count,
    tier3 metrics, and checksum have already been computed in Phase A.
    The dict keys are: 'count', 'tier3', 'checksum', 'checksum_tier'.
    'checksum_tier' indicates which tier the precomputed checksum was built
    for (1 or 2). The precomputed checksum is only used when Phase B's
    tier matches checksum_tier; otherwise CH is re-queried on-the-fly.
    When None, CH queries are computed on-the-fly (legacy mode).

    Steps:
      1. Check CH table exists
      2. Get approximate PG row count → select tier
      3. Get exact PG count + CH count (from precomputed or on-the-fly)
      4. Run tier-appropriate checksum (unless no_checksum or Tier-3)
      5. Analyse differences and return ChecksumResult
    """
    # Determine whether we own the PG connection (must close it) or share it
    own_pg_conn = (pg_snapshot_conn is None)

    try:
        # --- Open connections ---
        if own_pg_conn:
            pg_conn = get_postgres_connection(
                pg_host, pg_user, pg_password, pg_port, pg_database)
        else:
            pg_conn = pg_snapshot_conn

        ch_conn = clickhouse_connection(
            ch_host, database=ch_database,
            user=ch_user, password=ch_password,
            port=ch_port, secure=ch_secure,
        )

        try:
            # --- 1. Check CH table exists ---
            if not ch_table_exists(ch_conn, ch_database, table_name):
                return ChecksumResult(
                    table=table_name, tier=0,
                    pg_count=-1, ch_count=-1, count_delta=0,
                    count_delta_pct=0.0, checksum_match=None,
                    pg_checksum=None, ch_checksum=None,
                    pg_max_pk=None, ch_max_pk=None,
                    pg_max_ts=None, ch_max_ts=None,
                    status='MISSING',
                    detail=f'Table {ch_database}.{table_name} not found in ClickHouse',
                )

            # --- 2. Approximate row count → tier selection ---
            approx_rows = get_table_row_count(pg_conn, pg_schema, table_name)
            # Fall back to exact count if stats unavailable
            if approx_rows < 0:
                rows_for_tier = execute_pg(
                    pg_conn, f'SELECT COUNT(*) AS cnt FROM "{pg_schema}"."{table_name}"')
                approx_rows = int(rows_for_tier[0]['cnt']) if rows_for_tier else 0

            # Tier selection – force Tier-1 (full column MD5) for ALL tables
            # regardless of size.  Correctness matters for large tables too.
            tier = 1

            # --- 3. Get PK and column metadata ---
            pk_columns = get_table_pk(pg_conn, pg_schema, table_name)
            pk_col = pk_columns[0] if pk_columns else None
            columns_meta = get_table_columns(pg_conn, pg_schema, table_name)

            # Check for updated_at column
            col_names_pg = {c['column_name'] for c in columns_meta}
            has_updated_at = 'updated_at' in col_names_pg

            # --- 4. Get CH column list (excluding CDC-internal columns) ---
            ch_col_names = get_ch_columns(ch_conn, ch_database, table_name, ch_exclude_columns)

            # Build skip set: per-table columns to exclude from checksum (not count)
            skip_set = set(skip_columns) if skip_columns else set()
            if skip_set:
                logging.info(
                    f"[{table_name}] Skipping columns from checksum: {sorted(skip_set)}"
                )

            # For checksum, use only columns that exist in BOTH PG and CH,
            # and not in the per-table skip list.
            pg_col_names = [c['column_name'] for c in columns_meta]
            shared_col_names = [c for c in pg_col_names
                                if c in set(ch_col_names) and c not in skip_set]

            logging.info(
                f"[{table_name}] TIER={tier} approx_rows={approx_rows:,} "
                f"pk={pk_col} pg_cols={len(pg_col_names)} shared_cols={len(shared_col_names)}"
            )

            # --- Tier-3: count + max metrics only ---
            if tier == 3:
                pg_metrics = get_pg_tier3_metrics(pg_conn, pg_schema, table_name,
                                                    pk_col, has_updated_at)
                if ch_precomputed:
                    ch_metrics = ch_precomputed['tier3']
                else:
                    ch_metrics = get_ch_tier3_metrics(ch_conn, ch_database, table_name,
                                                        pk_col, has_updated_at)
                pg_cnt = pg_metrics['count']
                ch_cnt = ch_metrics['count']
                count_delta = ch_cnt - pg_cnt
                count_delta_pct = abs(count_delta) / pg_cnt if pg_cnt > 0 else 0.0
                count_fail = (abs(count_delta) > alert_count_delta_abs or
                              count_delta_pct > alert_count_delta_pct)
                status = 'FAIL' if count_fail else 'PASS'

                # Max PK check
                pg_max_pk = pg_metrics.get('max_pk')
                ch_max_pk = ch_metrics.get('max_pk')
                max_pk_mismatch = (pg_max_pk is not None and ch_max_pk is not None and
                                   str(pg_max_pk) != str(ch_max_pk))
                if max_pk_mismatch:
                    status = 'FAIL'

                detail = f"SKIP(large>Tier3)"
                logging.info(
                    f"[{table_name}] TIER=3 PG={pg_cnt:,} CH={ch_cnt:,} "
                    f"DELTA={count_delta} MAX_PK=PG:{pg_max_pk}/CH:{ch_max_pk} STATUS={status}"
                )
                return ChecksumResult(
                    table=table_name, tier=3,
                    pg_count=pg_cnt, ch_count=ch_cnt,
                    count_delta=count_delta, count_delta_pct=count_delta_pct,
                    checksum_match=None,
                    pg_checksum=None, ch_checksum=None,
                    pg_max_pk=pg_max_pk, ch_max_pk=ch_max_pk,
                    pg_max_ts=pg_metrics.get('max_updated_at'),
                    ch_max_ts=ch_metrics.get('max_updated_at'),
                    status=status, detail=detail,
                )

            # --- Tier-1 / Tier-2: exact count + checksum ---
            # PG exact count
            pg_count_rows = execute_pg(
                pg_conn, f'SELECT COUNT(*) AS cnt FROM "{pg_schema}"."{table_name}"')
            pg_cnt = int(pg_count_rows[0]['cnt']) if pg_count_rows else 0

            # CH exact count (from precomputed or on-the-fly)
            if ch_precomputed:
                ch_cnt = ch_precomputed['count']
            else:
                ch_cnt = get_ch_count(ch_conn, ch_database, table_name)

            count_delta = ch_cnt - pg_cnt
            count_delta_pct = abs(count_delta) / pg_cnt if pg_cnt > 0 else 0.0
            count_fail = (abs(count_delta) > alert_count_delta_abs or
                          count_delta_pct > alert_count_delta_pct)

            pg_checksum = None
            ch_checksum = None
            checksum_match = None

            if not no_checksum:
                # --- PG checksum ---
                # When using snapshot_mode, pg_snapshot_conn is passed so all
                # chunk queries execute within the same REPEATABLE READ transaction.
                pg_checksum = get_postgres_table_checksum(
                    conn=pg_conn,
                    table_name=table_name,
                    columns_meta=[c for c in columns_meta
                                  if c['column_name'] in set(shared_col_names)],
                    pk_columns=pk_columns,
                    schema=pg_schema,
                    chunk_size=chunk_size,
                    where=None,
                    tier=tier,
                    include_floating_point=include_floating_point,
                    include_json=include_json,
                    excluded_columns=[],
                    debug_output=False,
                    debug_limit=None,
                    pg_host=pg_host,
                    pg_user=pg_user,
                    pg_password=pg_password,
                    pg_port=pg_port,
                    pg_database=pg_database,
                    threads_per_table=threads_per_table,
                    snapshot_conn=pg_snapshot_conn,
                )

                # --- CH checksum ---
                # Filter shared_col_names to only those that passed PG filtering
                # (exclude float, json cols excluded on PG side → exclude on CH side too)
                pg_included_cols = []
                for c in columns_meta:
                    if c['column_name'] not in set(shared_col_names):
                        continue
                    pg_t = c['pg_type'].lower()
                    if not include_floating_point and pg_t in ('real', 'float4', 'double precision', 'float8', 'float'):
                        continue
                    if not include_json and pg_t in ('json', 'jsonb'):
                        continue
                    # Skip bytea: Debezium encodes as Base64, dump uses \x hex.
                    # TODO: fix at ingestion time and then enable bytea checksumming.
                    if pg_t == 'bytea':
                        continue
                    # Skip range types: Debezium uses UTC, dump uses session-TZ.
                    # TODO: fix at ingestion time (tstzrange normalization), then enable.
                    udt_n = c.get('udt_name', '').lower()
                    if pg_t in ('tstzrange', 'tsrange', 'daterange', 'int4range', 'int8range', 'numrange') \
                            or udt_n in ('tstzrange', 'tsrange', 'daterange', 'int4range', 'int8range', 'numrange'):
                        continue
                    pg_included_cols.append(c['column_name'])

                # Build columns_meta for only the included columns so CH can
                # use type-aware expressions matching PG normalizations.
                included_meta = [
                    c for c in columns_meta
                    if c['column_name'] in set(pg_included_cols)
                ]
                # Use precomputed CH checksum from Phase A if available AND
                # the tier matches (Phase A estimated tier from ch_count).
                # If the tier doesn't match, fall back to on-the-fly query.
                precomputed_tier = (ch_precomputed.get('checksum_tier')
                                    if ch_precomputed else None)
                if (ch_precomputed
                        and ch_precomputed.get('checksum') is not None
                        and precomputed_tier == tier):
                    ch_checksum = ch_precomputed['checksum']
                    logging.info(
                        f"[{table_name}] Using precomputed CH Tier-{tier} "
                        f"checksum from Phase A: {ch_checksum}"
                    )
                else:
                    if (ch_precomputed
                            and ch_precomputed.get('checksum') is not None
                            and precomputed_tier != tier):
                        logging.info(
                            f"[{table_name}] Phase A precomputed tier "
                            f"({precomputed_tier}) != Phase B tier ({tier}); "
                            f"re-querying CH on-the-fly"
                        )
                    ch_checksum = get_ch_checksum(
                        ch_conn, ch_database, table_name,
                        col_names=pg_included_cols,
                        tier=tier,
                        pk_col=pk_col,
                        columns_meta=included_meta,
                    )

                if pg_checksum is not None and ch_checksum is not None:
                    checksum_match = (pg_checksum == ch_checksum)
                else:
                    checksum_match = None

            # --- Determine status ---
            if checksum_match is False or count_fail:
                status = 'FAIL'
            elif count_delta != 0 or checksum_match is False:
                status = 'WARN'
            else:
                status = 'PASS'

            cksum_label = 'MATCH' if checksum_match else ('MISMATCH' if checksum_match is False else 'SKIP')
            logging.info(
                f"[{table_name}] TIER={tier} PG={pg_cnt:,} CH={ch_cnt:,} "
                f"DELTA={count_delta} DELTA_PCT={count_delta_pct:.4%} "
                f"CHECKSUM={cksum_label} STATUS={status}"
            )

            return ChecksumResult(
                table=table_name, tier=tier,
                pg_count=pg_cnt, ch_count=ch_cnt,
                count_delta=count_delta, count_delta_pct=count_delta_pct,
                checksum_match=checksum_match,
                pg_checksum=pg_checksum, ch_checksum=ch_checksum,
                pg_max_pk=None, ch_max_pk=None,
                pg_max_ts=None, ch_max_ts=None,
                status=status,
                detail='',
            )

        finally:
            # Only close PG connection if we opened it (not if it's the shared snapshot conn)
            if own_pg_conn and pg_conn:
                pg_conn.close()
            ch_conn.close()

    except Exception as e:
        logging.error(f"[{table_name}] Exception: {e}")
        logging.error(traceback.format_exc())
        return ChecksumResult(
            table=table_name, tier=0,
            pg_count=-1, ch_count=-1, count_delta=0, count_delta_pct=0.0,
            checksum_match=None, pg_checksum=None, ch_checksum=None,
            pg_max_pk=None, ch_max_pk=None, pg_max_ts=None, ch_max_ts=None,
            status='ERROR', detail=str(e),
        )


# ---------------------------------------------------------------------------
# Summary printing
# ---------------------------------------------------------------------------

def print_summary(results: List[ChecksumResult], run_start: datetime,
                   run_end: datetime, lsn_str: str, lsn_int: int,
                   pg_database: str, ch_database: str) -> None:
    """Print a formatted summary table to stdout."""
    HEADER = (
        f"\n{'='*80}\n"
        f"=== PostgreSQL → ClickHouse Checksum Summary ===\n"
        f"    Source  : {pg_database}\n"
        f"    Replica : {ch_database}\n"
        f"    LSN     : {lsn_str} (int={lsn_int})\n"
        f"    Run     : {run_start.strftime('%Y-%m-%dT%H:%M:%SZ')} → "
        f"{run_end.strftime('%Y-%m-%dT%H:%M:%SZ')} "
        f"({int((run_end-run_start).total_seconds())}s)\n"
        f"{'='*80}"
    )
    print(HEADER)

    col_w = max(30, max(len(r.table) for r in results) + 2) if results else 30
    hdr = (
        f"{'Table':<{col_w}} {'Tier':>4}  {'PG Count':>12}  {'CH Count':>12}  "
        f"{'Delta':>8}  {'Delta%':>8}  {'Checksum':>10}  {'Status':>6}"
    )
    sep = '-' * len(hdr)
    print(hdr)
    print(sep)

    fail_count = 0
    for r in sorted(results, key=lambda x: x.table):
        ck = 'N/A'
        if r.tier == 3:
            ck = 'N/A'
        elif r.checksum_match is True:
            ck = 'MATCH'
        elif r.checksum_match is False:
            ck = 'MISMATCH'
        elif r.status == 'MISSING':
            ck = 'MISSING'
        else:
            ck = 'SKIP'

        pg_c = f'{r.pg_count:,}' if r.pg_count >= 0 else '-'
        ch_c = f'{r.ch_count:,}' if r.ch_count >= 0 else '-'
        delta = f'{r.count_delta:+,}' if r.pg_count >= 0 else '-'
        pct = f'{r.count_delta_pct:.4%}' if r.pg_count >= 0 else '-'

        print(
            f"{r.table:<{col_w}} {r.tier:>4}  {pg_c:>12}  {ch_c:>12}  "
            f"{delta:>8}  {pct:>8}  {ck:>10}  {r.status:>6}"
        )
        if r.status in ('FAIL', 'MISSING', 'ERROR'):
            fail_count += 1
        if r.detail:
            print(f"  ↳ {r.detail}")

    print(sep)
    total = len(results)
    if fail_count == 0:
        print(f"\nRESULT: PASS — all {total} tables match")
    else:
        print(f"\nRESULT: FAIL — {fail_count} of {total} tables have mismatches")

    print(f"Exit code: {'1' if fail_count > 0 else '0'}\n")


# ---------------------------------------------------------------------------
# Main orchestrator
# ---------------------------------------------------------------------------

def run_config(config: dict, args) -> None:
    """
    Main orchestration flow (snapshot_mode=True — correct ordering):

      0. Pause WAL replay on the standby (if enabled).
         Freezes the standby at a known LSN so PG queries see a consistent
         point-in-time snapshot.
      1. Open PG connection + BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ
         The REPEATABLE READ is opened on the frozen standby.
      2. Read pg_snap_lsn = pg_last_wal_replay_lsn() from INSIDE the open tx.
         This LSN is the exact WAL position that PG's snapshot reflects.
      3. Discover tables (on the same open connection, inside the transaction).
      4. Connect to CH -> wait for CH offset LSN >= pg_snap_lsn.
         The connector keeps running during this wait so it can advance
         CH's LSN to match the standby's frozen LSN.
      4b. Flush/pause the sink connector (immediately after LSN catch-up).
          Calls the connector's /flush API to drain any in-flight batch
          and pause processing. This prevents "future" events (beyond the
          standby's frozen LSN) from appearing in CH during the checksum.
          Wait stabilize_wait seconds for the flush to fully drain.
      5. Phase A: Query ALL CH tables in parallel (~1-2 min).
         Connector is paused, so no new writes arrive during this window.
      6. Phase B: Query PG tables serially on the REPEATABLE READ connection
         (~15 min). PG data is frozen so ordering doesn't matter.
      7. COMMIT PG transaction + close connection.
      8. Resume WAL replay (in finally block).
      9. Resume connector (in finally block, AFTER WAL resume).

    Correctness guarantee:
      WAL replay is paused (Step 0) — standby is frozen.
      PG snapshot is pinned at pg_snap_lsn (Step 1-2).
      CH has processed all WAL events up to pg_snap_lsn (Step 4).
      Connector is paused after catch-up (Step 4b) — no new writes to CH.
      Both CH and PG reflect the same logical state for checksumming.

    Legacy mode (snapshot_mode=False):
      Each table opens its own PG connection per thread. No REPEATABLE READ.
      LSN is read first (old order), then CH waits, then tables run.

    lsn_encoding (config.checksum.lsn_encoding):
      Documented for the operator; not used in computation.
      'full64' = confirmed: offset_val lsn is high_segment*2^32 + low_segment.
    """
    pg_cfg = config['source']['postgres']
    ch_cfg = config['clickhouse']
    conn_cfg = config.get('connector', {})
    cksum_cfg = config.get('checksum', {})

    # --- Resolve PG credentials ---
    pg_host = pg_cfg['host']
    pg_port = int(pg_cfg.get('port', 5432))
    pg_database = pg_cfg['database']
    pg_schema = pg_cfg.get('schema', 'public')
    pg_user = pg_cfg.get('user')
    pg_password = pg_cfg.get('password')

    if not pg_user or not pg_password:
        pgpass_file = os.path.expanduser(pg_cfg.get('pgpass_file', '~/.pgpass'))
        (resolved_user, resolved_pw) = resolve_credentials_from_pgpass(pgpass_file)
        if not pg_user:
            pg_user = resolved_user
        if not pg_password:
            pg_password = resolved_pw

    # --- Resolve CH credentials ---
    ch_host = ch_cfg['host']
    ch_port = int(ch_cfg.get('port', 9000))
    ch_database = ch_cfg['database']
    ch_user = ch_cfg.get('user', 'default')
    ch_password = ch_cfg.get('password', '')
    ch_secure = bool(ch_cfg.get('secure', False))

    if not ch_password and 'config_file' in ch_cfg:
        config_file = ch_cfg['config_file']
        try:
            (ch_user, ch_password) = resolve_credentials_from_config(config_file)
        except Exception as e:
            logging.warning(f"Could not read CH config file {config_file}: {e}")

    # --- Offset table ---
    offset_db = conn_cfg.get('offset_db', 'altinity_sink_connector')
    offset_table = conn_cfg.get('offset_table', '')

    # --- Checksum parameters ---
    tier1_max_rows = int(cksum_cfg.get('medium_table_threshold', cksum_cfg.get('tier1_max_rows', 100000)))
    tier2_max_rows = int(cksum_cfg.get('large_table_threshold', cksum_cfg.get('tier2_max_rows', 10000000)))
    chunk_size = int(cksum_cfg.get('chunk_size', 100000))
    threads = int(cksum_cfg.get('threads', 4))
    threads_per_table = int(cksum_cfg.get('threads_per_table', 1))
    lsn_wait_timeout = int(cksum_cfg.get('lsn_wait_timeout_seconds', 300))
    lsn_poll_interval = int(cksum_cfg.get('lsn_wait_poll_interval_seconds', 10))
    alert_count_delta_pct = float(cksum_cfg.get('alert_count_delta_pct', 0.0001))
    alert_count_delta_abs = int(cksum_cfg.get('alert_count_delta_abs', 100))
    skip_tables = set(cksum_cfg.get('skip_tables', []))
    include_floating_point = bool(cksum_cfg.get('include_floating_point_columns', False))
    include_json = bool(cksum_cfg.get('include_json_columns', False))
    ch_exclude_columns = set(cksum_cfg.get('exclude_ch_columns', list(DEFAULT_CH_EXCLUDE_COLUMNS)))

    # --- snapshot_mode: enables REPEATABLE READ + parallel CH-first querying ---
    snapshot_mode = bool(cksum_cfg.get('snapshot_mode', False))

    # --- Per-table skip_columns: columns excluded from checksum (not count) ---
    # Config format (dict keyed by table name):
    #   skip_columns:
    #     alerts_alerttemplate:
    #       - highlighted_tags
    # OR the newer config key:
    #   skip_table_columns:
    #     alerts_alerttemplate:
    #       - highlighted_tags
    # Also supports skip_columns as a list (global column exclusions) — in that
    # case per-table lookups return None.
    skip_columns_raw = cksum_cfg.get('skip_table_columns') or cksum_cfg.get('skip_columns', {})
    skip_columns_cfg = skip_columns_raw if isinstance(skip_columns_raw, dict) else {}

    run_start = datetime.now(timezone.utc)

    # =========================================================================
    # SNAPSHOT MODE (correct ordering — PG first, then CH wait)
    # =========================================================================
    if snapshot_mode:
        # -----------------------------------------------------------------
        # WAL replay pause setup (opt-in via checksum.wal_replay_pause)
        # -----------------------------------------------------------------
        wal_pause_enabled = bool(cksum_cfg.get('wal_replay_pause', False))
        wal_was_paused_by_us = False
        wal_control_conn = None

        # -----------------------------------------------------------------
        # Sink connector flush/resume setup (opt-in via checksum.flush_connector)
        # Calls the connector's REST API /flush to drain buffered records
        # and pause writes, then /resume after checksum completes.
        # -----------------------------------------------------------------
        flush_connector_cfg = cksum_cfg.get('flush_connector', {})
        connector_flush_enabled = bool(flush_connector_cfg.get('enabled', False))
        connector_flushed = False
        connector_flush_url = flush_connector_cfg.get(
            'flush_url', 'http://localhost:7008/flush')
        connector_resume_url = flush_connector_cfg.get(
            'resume_url', 'http://localhost:7008/resume')
        connector_flush_timeout = int(flush_connector_cfg.get('timeout', 30))
        connector_stabilize_wait = int(flush_connector_cfg.get('stabilize_wait', 5))

        def _ensure_connector_resumed():
            """Safety: resume the sink connector if we flushed/paused it."""
            nonlocal connector_flushed
            if connector_flushed:
                try:
                    logging.info("FLUSH_CONNECTOR: Resuming connector via %s",
                                 connector_resume_url)
                    req = Request(connector_resume_url)
                    resp = urlopen(req, timeout=connector_flush_timeout)
                    body = resp.read().decode('utf-8')
                    logging.info("FLUSH_CONNECTOR: Resume response: %s", body)
                    connector_flushed = False
                except Exception as e:
                    logging.error(
                        "FLUSH_CONNECTOR: CRITICAL — Failed to resume "
                        "connector via %s: %s. MANUAL INTERVENTION REQUIRED "
                        "(call GET %s manually).",
                        connector_resume_url, e, connector_resume_url)

        def _ensure_wal_resumed():
            """Safety: resume WAL replay if we paused it."""
            nonlocal wal_was_paused_by_us, wal_control_conn
            if wal_was_paused_by_us and wal_control_conn:
                try:
                    resume_wal_replay(wal_control_conn)
                    logging.info("WAL_REPLAY_PAUSE: WAL replay RESUMED (cleanup)")
                    wal_was_paused_by_us = False
                except Exception as e:
                    logging.error(
                        "CRITICAL: Failed to resume WAL replay: %s. "
                        "MANUAL INTERVENTION REQUIRED: SELECT pg_wal_replay_resume();", e
                    )

        # Signal handlers — ensure WAL resume + connector resume on SIGTERM/SIGINT
        prev_sigterm = signal.getsignal(signal.SIGTERM)
        prev_sigint = signal.getsignal(signal.SIGINT)

        def _signal_handler(signum, frame):
            logging.warning(
                "Signal %d received, cleaning up before exit", signum)
            _ensure_wal_resumed()
            _ensure_connector_resumed()
            if signum == signal.SIGTERM and callable(prev_sigterm):
                prev_sigterm(signum, frame)
            elif signum == signal.SIGINT and callable(prev_sigint):
                prev_sigint(signum, frame)
            sys.exit(128 + signum)

        if wal_pause_enabled or connector_flush_enabled:
            signal.signal(signal.SIGTERM, _signal_handler)
            signal.signal(signal.SIGINT, _signal_handler)

        try:
            # -----------------------------------------------------------------
            # Step 0: Pause WAL replay (if enabled)
            # Must happen BEFORE opening the REPEATABLE READ transaction and
            # reading the snapshot LSN so the standby is frozen at a known LSN.
            # Uses a SEPARATE autocommit connection for WAL control calls.
            # The connector keeps running during this step — it needs to
            # advance CH's LSN to match the standby's frozen LSN.
            # -----------------------------------------------------------------
            if wal_pause_enabled:
                logging.info(
                    "WAL_REPLAY_PAUSE: creating WAL control connection to "
                    f"{pg_host}:{pg_port}/{pg_database}"
                )
                wal_control_conn = get_postgres_connection(
                    pg_host, pg_user, pg_password, pg_port, pg_database)
                wal_control_conn.autocommit = True

                if not is_in_recovery(wal_control_conn):
                    logging.warning(
                        "WAL_REPLAY_PAUSE: Target is NOT a standby "
                        "(pg_is_in_recovery()=False). Skipping pause."
                    )
                    wal_pause_enabled = False
                elif is_wal_replay_paused(wal_control_conn):
                    logging.warning(
                        "WAL_REPLAY_PAUSE: WAL replay already paused by another "
                        "process. Skipping to avoid conflict."
                    )
                    wal_pause_enabled = False
                else:
                    pause_wal_replay(wal_control_conn)
                    wal_was_paused_by_us = True
                    # Read the frozen LSN for logging
                    (paused_lsn_str, paused_lsn_int) = get_standby_lsn(wal_control_conn)
                    logging.info(
                        "WAL_REPLAY_PAUSE: WAL replay PAUSED. Standby frozen at "
                        f"LSN {paused_lsn_str} (int={paused_lsn_int})"
                    )

            # -----------------------------------------------------------------
            # Step 1: Open PG connection + BEGIN REPEATABLE READ
            # CRITICAL: the transaction is opened BEFORE reading the LSN so that
            # PG's snapshot is pinned at exactly the moment the tx starts.
            # We then read pg_snap_lsn from INSIDE the open transaction.
            # -----------------------------------------------------------------
            logging.info(
                f"snapshot_mode=True: opening PG REPEATABLE READ transaction FIRST "
                f"({pg_host}:{pg_port}/{pg_database})"
            )
            pg_snapshot_conn = get_postgres_connection(
                pg_host, pg_user, pg_password, pg_port, pg_database)
            pg_snapshot_conn.autocommit = False
            try:
                with pg_snapshot_conn.cursor() as cur:
                    cur.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                logging.info("PG REPEATABLE READ transaction started")
            except Exception as e:
                logging.error(
                    f"Could not start REPEATABLE READ transaction: {e}; aborting"
                )
                pg_snapshot_conn.close()
                sys.exit(1)

            # -----------------------------------------------------------------
            # Step 2: Read pg_snap_lsn from INSIDE the open transaction.
            # This LSN is exactly what PG's REPEATABLE READ snapshot reflects.
            # -----------------------------------------------------------------
            (lsn_str, lsn_int) = get_standby_lsn(pg_snapshot_conn)
            logging.info(
                f"PG snapshot LSN (inside REPEATABLE READ): "
                f"{lsn_str} (full-64-bit int = {lsn_int})"
            )

            # -----------------------------------------------------------------
            # Step 3: Discover tables on the SAME open connection (inside the tx)
            # -----------------------------------------------------------------
            table_filter = None
            if args.table:
                table_filter = f'^{args.table}$'
            elif 'table_include_list' in pg_cfg and pg_cfg['table_include_list']:
                table_filter = pg_cfg['table_include_list']

            tables = get_tables(
                pg_snapshot_conn,
                pg_schema=pg_schema,
                include_regex=table_filter,
                exclude_regex=None,
            )
            tables = [t for t in tables if t not in skip_tables]

            logging.info(f"Found {len(tables)} tables to compare")
            if not tables:
                logging.error("No tables found — check your config and table_include_list")
                pg_snapshot_conn.rollback()
                pg_snapshot_conn.close()
                sys.exit(1)

            # -----------------------------------------------------------------
            # Step 4: Wait for CH to reach pg_snap_lsn (LSN catch-up only)
            # The connection to CH is opened here; pg_snapshot_conn stays open.
            # Once CH has caught up, ALL WAL events up to pg_snap_lsn are applied.
            # -----------------------------------------------------------------
            logging.info(f"Connecting to ClickHouse: {ch_host}:{ch_port}/{ch_database}")
            ch_conn = clickhouse_connection(
                ch_host, database=ch_database,
                user=ch_user, password=ch_password,
                port=ch_port, secure=ch_secure,
            )

            if offset_table and lsn_int > 0:
                logging.info(
                    f"Waiting for CH offset table {offset_db}.{offset_table} "
                    f"to reach PG snapshot LSN {lsn_int} ({lsn_str})"
                )
                # When WAL replay is paused, allow a small tolerance because
                # pg_last_wal_replay_lsn() (physical) can be slightly ahead of
                # the CDC logical replication slot's consumed position.
                # 8 KB tolerance is safe: the standby is frozen, so PG queries
                # reflect a fixed point-in-time regardless of this small gap.
                lsn_tolerance = 8192 if wal_was_paused_by_us else 0
                caught_up = wait_for_ch_lsn(
                    ch_conn, offset_db, offset_table, lsn_int,
                    max_wait_seconds=lsn_wait_timeout,
                    poll_interval=lsn_poll_interval,
                    tolerance_bytes=lsn_tolerance,
                )
                if not caught_up:
                    if wal_was_paused_by_us:
                        logging.error(
                            "LSN wait timed out with wal_replay_pause=true — aborting. "
                            "WAL replay will be resumed in finally block."
                        )
                        ch_conn.close()
                        pg_snapshot_conn.rollback()
                        pg_snapshot_conn.close()
                        sys.exit(1)
                    else:
                        logging.warning(
                            "Proceeding with checksum despite LSN timeout — "
                            "results may contain false positives if CH is still catching up"
                        )
                else:
                    logging.info(
                        f"snapshot_mode=True: CH caught up to pg_snap_lsn={lsn_str} — "
                        f"using FINAL WHERE is_deleted=0 (no _version filter)"
                    )
            else:
                logging.warning("LSN wait skipped (no offset_table configured or LSN=0)")

            ch_conn.close()

            # -----------------------------------------------------------------
            # Step 4b: Flush sink connector (if enabled)
            # Calls the connector REST API /flush which pauses the batch
            # executor (drains any in-flight batch, then holds).
            # Must happen AFTER LSN wait so the connector can advance CH's
            # LSN during the wait. Once CH has caught up, we freeze writes
            # to prevent "future" events (beyond the standby's frozen LSN)
            # from appearing in CH during the checksum.
            # Sequence: WAL pause → LSN wait → flush connector → checksum
            # -----------------------------------------------------------------
            if connector_flush_enabled:
                logging.info(
                    "FLUSH_CONNECTOR: Flushing connector via %s",
                    connector_flush_url)
                try:
                    req = Request(connector_flush_url)
                    resp = urlopen(req, timeout=connector_flush_timeout)
                    body = resp.read().decode('utf-8')
                    connector_flushed = True
                    logging.info(
                        "FLUSH_CONNECTOR: Flush response: %s", body)
                except (URLError, HTTPError) as e:
                    logging.error(
                        "FLUSH_CONNECTOR: Failed to flush connector "
                        "via %s: %s — proceeding without flush",
                        connector_flush_url, e)
                except Exception as e:
                    logging.error(
                        "FLUSH_CONNECTOR: Unexpected error calling %s: %s "
                        "— proceeding without flush",
                        connector_flush_url, e)

                if connector_flushed:
                    # Wait for CH to stabilize after flush (drain buffered writes)
                    logging.info(
                        "FLUSH_CONNECTOR: Waiting %ds for CH to stabilize "
                        "(drain buffered writes)...", connector_stabilize_wait)
                    time.sleep(connector_stabilize_wait)

            # -----------------------------------------------------------------
            # Step 5 — Phase A: Query ALL CH tables in parallel (immediately
            # after catch-up). This takes ~1-2 min and minimises CDC drift
            # window. Each thread creates its own CH connection
            # (clickhouse-driver is not thread-safe).
            # -----------------------------------------------------------------
            results: List[ChecksumResult] = []
            no_checksum = getattr(args, 'no_checksum', False)
            ch_precomputed_all: Dict[str, Dict[str, Any]] = {}

            # Pre-fetch PG column metadata for all tables on the shared
            # REPEATABLE READ connection (serial, thread-safe).  This gives
            # Phase A the *real* PG types so it can filter out bytea,
            # tstzrange, float, json, etc. — matching Phase B's filtering.
            pg_columns_by_table: Dict[str, List[Dict[str, Any]]] = {}
            for tbl in tables:
                try:
                    pg_columns_by_table[tbl] = get_table_columns(
                        pg_snapshot_conn, pg_schema, tbl)
                except Exception as e:
                    logging.warning(
                        f"[{tbl}] Could not fetch PG column metadata for "
                        f"Phase A filtering: {e}"
                    )
                    pg_columns_by_table[tbl] = []

            logging.info(
                f"Phase A: querying all {len(tables)} CH tables in parallel "
                f"(no_checksum={no_checksum})..."
            )
            phase_a_t0 = time.time()

            def _query_ch_for_table(tbl_name):
                """Query CH count, tier3 metrics, and checksum for a single table.
                Creates its own CH connection for thread safety.

                Precomputes the CH checksum in Phase A (immediately after LSN
                catch-up) so that the ~15-minute Phase B PG serial loop does
                NOT re-query CH, eliminating the CDC drift window.

                Uses PG column metadata (pre-fetched on the snapshot connection)
                to filter out bytea/range/float/json columns — matching the
                Phase B filtering exactly.  Falls back to CH-inferred types
                when PG metadata is unavailable.
                """
                tbl_ch_conn = clickhouse_connection(
                    ch_host, database=ch_database,
                    user=ch_user, password=ch_password,
                    port=ch_port, secure=ch_secure,
                )
                try:
                    if not ch_table_exists(tbl_ch_conn, ch_database, tbl_name):
                        return {'exists': False, 'count': -1, 'tier3': None,
                                'checksum': None, 'checksum_tier': None}

                    ch_count = get_ch_count(tbl_ch_conn, ch_database, tbl_name)

                    # Get CH column metadata for tier3 and checksum computation.
                    # Use get_ch_columns_meta() which returns type info from
                    # system.columns, including inferred pg_type and nullable.
                    ch_cols_meta = get_ch_columns_meta(
                        tbl_ch_conn, ch_database, tbl_name, ch_exclude_columns)
                    ch_cols = [c['column_name'] for c in ch_cols_meta]
                    has_updated_at_ch = 'updated_at' in set(ch_cols)

                    # Get PK from CH engine sorting key (first column)
                    pk_col_ch = None
                    try:
                        pk_sql = f"""
                            SELECT splitByChar(',', sorting_key)[1]
                            FROM system.tables
                            WHERE database = '{ch_database}' AND name = '{tbl_name}'
                        """
                        (pk_rows, pk_cnt) = execute_sql(tbl_ch_conn, pk_sql)
                        if pk_rows and pk_rows[0][0]:
                            pk_col_ch = pk_rows[0][0].strip().strip('"')
                    except Exception:
                        pass

                    ch_tier3 = get_ch_tier3_metrics(
                        tbl_ch_conn, ch_database, tbl_name,
                        pk_col=pk_col_ch, has_updated_at=has_updated_at_ch)

                    # ---- Precompute CH checksum in Phase A ----
                    # Use ch_count to estimate the tier (same thresholds as Phase B).
                    # This avoids querying CH on-the-fly 15 minutes later during
                    # Phase B's serial PG loop, eliminating the CDC drift window.
                    ch_cksum = None
                    cksum_tier = None

                    if ch_count > 0 and not no_checksum:
                        # Force Tier-1 (full column MD5) for ALL tables
                        # regardless of size – correctness matters.
                        cksum_tier = 1

                        if cksum_tier in (1, 2):
                            # Build per-table skip set from config
                            tbl_skip_cols = set(
                                skip_columns_cfg.get(tbl_name, []) or [])

                            # Use real PG column metadata (pre-fetched on
                            # the snapshot connection) for type filtering.
                            # This ensures Phase A excludes the SAME columns
                            # as Phase B (bytea, range, float, json).
                            # Falls back to CH-inferred pg_type when PG
                            # metadata is unavailable for a column.
                            tbl_pg_meta = pg_columns_by_table.get(tbl_name, [])
                            pg_type_by_col = {}
                            pg_udt_by_col = {}
                            for pm in tbl_pg_meta:
                                pg_type_by_col[pm['column_name']] = pm['pg_type'].lower().strip()
                                pg_udt_by_col[pm['column_name']] = (pm.get('udt_name') or '').lower().strip()

                            # Filter columns: exclude skip_columns, float (if not
                            # included), bytea, range types — mirroring Phase B logic
                            # exactly, using real PG types when available.
                            included_cols = []
                            included_meta = []
                            for c in ch_cols_meta:
                                col_name = c['column_name']
                                # Use real PG type if available, else fall back
                                # to CH-inferred pg_type
                                pg_t = pg_type_by_col.get(col_name, c['pg_type'].lower())
                                udt_n = pg_udt_by_col.get(col_name, '')

                                # Skip per-table excluded columns
                                if col_name in tbl_skip_cols:
                                    continue

                                # Skip float columns unless included
                                if not include_floating_point and pg_t in (
                                        'real', 'float4', 'double precision',
                                        'float8', 'float'):
                                    continue

                                # Skip json/jsonb unless included
                                if not include_json and pg_t in ('json', 'jsonb'):
                                    continue

                                # Skip bytea (Debezium encodes as Base64)
                                if pg_t == 'bytea':
                                    continue

                                # Skip range types (check both pg_type and
                                # udt_name, matching Phase B / PG-side logic)
                                if pg_t in ('tstzrange', 'tsrange', 'daterange',
                                            'int4range', 'int8range', 'numrange') \
                                        or udt_n in ('tstzrange', 'tsrange',
                                                     'daterange', 'int4range',
                                                     'int8range', 'numrange'):
                                    continue

                                included_cols.append(col_name)
                                included_meta.append(c)

                            skipped_count = len(ch_cols_meta) - len(included_cols)
                            if skipped_count > 0:
                                skipped_names = sorted(
                                    set(c['column_name'] for c in ch_cols_meta)
                                    - set(included_cols) - tbl_skip_cols
                                )
                                logging.info(
                                    f"[{tbl_name}] Phase A: filtered out "
                                    f"{skipped_count} columns (type-excluded: "
                                    f"{skipped_names})"
                                )

                            if included_cols:
                                ch_cksum = get_ch_checksum(
                                    tbl_ch_conn, ch_database, tbl_name,
                                    col_names=included_cols,
                                    tier=cksum_tier,
                                    pk_col=pk_col_ch,
                                    columns_meta=included_meta,
                                )
                                logging.info(
                                    f"[{tbl_name}] Phase A: precomputed CH "
                                    f"Tier-{cksum_tier} checksum = {ch_cksum} "
                                    f"({len(included_cols)} cols)"
                                )

                    return {
                        'exists': True,
                        'count': ch_count,
                        'tier3': ch_tier3,
                        'checksum': ch_cksum,
                        'checksum_tier': cksum_tier,
                    }
                except Exception as e:
                    logging.error(f"[{tbl_name}] Phase A CH query error: {e}")
                    logging.error(traceback.format_exc())
                    return {'exists': True, 'count': -1, 'tier3': None,
                            'checksum': None, 'checksum_tier': None}
                finally:
                    tbl_ch_conn.close()

            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                future_map = {}
                for tbl in tables:
                    future_map[tbl] = pool.submit(_query_ch_for_table, tbl)
                for tbl, fut in future_map.items():
                    try:
                        ch_precomputed_all[tbl] = fut.result()
                    except Exception as e:
                        logging.error(f"[{tbl}] Phase A future error: {e}")
                        ch_precomputed_all[tbl] = {
                            'exists': True, 'count': -1, 'tier3': None, 'checksum': None
                        }

            phase_a_elapsed = time.time() - phase_a_t0
            logging.info(
                f"Phase A complete: {len(ch_precomputed_all)} CH tables queried "
                f"in {phase_a_elapsed:.1f}s"
            )

            # -----------------------------------------------------------------
            # Step 6 — Phase B: Query PG tables serially on the frozen
            # REPEATABLE READ connection (~15 min). Compare with cached CH
            # results.
            # -----------------------------------------------------------------
            logging.info(
                f"Phase B: querying PG tables serially on REPEATABLE READ connection "
                f"(no_checksum={no_checksum}, snapshot_mode=True, pg_snap_lsn={lsn_str})"
            )
            phase_b_t0 = time.time()

            for table_name in tables:
                try:
                    result = compare_table(
                        table_name,
                        pg_host, pg_user, pg_password, pg_port, pg_database, pg_schema,
                        ch_host, ch_user, ch_password, ch_port, ch_database, ch_secure,
                        ch_exclude_columns,
                        tier1_max_rows,
                        tier2_max_rows,
                        chunk_size,
                        threads_per_table,
                        alert_count_delta_pct,
                        alert_count_delta_abs,
                        include_floating_point,
                        include_json,
                        no_checksum,
                        pg_snapshot_conn=pg_snapshot_conn,
                        skip_columns=skip_columns_cfg.get(table_name),
                        ch_precomputed=ch_precomputed_all.get(table_name),
                    )
                    results.append(result)
                except Exception as e:
                    logging.error(f"[{table_name}] Unhandled exception: {e}")
                    logging.error(traceback.format_exc())
                    results.append(ChecksumResult(
                        table=table_name, tier=0,
                        pg_count=-1, ch_count=-1, count_delta=0, count_delta_pct=0.0,
                        checksum_match=None, pg_checksum=None, ch_checksum=None,
                        pg_max_pk=None, ch_max_pk=None, pg_max_ts=None, ch_max_ts=None,
                        status='ERROR', detail=str(e),
                    ))

            phase_b_elapsed = time.time() - phase_b_t0
            logging.info(
                f"Phase B complete: {len(results)} tables compared "
                f"in {phase_b_elapsed:.1f}s"
            )

            # -----------------------------------------------------------------
            # Step 6b — Auto-diff phase: find divergent rows for FAIL tables
            # Runs BEFORE COMMIT so PG snapshot connection is still open.
            # WAL replay is still paused, connector is still flushed.
            # -----------------------------------------------------------------
            auto_diff_cfg = cksum_cfg.get('auto_diff', {})
            auto_diff_enabled = bool(auto_diff_cfg.get('enabled', False))

            if auto_diff_enabled:
                failed_tables = [
                    r for r in results
                    if r.status == 'FAIL' and r.checksum_match is False
                ]
                if failed_tables:
                    logging.info(
                        "AUTO_DIFF: %d table(s) failed checksum, starting "
                        "binary search for divergent rows...",
                        len(failed_tables))
                    auto_diff_t0 = time.time()
                    auto_diff_timeout = int(
                        auto_diff_cfg.get('timeout_seconds', 600))

                    from ch_sink_tools.db_compare.auto_diff import run_auto_diff_for_table

                    for result in failed_tables:
                        elapsed = time.time() - auto_diff_t0
                        if auto_diff_timeout > 0 and elapsed > auto_diff_timeout:
                            logging.warning(
                                "AUTO_DIFF: timeout (%ds) exceeded after "
                                "%.1fs, skipping remaining tables",
                                auto_diff_timeout, elapsed)
                            break

                        try:
                            diff_result = run_auto_diff_for_table(
                                table_name=result.table,
                                pg_conn=pg_snapshot_conn,
                                pg_schema=pg_schema,
                                ch_host=ch_host,
                                ch_user=ch_user,
                                ch_password=ch_password,
                                ch_port=ch_port,
                                ch_database=ch_database,
                                ch_secure=ch_secure,
                                ch_exclude_columns=ch_exclude_columns,
                                columns_meta=pg_columns_by_table.get(
                                    result.table, []),
                                skip_columns=skip_columns_cfg.get(
                                    result.table, []),
                                include_floating_point=include_floating_point,
                                include_json=include_json,
                                auto_diff_cfg=auto_diff_cfg,
                                run_timestamp=run_start,
                            )
                            logging.info(
                                "AUTO_DIFF: [%s] complete: %d divergent rows "
                                "found, diff file: %s",
                                result.table,
                                len(diff_result.get('divergent_rows', [])),
                                diff_result.get('diff_file', 'N/A'))
                        except Exception as e:
                            logging.error(
                                "AUTO_DIFF: [%s] Error: %s",
                                result.table, e)
                            logging.error(traceback.format_exc())

                    auto_diff_elapsed = time.time() - auto_diff_t0
                    logging.info(
                        "AUTO_DIFF: phase complete in %.1fs",
                        auto_diff_elapsed)
                else:
                    logging.info(
                        "AUTO_DIFF: enabled but no tables failed checksum "
                        "with mismatched checksums -- skipping diff collection")

            # -----------------------------------------------------------------
            # Step 7: COMMIT and close shared PG connection
            # -----------------------------------------------------------------
            try:
                pg_snapshot_conn.commit()
                logging.info("PG REPEATABLE READ transaction committed")
            except Exception as e:
                logging.warning(f"Error committing PG snapshot transaction: {e}")
            finally:
                pg_snapshot_conn.close()
                pg_snapshot_conn = None

        finally:
            # -----------------------------------------------------------------
            # Step 8: Resume WAL replay (ALWAYS if we paused it)
            # -----------------------------------------------------------------
            _ensure_wal_resumed()
            if wal_control_conn:
                try:
                    wal_control_conn.close()
                except Exception:
                    pass
                wal_control_conn = None

            # -----------------------------------------------------------------
            # Step 9: Resume sink connector (ALWAYS if we flushed it)
            # -----------------------------------------------------------------
            _ensure_connector_resumed()

            # Restore original signal handlers
            if wal_pause_enabled or connector_flush_enabled:
                signal.signal(signal.SIGTERM, prev_sigterm or signal.SIG_DFL)
                signal.signal(signal.SIGINT, prev_sigint or signal.SIG_DFL)

    # =========================================================================
    # LEGACY MODE (original ordering — LSN first, no REPEATABLE READ)
    # =========================================================================
    else:
        # Step 1: Connect to PG, read LSN, discover tables, close connection
        logging.info(f"Connecting to PostgreSQL: {pg_host}:{pg_port}/{pg_database}")
        pg_conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)

        (lsn_str, lsn_int) = get_standby_lsn(pg_conn)
        logging.info(f"Target WAL LSN: {lsn_str} (full-64-bit int = {lsn_int})")

        table_filter = None
        if args.table:
            table_filter = f'^{args.table}$'
        elif 'table_include_list' in pg_cfg and pg_cfg['table_include_list']:
            table_filter = pg_cfg['table_include_list']

        tables = get_tables(
            pg_conn,
            pg_schema=pg_schema,
            include_regex=table_filter,
            exclude_regex=None,
        )
        tables = [t for t in tables if t not in skip_tables]
        pg_conn.close()

        logging.info(f"Found {len(tables)} tables to compare")
        if not tables:
            logging.error("No tables found — check your config and table_include_list")
            sys.exit(1)

        # Step 2: Wait for CH to reach LSN
        logging.info(f"Connecting to ClickHouse: {ch_host}:{ch_port}/{ch_database}")
        ch_conn = clickhouse_connection(
            ch_host, database=ch_database,
            user=ch_user, password=ch_password,
            port=ch_port, secure=ch_secure,
        )

        if offset_table and lsn_int > 0:
            logging.info(f"Waiting for CH offset table {offset_db}.{offset_table} to reach LSN {lsn_int}")
            caught_up = wait_for_ch_lsn(
                ch_conn, offset_db, offset_table, lsn_int,
                max_wait_seconds=lsn_wait_timeout,
                poll_interval=lsn_poll_interval,
            )
            if not caught_up:
                logging.warning(
                    "Proceeding with checksum despite LSN timeout — "
                    "results may contain false positives if CH is still catching up"
                )
        else:
            logging.warning("LSN wait skipped (no offset_table configured or LSN=0)")

        ch_conn.close()

        # Step 3: Run per-table comparison (concurrent, each table opens own PG conn)
        results: List[ChecksumResult] = []
        no_checksum = getattr(args, 'no_checksum', False)

        logging.info(
            f"Starting per-table comparison "
            f"(threads={threads}, no_checksum={no_checksum}, snapshot_mode=False)"
        )

        with concurrent.futures.ThreadPoolExecutor(max_workers=threads) as executor:
            futures = {}
            for table_name in tables:
                fut = executor.submit(
                    compare_table,
                    table_name,
                    pg_host, pg_user, pg_password, pg_port, pg_database, pg_schema,
                    ch_host, ch_user, ch_password, ch_port, ch_database, ch_secure,
                    ch_exclude_columns,
                    tier1_max_rows,
                    tier2_max_rows,
                    chunk_size,
                    threads_per_table,
                    alert_count_delta_pct,
                    alert_count_delta_abs,
                    include_floating_point,
                    include_json,
                    no_checksum,
                    None,       # pg_snapshot_conn=None → legacy per-thread connection
                    skip_columns_cfg.get(table_name),
                )
                futures[fut] = table_name

            for fut in concurrent.futures.as_completed(futures):
                table_name = futures[fut]
                try:
                    result = fut.result()
                    results.append(result)
                except Exception as e:
                    logging.error(f"[{table_name}] Unhandled exception: {e}")
                    logging.error(traceback.format_exc())
                    results.append(ChecksumResult(
                        table=table_name, tier=0,
                        pg_count=-1, ch_count=-1, count_delta=0, count_delta_pct=0.0,
                        checksum_match=None, pg_checksum=None, ch_checksum=None,
                        pg_max_pk=None, ch_max_pk=None, pg_max_ts=None, ch_max_ts=None,
                        status='ERROR', detail=str(e),
                    ))

    # -------------------------------------------------------------------------
    # Step 7: Print summary and exit
    # -------------------------------------------------------------------------
    run_end = datetime.now(timezone.utc)

    print_summary(results, run_start, run_end, lsn_str, lsn_int,
                  pg_database, ch_database)

    fail_count = sum(1 for r in results if r.status in ('FAIL', 'MISSING', 'ERROR'))
    sys.exit(1 if fail_count > 0 else 0)


# ---------------------------------------------------------------------------
# Logger factory (mirrors other scripts)
# ---------------------------------------------------------------------------

old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='''
PostgreSQL → ClickHouse periodic checksum verification.
Mirrors top_level_table_checksum.py but with LSN-wait instead of table locks.

Uses three tiers:
  Tier-1 (<100K rows)     : full column MD5 checksum
  Tier-2 (100K-10M rows)  : PK-list MD5 checksum
  Tier-3 (>10M rows)      : count + max(pk) + max(updated_at)
    ''')

    parser.add_argument('--config', '--config_file', dest='config',
                        help='Path to the YAML configuration file', required=True)
    parser.add_argument('--table',
                        help='Compare a single table (bare name, no schema prefix)',
                        required=False, default=None)
    parser.add_argument('--no-checksum', '--no_checksum', dest='no_checksum',
                        action='store_true', default=False,
                        help='Skip checksum computation; only compare row counts')
    parser.add_argument('--verbose', '-v', dest='debug',
                        action='store_true', default=False,
                        help='Enable DEBUG logging')
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)

    global args
    args = parser.parse_args()

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(threadName)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if args.debug:
        root.setLevel(logging.DEBUG)
        handler.setLevel(logging.DEBUG)

    config = parse_config(args.config)

    if not validate_config(config):
        logging.error("Invalid configuration — fix errors above and retry")
        sys.exit(1)

    logging.info("Configuration validated OK")
    run_config(config, args)


if __name__ == '__main__':
    main()
