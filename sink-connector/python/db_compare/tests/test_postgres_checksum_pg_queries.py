"""PostgreSQL-side query building time zone test
    python3 -m pytest db_compare/tests/test_postgres_checksum_pg_queries.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import ch_sink_tools.db_compare.postgres_table_checksum as ptc


class TestNaiveTimestampRendering(unittest.TestCase):
    TS_FORMAT = "'YYYY-MM-DD HH24:MI:SS.US'"

    def test_naive_timestamp_has_no_zone_conversion(self):
        expr, skip = ptc.build_pg_select_expression(
            'ts', 'timestamp without time zone', False, 'timestamp'
        )
        self.assertFalse(skip)
        self.assertEqual(expr, f'to_char("ts", {self.TS_FORMAT})')
        self.assertNotIn('AT TIME ZONE', expr)

    def test_nullable_naive_timestamp_has_no_zone_conversion(self):
        expr, _ = ptc.build_pg_select_expression(
            'ts', 'timestamp without time zone', True, 'timestamp'
        )
        self.assertEqual(expr, f"coalesce(to_char(\"ts\", {self.TS_FORMAT}), '')")

    def test_timestamptz_is_rendered_in_utc(self):
        expr, _ = ptc.build_pg_select_expression(
            'ts', 'timestamp with time zone', False, 'timestamptz'
        )
        self.assertEqual(expr, f"to_char(\"ts\" AT TIME ZONE 'UTC', {self.TS_FORMAT})")


if __name__ == '__main__':
    unittest.main()
