"""
Pieces of the table checksum that MUST be identical on the MySQL side and on the
ClickHouse side (spec 11.02). Anything defined here is imported by both
db_compare/mysql_table_checksum.py and db_compare/clickhouse_table_checksum.py so
that the two sides cannot drift apart.
"""
import hashlib


def checksum_from_aggregate(cnt, a, b, c, d):
    """The printed checksum: md5 of ``'<cnt>#<a>#<b>#<c>#<d>#'``.

    ``cnt`` is the row count and ``a``..``d`` are the sums, over all rows, of the
    four 32-bit words of each row's MD5 (spec 11.02 section 3.4). Both sides print
    exactly this value, so an empty table hashes to ``md5('0#0#0#0#0#')`` on both.
    """
    text = "".join(str(value) + "#" for value in (cnt, a, b, c, d))
    return hashlib.md5(text.encode("utf-8")).hexdigest()
