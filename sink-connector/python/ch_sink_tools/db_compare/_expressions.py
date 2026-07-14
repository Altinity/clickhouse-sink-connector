"""
Shared SQL expression builders for ClickHouse and PostgreSQL checksum comparison.

Extracted from top_level_postgres_checksum.py to break the circular import
between auto_diff.py and top_level_postgres_checksum.py.
"""

from typing import Optional


def _build_ch_col_expr(col_name, pg_type, is_nullable):
    # type: (str, str, bool) -> str
    """
    Build a ClickHouse SQL expression that produces a stable text value
    matching the PostgreSQL side's build_pg_select_expression() output.

    Key normalisations:
      boolean (UInt8 in CH)  -> if(col = 0, '0', '1')   matches PG CASE WHEN col THEN '1' ELSE '0' END
      timestamp / DateTime64 -> toString(col)              matches PG to_char(col,'YYYY-MM-DD HH24:MI:SS.US')
                                                           NOTE: CH toString(DateTime64(6)) = 'YYYY-MM-DD HH:MM:SS.ffffff'
      date / Date32          -> toString(col)              matches PG to_char(col,'YYYY-MM-DD')
      json / jsonb           -> coalesce(col, '')          Debezium preserves Postgres's sorted-key jsonb
                                                           serialization, so raw CH String matches PG jsonb::text
      everything else        -> toString(col)
    Nullable columns are wrapped with coalesce(..., '') to match PG coalesce(..., '').
    """
    qc = '"{}"'.format(col_name)
    pg_type_lower = pg_type.lower().strip() if pg_type else ''

    if pg_type_lower in ('boolean', 'bool'):
        # CH stores boolean as UInt8; toString gives '0'/'1'
        # For Nullable(UInt8), if(col = 0, ...) treats NULL as false (else branch)
        # which gives '1' instead of NULL.  Preserve NULL so the outer coalesce
        # produces '' – matching the PG side.
        if is_nullable:
            expr = "if(isNull({qc}), NULL, if({qc} = 0, '0', '1'))".format(qc=qc)
        else:
            expr = "if({qc} = 0, '0', '1')".format(qc=qc)
    elif 'timestamp' in pg_type_lower and 'with time zone' in pg_type_lower \
            and 'without time zone' not in pg_type_lower:
        # Bug 84.2-1 fix: only apply toTimeZone() for `timestamp with time zone`
        # (timestamptz).  `timestamp without time zone` must NOT be converted —
        # it stores timezone-naive values and toTimeZone() would incorrectly
        # shift them by the CH server's timezone offset.
        # toString(toTimeZone(col, 'UTC')) renders in UTC regardless of CH server TZ
        expr = "toString(toTimeZone({qc}, 'UTC'))".format(qc=qc)
    elif 'timestamp' in pg_type_lower:
        # timestamp without time zone — no timezone conversion needed;
        # the value is already timezone-naive, just render as string.
        expr = "toString({qc})".format(qc=qc)
    elif pg_type_lower in ('json', 'jsonb'):
        # Debezium PostgreSQL connector preserves the Postgres jsonb serialization,
        # which always has keys in sorted order (Postgres jsonb guarantees this at
        # WAL level). The CH String column should already match PG jsonb::text.
        # Use the column directly -- no toString() needed since it's already a String.
        expr = qc
    else:
        expr = "toString({qc})".format(qc=qc)

    if is_nullable:
        expr = "coalesce({expr}, '')".format(expr=expr)
    return expr
