"""Row-text escaping and the checksum format tag (spec 11.02 section 3.3, section 3.5).

Each value piece of a canonical row is escaped before the '#' join: every
backslash is doubled first, then every '#' is prefixed with a backslash. An
unescaped '#' in the joined text can then only be a separator, so the join is
injective on the pieces. Both renderers apply the same escape with the same
vectors; the SQL wrappers are checked here as text, and the live cross-side
comparison runs in the differential harness (test case c01).
"""
import hashlib
import itertools
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

from ch_sink_tools.db.checksum_common import (  # noqa: E402
    CHECKSUM_FORMAT, checksum_from_aggregate, clickhouse_escape_piece_sql, escape_checksum_piece,
    join_checksum_pieces, mysql_escape_piece_sql,
)

# (piece, escaped) pairs shared by both sides; the e2e case c01 uses the same values.
VECTORS = [
    ("", ""),
    ("plain", "plain"),
    ("a#b", "a\\#b"),
    ("a\\b", "a\\\\b"),
    ("a\\#b", "a\\\\\\#b"),
    ("##", "\\#\\#"),
    ("\\\\", "\\\\\\\\"),
    ("x\\", "x\\\\"),
]


class TestEscapeVectors(unittest.TestCase):
    def test_fixed_vectors(self):
        for piece, escaped in VECTORS:
            with self.subTest(piece=piece):
                self.assertEqual(escape_checksum_piece(piece), escaped)

    def test_every_separator_in_the_joined_text_is_a_real_separator(self):
        # Splitting the joined text on '#' that is not preceded by an escaping
        # backslash must give back the pieces, for every vector.
        for piece, _ in VECTORS:
            with self.subTest(piece=piece):
                self.assertEqual(split_unescaped(join_checksum_pieces([piece, "z"])), [piece, "z"])


class TestJoinIsInjective(unittest.TestCase):
    def test_pieces_that_collide_without_escaping_stay_apart(self):
        pairs = [
            (["a#b", "c"], ["a", "b#c"]),
            (["a", "b"], ["a#b"]),
            (["a\\", "b"], ["a", "\\b"]),
            (["x\\#", "y"], ["x", "\\#y"]),
        ]
        for left, right in pairs:
            with self.subTest(left=left, right=right):
                self.assertNotEqual(join_checksum_pieces(left), join_checksum_pieces(right))

    def test_exhaustive_over_a_small_alphabet(self):
        # Every 1..3-piece tuple over pieces built from {a, #, \}: distinct tuples, distinct text.
        seen = {}
        for length in (1, 2, 3):
            for tuple_ in itertools.product(["", "a", "#", "\\", "#a", "\\#", "\\\\"], repeat=length):
                key = join_checksum_pieces(list(tuple_))
                self.assertNotIn(key, seen, (tuple_, seen.get(key)))
                seen[key] = tuple_


def split_unescaped(text):
    """Split on the separators that are not escaped (a '#' preceded by an odd run of backslashes is data)."""
    pieces, current, i = [], [], 0
    while i < len(text):
        ch = text[i]
        if ch == "\\" and i + 1 < len(text):
            current.append(text[i:i + 2])
            i += 2
            continue
        if ch == "#":
            pieces.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    pieces.append("".join(current))
    return [unescape(p) for p in pieces]


def unescape(text):
    out, i = [], 0
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text):
            out.append(text[i + 1])
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


class TestSqlWrappers(unittest.TestCase):
    def test_mysql_wrapper_uses_char_92_and_no_backslash_literal(self):
        sql = mysql_escape_piece_sql("`name`")
        self.assertIn("CHAR(92 USING utf8mb4)", sql)
        self.assertIn("REPLACE(REPLACE(`name`,", sql)
        self.assertIn("'#',CONCAT(CHAR(92 USING utf8mb4),'#')", sql)
        # A backslash literal would depend on NO_BACKSLASH_ESCAPES.
        self.assertNotIn("\\", sql)

    def test_clickhouse_wrapper_uses_char_92_and_no_backslash_literal(self):
        sql = clickhouse_escape_piece_sql('toString("name")')
        self.assertIn("replaceAll(replaceAll(toString(\"name\"),char(92),concat(char(92),char(92)))", sql)
        self.assertIn("'#',concat(char(92),'#'))", sql)
        self.assertNotIn("\\", sql)



class TestFormatTag(unittest.TestCase):
    def test_format_is_v2(self):
        self.assertEqual(CHECKSUM_FORMAT, "v2")

    def test_printed_checksum_hashes_the_tagged_aggregate(self):
        self.assertEqual(checksum_from_aggregate(3, 1, 2, 3, 4),
                         hashlib.md5(b"v2#3#1#2#3#4#").hexdigest())


if __name__ == "__main__":
    unittest.main()
