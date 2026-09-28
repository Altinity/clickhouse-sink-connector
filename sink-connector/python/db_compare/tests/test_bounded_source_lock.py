#!/usr/bin/env python3
"""Tests for the bounded source lock on the top-level table checksum.

A per-table lock_wait_timeout bounds how long the tool waits for the source
READ lock, MySQL errno 1205 is classified as a LockAcquisitionError, and one
un-lockable (continuously-written) table is skipped-and-warned rather than
aborting the whole checksum run.
"""
import unittest
from unittest.mock import MagicMock, patch
import sys
import os

# Add parent directories to path so we can import the module under test
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))


class _FakeDriverError(Exception):
    """Mimics pymysql.err.OperationalError: numeric code in args[0]."""
    def __init__(self, code, message):
        super().__init__(code, message)


class _FakeWrappedError(Exception):
    """Mimics a SQLAlchemy error wrapping a driver error under .orig."""
    def __init__(self, orig):
        super().__init__(str(orig))
        self.orig = orig


class TestIsLockWaitTimeout(unittest.TestCase):
    def test_matches_message_text(self):
        from db_compare.top_level_table_checksum import _is_lock_wait_timeout
        self.assertTrue(_is_lock_wait_timeout(Exception("Lock wait timeout exceeded")))

    def test_matches_structured_code_without_message(self):
        """errno 1205 in args[0] classifies even if the text lacks the message."""
        from db_compare.top_level_table_checksum import _is_lock_wait_timeout
        self.assertTrue(_is_lock_wait_timeout(_FakeDriverError(1205, "whatever")))

    def test_matches_sqlalchemy_wrapped_code(self):
        from db_compare.top_level_table_checksum import _is_lock_wait_timeout
        self.assertTrue(_is_lock_wait_timeout(
            _FakeWrappedError(_FakeDriverError(1205, "Lock wait timeout exceeded"))))

    def test_does_not_match_other_errors(self):
        from db_compare.top_level_table_checksum import _is_lock_wait_timeout
        self.assertFalse(_is_lock_wait_timeout(Exception("table does not exist")))

    def test_does_not_match_table_name_containing_1205(self):
        """Blocker from review: a non-timeout error on a table named with 1205,
        or any message merely containing those digits, must NOT be misclassified
        as a skippable lock timeout (which would be silently swallowed)."""
        from db_compare.top_level_table_checksum import _is_lock_wait_timeout
        # errno 1146 (table missing) on table `trades_1205`
        self.assertFalse(_is_lock_wait_timeout(
            _FakeDriverError(1146, "Table 'prod.trades_1205' doesn't exist")))
        # errno 1142 (access denied) with 1205 in the SQL text
        self.assertFalse(_is_lock_wait_timeout(_FakeWrappedError(
            _FakeDriverError(1142, "command denied [SQL: LOCK TABLES `orders_1205` READ]"))))
        # bare string mentioning 1205 but no timeout, no numeric code
        self.assertFalse(_is_lock_wait_timeout(Exception("ticket 1205 unrelated failure")))


class TestLockTables(unittest.TestCase):
    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_sets_session_timeout_before_lock(self, mock_execute):
        """SET SESSION lock_wait_timeout must run BEFORE the LOCK TABLES."""
        from db_compare.top_level_table_checksum import lock_tables
        lock_tables(MagicMock(), 'my_table', lock_wait_timeout=30)
        stmts = [c.args[1] for c in mock_execute.call_args_list]
        self.assertEqual(stmts[0], 'SET SESSION lock_wait_timeout = 30')
        self.assertEqual(stmts[1], 'LOCK TABLES `my_table` READ')

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_no_session_var_when_timeout_none(self, mock_execute):
        from db_compare.top_level_table_checksum import lock_tables
        lock_tables(MagicMock(), 'my_table')
        stmts = [c.args[1] for c in mock_execute.call_args_list]
        self.assertTrue(all('lock_wait_timeout' not in s for s in stmts))
        self.assertIn('LOCK TABLES `my_table` READ', stmts)

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_timeout_raises_lock_acquisition_error(self, mock_execute):
        from db_compare.top_level_table_checksum import (
            lock_tables, LockAcquisitionError)

        def effect(conn, sql):
            if sql.startswith('LOCK TABLES'):
                raise Exception("(1205, 'Lock wait timeout exceeded')")
        mock_execute.side_effect = effect
        with self.assertRaises(LockAcquisitionError):
            lock_tables(MagicMock(), 'hot_table', lock_wait_timeout=5)

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_non_timeout_error_propagates_unchanged(self, mock_execute):
        from db_compare.top_level_table_checksum import (
            lock_tables, LockAcquisitionError)

        def effect(conn, sql):
            if sql.startswith('LOCK TABLES'):
                raise ValueError("table does not exist")
        mock_execute.side_effect = effect
        with self.assertRaises(ValueError):
            lock_tables(MagicMock(), 'missing_table', lock_wait_timeout=5)
        try:
            lock_tables(MagicMock(), 'missing_table', lock_wait_timeout=5)
        except LockAcquisitionError:
            self.fail("non-timeout error must not become LockAcquisitionError")
        except ValueError:
            pass


class TestCliContract(unittest.TestCase):
    """Lock the CLI contract so the documented default cannot silently drift
    from the code (the exact mismatch flagged in review): skipping an
    un-lockable table is the DEFAULT, --fail_on_lock_timeout is the opt-out."""

    def _help_text(self):
        import subprocess
        python_root = os.path.abspath(
            os.path.join(os.path.dirname(__file__), '..', '..'))
        script = os.path.join('db_compare', 'top_level_table_checksum.py')
        env = dict(os.environ)
        # The tool does `from db.mysql import *`, so the python root (which
        # contains the `db` package) must be importable -- exactly how the
        # deployed pytools module runs it.
        env['PYTHONPATH'] = python_root + os.pathsep + env.get('PYTHONPATH', '')
        out = subprocess.run(
            [sys.executable, script, '--help'],
            cwd=python_root, capture_output=True, text=True, env=env)
        return (out.stdout or '') + (out.stderr or '')

    def test_fail_on_lock_timeout_flag_exists(self):
        self.assertIn('--fail_on_lock_timeout', self._help_text())

    def test_lock_wait_timeout_flag_exists(self):
        self.assertIn('--lock_wait_timeout', self._help_text())

    def test_no_allow_skip_flag(self):
        """The opt-out is --fail_on_lock_timeout; skip must be the default,
        so the inverted --allow_lock_timeout_skip must NOT be present."""
        self.assertNotIn('--allow_lock_timeout_skip', self._help_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
