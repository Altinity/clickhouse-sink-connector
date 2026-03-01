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
import concurrent.futures
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any

from db.postgres import (
    get_postgres_connection,
    execute_pg,
    get_tables,
    get_table_columns,
    get_table_pk,
    get_table_row_count,
    get_current_lsn,
    resolve_credentials_from_pgpass,
)
from db.clickhouse import (
    clickhouse_connection,
    execute_sql,
    resolve_credentials_from_config,
)
from postgres_table_checksum import (
    get_postgres_table_checksum,
    build_pg_select_expression,
)

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
                     poll_interval: int = 10) -> bool:
    """
    Poll the ClickHouse offset table until the stored LSN integer
    is >= target_lsn_int.

    The offset table stores a JSON string in `offset_val` with a field `lsn`
    that contains the low-32-bit integer of the WAL LSN (Debezium encoding).

    Returns True if caught up before timeout, False on timeout.
    """
    full_table = f"{offset_db}.{offset_table}"
    deadline = time.time() + max_wait_seconds
    logged_first = False

    while time.time() < deadline:
        try:
            # Use MAX(JSONExtractInt(offset_val, 'lsn')) so a stale bootstrap row
            # with a low lsn value never hides a newer, higher-lsn row.
            sql = f"""
                SELECT max(toInt64OrZero(JSONExtractRaw(offset_val, 'lsn')))
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
                if ch_lsn >= target_lsn_int:
                    logging.info(
                        f"ClickHouse caught up: ch_lsn={ch_lsn} >= target={target_lsn_int}"
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
    """Exact count from ClickHouse using FINAL + is_deleted=0."""
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

def _build_ch_col_expr(col_name: str, pg_type: str, is_nullable: bool) -> str:
    """
    Build a ClickHouse SQL expression that produces a stable text value
    matching the PostgreSQL side's build_pg_select_expression() output.

    Key normalisations:
      boolean (UInt8 in CH)  → if(col = 0, '0', '1')   matches PG CASE WHEN col THEN '1' ELSE '0' END
      timestamp / DateTime64 → toString(col)              matches PG to_char(col,'YYYY-MM-DD HH24:MI:SS.US')
                                                          NOTE: CH toString(DateTime64(6)) = 'YYYY-MM-DD HH:MM:SS.ffffff'
      date / Date32          → toString(col)              matches PG to_char(col,'YYYY-MM-DD')
      everything else        → toString(col)
    Nullable columns are wrapped with coalesce(..., '') to match PG coalesce(..., '').
    """
    qc = f'"{col_name}"'
    pg_type_lower = pg_type.lower().strip() if pg_type else ''

    if pg_type_lower in ('boolean', 'bool'):
        # CH stores boolean as UInt8; toString gives '0'/'1'
        expr = f"if({qc} = 0, '0', '1')"
    elif 'timestamp' in pg_type_lower and 'time zone' in pg_type_lower:
        # timestamptz: normalise to UTC so output matches PG AT TIME ZONE 'UTC'
        # toString(toTimeZone(col, 'UTC')) renders in UTC regardless of CH server TZ
        expr = f"toString(toTimeZone({qc}, 'UTC'))"
    else:
        expr = f"toString({qc})"

    if is_nullable:
        expr = f"coalesce({expr}, '')"
    return expr


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
            WHERE is_deleted = 0
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
                  no_checksum: bool) -> ChecksumResult:
    """
    Full comparison for a single table.  Opens its own PG and CH connections
    (thread-safe: one connection per thread).

    Steps:
      1. Check CH table exists
      2. Get approximate PG row count → select tier
      3. Get exact PG count + CH count
      4. Run tier-appropriate checksum (unless no_checksum or Tier-3)
      5. Analyse differences and return ChecksumResult
    """
    try:
        # --- Open connections ---
        pg_conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)
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

            # Tier selection
            if approx_rows > tier2_max_rows:
                tier = 3
            elif approx_rows > tier1_max_rows:
                tier = 2
            else:
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

            # For checksum, use only columns that exist in BOTH PG and CH
            pg_col_names = [c['column_name'] for c in columns_meta]
            shared_col_names = [c for c in pg_col_names if c in set(ch_col_names)]

            logging.info(
                f"[{table_name}] TIER={tier} approx_rows={approx_rows:,} "
                f"pk={pk_col} pg_cols={len(pg_col_names)} shared_cols={len(shared_col_names)}"
            )

            # --- Tier-3: count + max metrics only ---
            if tier == 3:
                pg_metrics = get_pg_tier3_metrics(pg_conn, pg_schema, table_name,
                                                    pk_col, has_updated_at)
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

            # CH exact count
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
                pg_checksum = get_postgres_table_checksum(
                    conn=pg_conn,
                    table_name=table_name,
                    columns_meta=[c for c in columns_meta if c['column_name'] in set(shared_col_names)],
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
                    # Skip bytea: Debezium encodes as Base64, dump uses hex\x
                    if pg_t == 'bytea':
                        continue
                    # Skip range types: Debezium uses UTC, dump uses session-TZ
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
    Main orchestration flow:
      1. Connect to PG → capture WAL LSN
      2. Discover tables
      3. Connect to CH → wait for LSN catch-up
      4. Run per-table comparison concurrently
      5. Print summary + exit code
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

    run_start = datetime.now(timezone.utc)

    # -------------------------------------------------------------------------
    # Step 1: Connect to PG and capture WAL LSN
    # -------------------------------------------------------------------------
    logging.info(f"Connecting to PostgreSQL: {pg_host}:{pg_port}/{pg_database}")
    pg_conn = get_postgres_connection(pg_host, pg_user, pg_password, pg_port, pg_database)

    (lsn_str, lsn_int) = get_current_lsn(pg_conn)
    logging.info(f"Target WAL LSN: {lsn_str} (low-32-bit int = {lsn_int})")

    # -------------------------------------------------------------------------
    # Step 2: Discover tables
    # -------------------------------------------------------------------------
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

    # Apply skip_tables from config
    tables = [t for t in tables if t not in skip_tables]
    pg_conn.close()

    logging.info(f"Found {len(tables)} tables to compare")
    if not tables:
        logging.error("No tables found — check your config and table_include_list")
        sys.exit(1)

    # -------------------------------------------------------------------------
    # Step 3: Connect to CH and wait for LSN catch-up
    # -------------------------------------------------------------------------
    logging.info(f"Connecting to ClickHouse: {ch_host}:{ch_port}/{ch_database}")
    ch_conn = clickhouse_connection(
        ch_host, database=ch_database,
        user=ch_user, password=ch_password,
        port=ch_port, secure=ch_secure,
    )

    if offset_table and lsn_int > 0:
        logging.info(f"Waiting for ClickHouse offset table {offset_db}.{offset_table} to reach LSN {lsn_int}")
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

    # -------------------------------------------------------------------------
    # Step 4: Run per-table comparison concurrently
    # -------------------------------------------------------------------------
    results: List[ChecksumResult] = []

    no_checksum = getattr(args, 'no_checksum', False)

    logging.info(f"Starting per-table comparison (threads={threads}, no_checksum={no_checksum})")

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
    # Step 5: Print summary and exit
    # -------------------------------------------------------------------------
    run_end = datetime.now(timezone.utc)

    print_summary(results, run_start, run_end, lsn_str, lsn_int, pg_database, ch_database)

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
