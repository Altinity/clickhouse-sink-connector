#!/usr/bin/env python3
"""
Local validation script: runs corrected PG+CH checksums directly from this machine
against the live awacs-qa replication, WITHOUT needing SSH to clickhouse.

PG:  postgres:5435  (direct TCP accessible)
CH:  clickhouse:8123      (HTTP interface accessible)

Key fixes being validated:
  1. TimeZone=UTC on PG connection (timestamptz → UTC strings matching CH)
  2. ARRAY columns: CH replaceAll(replaceAll(toString(col),'{',''),'}','') matches PG array_to_string(col,',')
  3. UUID: lower(toString(col)) in CH matches lower(col::text) in PG
  4. Boolean: if(col=0,'0','1') in CH matches CASE WHEN col THEN '1' ELSE '0' END in PG
"""
import hashlib
import json
import logging
import sys
import urllib.request
import urllib.parse

import psycopg2
import psycopg2.extras

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s %(message)s',
    stream=sys.stdout,
)

# ---------------------------------------------------------------------------
# Connection params
# ---------------------------------------------------------------------------
PG_HOST = 'postgres'
PG_PORT = 5435
PG_USER = 'sink_connector_user'
PG_PASS = '<REDACTED>'
PG_DB   = 'awacs-qa'
PG_SCHEMA = 'public'

CH_HOST = 'clickhouse'
CH_PORT = 8123
CH_USER = 'sink_connector_user'
CH_PASS = '<REDACTED>'
CH_DB   = 'awacs-qa'

# ---------------------------------------------------------------------------
# CH columns to exclude from checksum (CDC internals)
# ---------------------------------------------------------------------------
CH_EXCLUDE = {'_version', 'is_deleted', '_timestamp', '_offset',
              '_partition', '_topic', '_key', 'record_insert_ts',
              'record_insert_seq'}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def pg_connect():
    conn = psycopg2.connect(
        host=PG_HOST, port=PG_PORT, user=PG_USER, password=PG_PASS,
        dbname=PG_DB, connect_timeout=20,
        options='-c statement_timeout=0',
    )
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("SET TimeZone = 'UTC'")
    return conn


def ch_query(sql):
    """Execute SQL via CH HTTP interface, return list of TSV rows as lists."""
    params = urllib.parse.urlencode({
        'user': CH_USER,
        'password': CH_PASS,
        'database': CH_DB,
        'default_format': 'TSV',
        'do_not_merge_across_partitions_select_final': 1,
    })
    url = f'http://{CH_HOST}:{CH_PORT}/?{params}'
    data = sql.encode('utf-8')
    req = urllib.request.Request(url, data=data, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode('utf-8').strip()
            if not body:
                return []
            return [line.split('\t') for line in body.split('\n')]
    except urllib.error.HTTPError as e:
        err = e.read().decode('utf-8')
        raise RuntimeError(f'CH HTTP error: {err}')


def pg_query(conn, sql):
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql)
        return cur.fetchall()


def get_pg_columns(conn, table):
    rows = pg_query(conn, f"""
        SELECT column_name, data_type AS pg_type, udt_name,
               is_nullable = 'YES' AS nullable
        FROM information_schema.columns
        WHERE table_schema = '{PG_SCHEMA}' AND table_name = '{table}'
        ORDER BY ordinal_position
    """)
    return rows


def get_ch_columns(table):
    rows = ch_query(f"""
        SELECT name, type
        FROM system.columns
        WHERE database = '{CH_DB}' AND table = '{table}'
        ORDER BY position
    """)
    return {r[0]: r[1] for r in rows}


# ---------------------------------------------------------------------------
# Expression builders
# ---------------------------------------------------------------------------
FLOAT_TYPES  = {'real', 'float4', 'double precision', 'float8', 'float'}
JSON_TYPES   = {'json', 'jsonb'}
# Additional types that can't be reliably normalized across PG→CH:
#   bytea  : CH stores as \\x-prefixed hex string; PG encode(col,'hex') = no prefix — skip both
#   tstzrange / other range types: format differs; skip both sides
#   any other user-defined complex types: skip
COMPLEX_TYPES = {'bytea', 'tstzrange', 'tsrange', 'daterange', 'int4range',
                 'int8range', 'numrange'}
SKIP_TYPES   = FLOAT_TYPES | JSON_TYPES | COMPLEX_TYPES


def build_pg_expr(col_name, pg_type, is_nullable, udt_name):
    q = f'"{col_name}"'
    pg_t = pg_type.lower().strip()
    udt  = (udt_name or '').lower().strip()

    if pg_t in SKIP_TYPES:
        return None   # excluded
    if pg_t in ('boolean', 'bool'):
        expr = f"CASE WHEN {q} THEN '1' ELSE '0' END"
    elif 'timestamp' in pg_t:
        expr = f"to_char({q}, 'YYYY-MM-DD HH24:MI:SS.US')"
    elif pg_t == 'date':
        expr = f"to_char({q}, 'YYYY-MM-DD')"
    elif 'time' in pg_t:
        expr = f"to_char({q}, 'HH24:MI:SS.US')"
    elif pg_t == 'bytea':
        expr = f"encode({q}, 'hex')"
    elif pg_t == 'uuid':
        expr = f"lower({q}::text)"
    elif udt.startswith('_') or pg_t.endswith('[]'):
        expr = f"array_to_string({q}, ',')"
    else:
        expr = f"{q}::text"

    if is_nullable:
        expr = f"coalesce({expr}, '')"
    return expr


def build_ch_expr(col_name, pg_type, is_nullable, udt_name):
    qc = f'"{col_name}"'
    pg_t = pg_type.lower().strip()
    udt  = (udt_name or '').lower().strip()

    if pg_t in SKIP_TYPES:
        return None
    if pg_t in ('boolean', 'bool'):
        expr = f"if({qc} = 0, '0', '1')"
    elif udt.startswith('_') or pg_t in ('array', 'anyarray'):
        expr = f"replaceAll(replaceAll(toString({qc}), '{{', ''), '}}', '')"
    elif pg_t == 'uuid':
        expr = f"lower(toString({qc}))"
    else:
        expr = f"toString({qc})"

    if is_nullable:
        expr = f"coalesce({expr}, '')"
    return expr


# ---------------------------------------------------------------------------
# Checksum computation
# ---------------------------------------------------------------------------
def compute_pg_checksum(conn, table, pg_cols):
    col_exprs = []
    nullable_cols = []
    for col in pg_cols:
        expr = build_pg_expr(col['column_name'], col['pg_type'],
                             col['nullable'], col['udt_name'])
        if expr is None:
            continue
        col_exprs.append(expr)
        if col['nullable']:
            nullable_cols.append(f'"{col["column_name"]}"')

    if not col_exprs:
        return None, 0

    null_part = ''
    if nullable_cols:
        null_bits = ' || '.join(
            f"(CASE WHEN {c} IS NULL THEN '1' ELSE '0' END)"
            for c in nullable_cols
        )
        null_part = f" || '#' || {null_bits}"

    concat_expr = "concat_ws('#', " + ', '.join(col_exprs) + ')'  + null_part

    sql = f"""
SELECT
    count(*) AS cnt,
    sum(('x' || substring(row_hash,  1, 8))::bit(32)::int8) AS a,
    sum(('x' || substring(row_hash,  9, 8))::bit(32)::int8) AS b,
    sum(('x' || substring(row_hash, 17, 8))::bit(32)::int8) AS c,
    sum(('x' || substring(row_hash, 25, 8))::bit(32)::int8) AS d
FROM (
    SELECT md5({concat_expr}) AS row_hash
    FROM "{PG_SCHEMA}"."{table}"
) t
"""
    rows = pg_query(conn, sql)
    row = rows[0]
    cnt = int(row['cnt'] or 0)
    if cnt == 0:
        return _zero_checksum(), 0
    values = (cnt, int(row['a'] or 0), int(row['b'] or 0),
              int(row['c'] or 0), int(row['d'] or 0))
    return _final_md5(values), cnt


def compute_ch_checksum(table, pg_cols, ch_cols):
    # Intersect PG columns with CH columns (exclude CDC internals)
    ch_col_set = set(ch_cols.keys()) - CH_EXCLUDE

    col_exprs = []
    nullable_cols = []
    for col in pg_cols:
        cname = col['column_name']
        if cname not in ch_col_set:
            continue
        expr = build_ch_expr(cname, col['pg_type'],
                             col['nullable'], col['udt_name'])
        if expr is None:
            continue
        col_exprs.append(expr)
        if col['nullable']:
            nullable_cols.append(f'"{cname}"')

    if not col_exprs:
        return None, 0

    null_part = ''
    if nullable_cols:
        null_bits = ' || '.join(
            f"(case when {c} is null then '1' else '0' end)"
            for c in nullable_cols
        )
        null_part = f" || '#' || {null_bits}"

    concat_expr = "concat_ws('#', " + ', '.join(col_exprs) + ')' + null_part

    sql = f"""
SELECT
    count(*) AS cnt,
    sum(reinterpretAsInt64(reverse(unhex(substring(hash,  1, 8))))) AS a,
    sum(reinterpretAsInt64(reverse(unhex(substring(hash,  9, 8))))) AS b,
    sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))) AS c,
    sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))) AS d
FROM (
    SELECT hex(MD5({concat_expr})) AS hash
    FROM "{CH_DB}"."{table}" FINAL
    WHERE is_deleted = 0
) t
SETTINGS do_not_merge_across_partitions_select_final = 1
"""
    rows = ch_query(sql)
    if not rows:
        return _zero_checksum(), 0
    row = rows[0]
    cnt = int(row[0] or 0)
    if cnt == 0:
        return _zero_checksum(), 0
    values = (cnt, int(row[1] or 0), int(row[2] or 0),
              int(row[3] or 0), int(row[4] or 0))
    return _final_md5(values), cnt


def _zero_checksum():
    md5_input = '#'.join(['0'] * 5) + '#'
    return hashlib.md5(md5_input.encode()).hexdigest()


def _final_md5(values):
    md5_input = '#'.join(str(x) for x in values) + '#'
    return hashlib.md5(md5_input.encode()).hexdigest()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    tables = [
        'alerts_agent', 'alerts_alert', 'alerts_alertattachment',
        'alerts_alertevent', 'alerts_alertincident', 'alerts_alerttemplate',
        'alerts_jsmteammapping', 'alerts_menuitem', 'alerts_oncall',
        'alerts_oncallprovider', 'alerts_preprocessordata', 'alerts_rule',
        'alerts_rulealerteventrelation', 'alerts_rulehistoryentry',
        'alerts_savedquery', 'alerts_savedqueryhistory', 'alerts_savedview',
        'alerts_tagcache', 'alerts_untrackedtag', 'alerts_user',
        'alerts_user_groups', 'alerts_user_user_permissions',
        'alerts_viewlink', 'auth_group', 'auth_group_permissions',
        'auth_permission', 'authtoken_token', 'django_admin_log',
        'django_content_type', 'django_migrations', 'django_session',
        'social_auth_association', 'social_auth_code', 'social_auth_nonce',
        'social_auth_partial', 'social_auth_usersocialauth',
    ]

    pg_conn = pg_connect()
    logging.info("Connected to PG %s:%s, timezone=UTC", PG_HOST, PG_PORT)

    results = []
    pass_count = fail_count = skip_count = 0

    for table in tables:
        try:
            pg_cols = get_pg_columns(pg_conn, table)
            if not pg_cols:
                logging.warning("  [%s] no PG columns found — skipping", table)
                skip_count += 1
                results.append((table, 'SKIP', 0, 0, None, None, ''))
                continue

            ch_cols = get_ch_columns(table)
            if not ch_cols:
                logging.warning("  [%s] not in CH — skipping", table)
                skip_count += 1
                results.append((table, 'SKIP', 0, 0, None, None, 'missing in CH'))
                continue

            pg_cksum, pg_cnt = compute_pg_checksum(pg_conn, table, pg_cols)
            ch_cksum, ch_cnt = compute_ch_checksum(table, pg_cols, ch_cols)

            match = (pg_cksum == ch_cksum)
            status = 'PASS' if match else 'FAIL'
            delta  = ch_cnt - pg_cnt

            if match:
                pass_count += 1
                logging.info("  [%s] PASS  pg=%d ch=%d delta=%d", table, pg_cnt, ch_cnt, delta)
            else:
                fail_count += 1
                logging.warning("  [%s] FAIL  pg=%d ch=%d delta=%d  pg_ck=%s  ch_ck=%s",
                                table, pg_cnt, ch_cnt, delta, pg_cksum, ch_cksum)

            results.append((table, status, pg_cnt, ch_cnt, pg_cksum, ch_cksum,
                            f'delta={delta}'))

        except Exception as e:
            logging.error("  [%s] ERROR: %s", table, e)
            fail_count += 1
            results.append((table, 'ERROR', 0, 0, None, None, str(e)))

    # Summary
    total = len(tables)
    print('\n' + '='*72)
    print(f'SUMMARY: {pass_count}/{total} PASS  {fail_count} FAIL  {skip_count} SKIP')
    print('='*72)
    print(f'{"TABLE":<40} {"STATUS":<8} {"PG_CNT":>8} {"CH_CNT":>8} {"DELTA":>6}')
    print('-'*72)
    for (table, status, pg_cnt, ch_cnt, pg_ck, ch_ck, detail) in results:
        delta = ch_cnt - pg_cnt if pg_cnt and ch_cnt else 0
        marker = '✓' if status == 'PASS' else ('✗' if status == 'FAIL' else '?')
        print(f'{marker} {table:<38} {status:<8} {pg_cnt:>8} {ch_cnt:>8} {delta:>6}')


if __name__ == '__main__':
    main()
