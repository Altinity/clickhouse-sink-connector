"""Tests that the checksum tooling ignores the connector-generated row key.

A source table with no PRIMARY KEY and no UNIQUE key gets a generated
``_row_key`` column in ClickHouse so ReplacingMergeTree can tell its rows
apart. That column exists ONLY in ClickHouse -- MySQL has nothing to hash
against it -- so it has to be excluded from the comparison for the same reason
``_version`` and ``is_deleted`` already are.

Left in, the two sides hash different column sets and every keyless table is
reported as a checksum MISMATCH by the Jenkins compare jobs, which is the same
signal a genuine divergence produces. That is the failure these tests pin: with
``_row_key`` removed from the exclusion defaults, the first two cases fail.

The last case is the opposite risk. A source table may itself declare a column
called ``_row_key``; the generated one is then renamed to ``__row_key``. The
source column is real data and must stay in the comparison, or a genuine
divergence in it would go unreported.
"""

import os
import sys
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from db_compare.clickhouse_table_checksum import filter_excluded_columns
from db_compare.top_level_table_checksum import CONNECTOR_GENERATED_COLUMNS


def _column(name, data_type="String"):
    """One row of ClickHouse column metadata: (name, type, nullable, scale)."""
    return (name, data_type, 0, None)


def _kept(columns_metadata, excluded):
    return [row[0] for row in filter_excluded_columns(columns_metadata, excluded)]


class RowKeyExclusionTestCase(unittest.TestCase):

    def test_generated_row_key_is_excluded(self):
        """The generated column must not reach the checksum."""
        kept = _kept(
            [_column("a"), _column("b"), _column("_row_key"),
             _column("_version"), _column("is_deleted")],
            CONNECTOR_GENERATED_COLUMNS,
        )
        self.assertEqual(["a", "b"], kept)

    def test_disambiguated_generated_row_key_is_excluded(self):
        """The renamed generated column must not reach the checksum either.

        When the source declares ``_row_key``, the generated column becomes
        ``__row_key``. Both appear in the table; only the generated one is
        ClickHouse-only.
        """
        kept = _kept(
            [_column("_row_key"), _column("v"), _column("__row_key"),
             _column("_version"), _column("is_deleted")],
            CONNECTOR_GENERATED_COLUMNS,
        )
        self.assertNotIn("__row_key", kept)

    def test_source_column_named_row_key_is_still_compared(self):
        """A SOURCE column called _row_key is data and must stay compared.

        Dropping it would make a real divergence in that column invisible --
        a false PASS, which is worse than the false mismatch above.
        """
        kept = _kept(
            [_column("_row_key"), _column("v"), _column("__row_key"),
             _column("_version"), _column("is_deleted")],
            CONNECTOR_GENERATED_COLUMNS,
        )
        self.assertEqual(["_row_key", "v"], kept)

    def test_source_double_underscore_row_key_is_still_compared(self):
        """A source ``__row_key`` must not invert which column is dropped.

        The exclusion rule resolves a clash by underscore depth, so the
        generated column has to be strictly DEEPER than every source column of
        the row-key shape. If the generator picked ``_row_key`` while the
        source already had ``__row_key``, the rule would keep the generated
        column and drop the real one -- hashing a ClickHouse-only value on one
        side and hiding any divergence in the user's data on the other.

        ``resolveRowKeyColumnName`` therefore skips past the deepest source
        variant, which for this table means ``___row_key``.
        """
        kept = _kept(
            [_column("__row_key"), _column("v"), _column("___row_key", "UInt64"),
             _column("_version"), _column("is_deleted")],
            CONNECTOR_GENERATED_COLUMNS,
        )
        self.assertEqual(["__row_key", "v"], kept)

    def test_keyed_table_is_unaffected(self):
        """A table with its own primary key has no generated row key."""
        kept = _kept(
            [_column("id"), _column("v"), _column("_version"), _column("is_deleted")],
            CONNECTOR_GENERATED_COLUMNS,
        )
        self.assertEqual(["id", "v"], kept)


if __name__ == "__main__":
    unittest.main()
