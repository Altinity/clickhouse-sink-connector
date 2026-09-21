"""
Pieces of the table checksum that MUST be identical on the MySQL side and on the
ClickHouse side (spec 11.02). Anything defined here is imported by both
db_compare/mysql_table_checksum.py and db_compare/clickhouse_table_checksum.py so
that the two sides cannot drift apart.
"""
import datetime
import hashlib
import logging

# The ClickHouse DateTime64 range the connector clamps to when it writes
# (DataTypeRange.DATETIME64_MIN / DATETIME64_MAX: 1900-01-01 00:00:00 and
# 2299-12-31 23:59:59 UTC), in the canonical rendering below. Both sides clamp
# their rendered datetime text to these bounds (or to narrower user bounds), so a
# MySQL value the connector could only store clamped compares equal to what
# ClickHouse holds.
DATETIME_MIN = "1900-01-01 00:00:00.000000"
DATETIME_MAX = "2299-12-31 23:59:59.000000"

# Canonical text of a DATETIME / TIMESTAMP value on both sides: fixed width,
# six fraction digits, never trimmed (spec 11.02 section 3.4). Fixed width keeps
# the mapping injective (trimming trailing zeros turned '10:00:10' into '10:00:1'
# on one side and '10:00:10' on the other) and makes lexicographic comparison
# against the bounds a correct temporal comparison.
CANONICAL_DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S.%f"
_ACCEPTED_BOUND_FORMATS = ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d")


def checksum_from_aggregate(cnt, a, b, c, d):
    """The printed checksum: md5 of ``'<cnt>#<a>#<b>#<c>#<d>#'``.

    ``cnt`` is the row count and ``a``..``d`` are the sums, over all rows, of the
    four 32-bit words of each row's MD5 (spec 11.02 section 3.5). Both sides print
    exactly this value, so an empty table hashes to ``md5('0#0#0#0#0#')`` on both.
    """
    text = "".join(str(value) + "#" for value in (cnt, a, b, c, d))
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def canonical_datetime_bound(value, option_name):
    """Normalise a user supplied datetime bound to the canonical 26-character
    form and confine it to [DATETIME_MIN, DATETIME_MAX].

    A bound outside the ClickHouse range cannot be exceeded on the replica side,
    so it would clamp MySQL alone; it is pulled back to the range with a WARNING.
    A value that is not a datetime is refused loudly.
    """
    text = str(value).strip()
    parsed = None
    for fmt in _ACCEPTED_BOUND_FORMATS:
        try:
            parsed = datetime.datetime.strptime(text, fmt)
            break
        except ValueError:
            continue
    if parsed is None:
        raise ValueError(f"{option_name}: '{value}' is not a datetime; expected YYYY-MM-DD[ HH:MM:SS[.ffffff]]")
    rendered = parsed.strftime(CANONICAL_DATETIME_FORMAT)
    confined = min(max(rendered, DATETIME_MIN), DATETIME_MAX)
    if confined != rendered:
        logging.warning(f"{option_name} {rendered} is outside the ClickHouse DateTime64 range, using {confined}")
    return confined


def datetime_bounds(options):
    """(min, max) canonical bounds from the parsed --min/--max_datetime_value."""
    return (canonical_datetime_bound(options.min_datetime_value, "--min_datetime_value"),
            canonical_datetime_bound(options.max_datetime_value, "--max_datetime_value"))


def clamp_datetime_expression(rendered, min_value, max_value, dialect):
    """Clamp the canonical datetime text ``rendered`` to [min_value, max_value]:
    ``>= max`` renders as the max bound, ``< min`` as the min bound. The same
    rule in both dialects -- the two sides must never differ in the comparison
    operator or in the text they substitute."""
    if dialect == "mysql":
        return f"case when {rendered} >= '{max_value}' then '{max_value}' when {rendered} < '{min_value}' then '{min_value}' else {rendered} end"
    if dialect == "clickhouse":
        return f"if({rendered} >= '{max_value}', '{max_value}', if({rendered} < '{min_value}', '{min_value}', {rendered}))"
    raise ValueError(f"unknown SQL dialect {dialect!r}")


def clamped_datetime_flag(rendered, min_value, max_value):
    """1 when the clamp changed the value (strictly outside the bounds), 0
    otherwise, NULL for NULL; identical text in both dialects. Summed per table
    and printed as the clamped-value count, never hashed."""
    return f"({rendered} > '{max_value}' or {rendered} < '{min_value}')"


def clamped_count_expression(flags):
    """Per-row sum of the clamped flags (``0`` when there is nothing to clamp)."""
    if not flags:
        return "0"
    return " + ".join(f"coalesce({flag}, 0)" for flag in flags)
