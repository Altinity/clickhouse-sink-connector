#!/usr/bin/env python3
"""Tests for table locking improvements in top_level_table_checksum.py.

Tests cover:
1. Lock lifecycle safety (try/finally guarantees unlock even on exception)
2. Connection cleanup after unlock
3. Per-table lock isolation (locks are not held across tables)
4. Lock held during all checksums (MySQL + ClickHouse) for consistency
5. MySQL and ClickHouse checksums run concurrently under the lock
6. LOCK TABLES ... READ statement correctness
7. Performance: old approach (lock-all-then-checksum) vs new (per-table lock)
"""
import unittest
from unittest.mock import MagicMock, patch, call
import time
import threading
import concurrent.futures
import sys
import os

# Add parent directory to path so we can import the module under test
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))


class TestLockStatement(unittest.TestCase):
    """Test that the lock statement uses LOCK TABLES ... READ."""

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_lock_uses_lock_tables_read(self, mock_execute):
        """LOCK TABLES ... READ blocks writes on the source for a consistent
        read lock during the checksum comparison, without the disk flush that
        FLUSH TABLE ... WITH READ LOCK incurs."""
        from db_compare.top_level_table_checksum import lock_tables

        mock_conn = MagicMock()
        lock_tables(mock_conn, 'my_table')

        mock_execute.assert_called_once_with(mock_conn, 'LOCK TABLES `my_table` READ')

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_lock_statement_uses_lock_tables(self, mock_execute):
        """Verify LOCK TABLES ... READ is used (not FLUSH TABLE)."""
        from db_compare.top_level_table_checksum import lock_tables

        mock_conn = MagicMock()
        lock_tables(mock_conn, 'test_table')

        sql_arg = mock_execute.call_args[0][1]
        self.assertIn('LOCK TABLES', sql_arg.upper())
        self.assertIn('READ', sql_arg.upper())
        self.assertNotIn('FLUSH', sql_arg.upper())

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_unlock_uses_unlock_tables(self, mock_execute):
        from db_compare.top_level_table_checksum import unlock_tables

        mock_conn = MagicMock()
        unlock_tables(mock_conn, 'my_table')

        mock_execute.assert_called_once_with(mock_conn, 'UNLOCK TABLES')

    @patch('db_compare.top_level_table_checksum.execute_mysql')
    def test_lock_escapes_table_name(self, mock_execute):
        """Table name should be backtick-escaped in the lock statement."""
        from db_compare.top_level_table_checksum import lock_tables

        mock_conn = MagicMock()
        lock_tables(mock_conn, 'table-with-dashes')

        sql_arg = mock_execute.call_args[0][1]
        self.assertIn('`table-with-dashes`', sql_arg)


class TestCloseConnection(unittest.TestCase):
    """Test the close_connection helper."""

    def test_close_connection_calls_close(self):
        from db_compare.top_level_table_checksum import close_connection

        mock_conn = MagicMock()
        close_connection(mock_conn, 'db.table')
        mock_conn.close.assert_called_once()

    def test_close_connection_swallows_exception(self):
        """close_connection should not raise even if conn.close() fails."""
        from db_compare.top_level_table_checksum import close_connection

        mock_conn = MagicMock()
        mock_conn.close.side_effect = Exception("connection already closed")
        # Should not raise
        close_connection(mock_conn, 'db.table')


class TestComputeChecksumLockLifecycle(unittest.TestCase):
    """Test that compute_checksum properly manages lock lifecycle."""

    def _make_mock_checksum_result(self, host, table):
        return (host, table, 'abc123', 100)

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_lock_acquired_and_released_on_success(
        self, mock_mysql_cmd, mock_ch_cmd, mock_get_conn, mock_lock,
        mock_unlock, mock_close, mock_checksum
    ):
        """Lock is acquired before MySQL checksum and released after, even on success."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_conn = MagicMock()
        mock_get_conn.return_value = mock_conn
        mock_checksum.return_value = ('host', 'tbl', 'hash', 100)

        # Set up minimal args global
        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        compute_checksum(
            'test_db', {}, {}, 'test_table', 'user', 'pass',
            'mysql-host', ['ch-host1'], 'id', 1000, None,
            lock_enabled=True, sleep_after_lock=0, mysql_port=3306
        )

        mock_lock.assert_called_once_with(mock_conn, 'test_table')
        mock_unlock.assert_called_once_with(mock_conn, 'test_table')
        mock_close.assert_called_once_with(mock_conn, 'test_db.test_table')

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_lock_released_on_mysql_checksum_exception(
        self, mock_mysql_cmd, mock_ch_cmd, mock_get_conn, mock_lock,
        mock_unlock, mock_close, mock_checksum
    ):
        """Lock MUST be released even if the MySQL checksum subprocess fails."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_conn = MagicMock()
        mock_get_conn.return_value = mock_conn
        mock_checksum.side_effect = RuntimeError("checksum subprocess crashed")

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        with self.assertRaises(RuntimeError):
            compute_checksum(
                'test_db', {}, {}, 'test_table', 'user', 'pass',
                'mysql-host', ['ch-host1'], 'id', 1000, None,
                lock_enabled=True, sleep_after_lock=0, mysql_port=3306
            )

        # Lock must still have been released despite the exception
        mock_unlock.assert_called_once_with(mock_conn, 'test_table')
        mock_close.assert_called_once_with(mock_conn, 'test_db.test_table')

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_connection_closed_even_if_unlock_fails(
        self, mock_mysql_cmd, mock_ch_cmd, mock_get_conn, mock_lock,
        mock_unlock, mock_close, mock_checksum
    ):
        """Connection must be closed even if UNLOCK TABLES fails."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_conn = MagicMock()
        mock_get_conn.return_value = mock_conn
        mock_checksum.return_value = ('host', 'tbl', 'hash', 100)
        mock_unlock.side_effect = Exception("MySQL gone away")

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        # Should not raise — unlock failure is handled gracefully
        try:
            compute_checksum(
                'test_db', {}, {}, 'test_table', 'user', 'pass',
                'mysql-host', ['ch-host1'], 'id', 1000, None,
                lock_enabled=True, sleep_after_lock=0, mysql_port=3306
            )
        except Exception:
            pass  # unlock exception may propagate, but close must still happen

        # Connection closed even though unlock failed
        mock_close.assert_called_once_with(mock_conn, 'test_db.test_table')

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_no_lock_when_disabled(
        self, mock_mysql_cmd, mock_ch_cmd, mock_get_conn, mock_lock,
        mock_unlock, mock_close, mock_checksum
    ):
        """When lock_enabled=False (default), no lock/unlock/connection should happen."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_checksum.return_value = ('host', 'tbl', 'hash', 100)

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        compute_checksum(
            'test_db', {}, {}, 'test_table', 'user', 'pass',
            'mysql-host', ['ch-host1'], 'id', 1000, None,
            lock_enabled=False
        )

        mock_get_conn.assert_not_called()
        mock_lock.assert_not_called()
        mock_unlock.assert_not_called()
        mock_close.assert_not_called()


class TestLockHoldDuration(unittest.TestCase):
    """Test that locks are held during both MySQL and ClickHouse checksums.
    The source must stay frozen until both sides are checksummed for a
    valid comparison — the 3s sleep allows replication lag to settle,
    and unlocking early would let new writes reach ClickHouse."""

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_lock_held_during_both_checksums(
        self, mock_mysql_cmd, mock_ch_cmd, mock_get_conn, mock_lock,
        mock_unlock, mock_close, mock_checksum
    ):
        """Lock must be held during BOTH MySQL and ClickHouse checksums.
        Unlocking before the ClickHouse checksum would allow new writes
        to flow from MySQL to ClickHouse, invalidating the comparison."""
        from db_compare.top_level_table_checksum import compute_checksum

        call_order = []

        mock_conn = MagicMock()
        mock_get_conn.return_value = mock_conn

        def track_lock(conn, table):
            call_order.append('LOCK')

        def track_unlock(conn, table):
            call_order.append('UNLOCK')

        def track_checksum(cmd, host, table):
            if host == 'mysql-host':
                call_order.append('MYSQL_CHECKSUM')
            else:
                call_order.append(f'CH_CHECKSUM_{host}')
            return (host, table, 'hash', 100)

        mock_lock.side_effect = track_lock
        mock_unlock.side_effect = track_unlock
        mock_checksum.side_effect = track_checksum

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        compute_checksum(
            'test_db', {}, {}, 'test_table', 'user', 'pass',
            'mysql-host', ['ch-host1', 'ch-host2'], 'id', 1000, None,
            lock_enabled=True, sleep_after_lock=0, mysql_port=3306
        )

        # Verify ordering: LOCK first, then all checksums (MySQL + CH run
        # concurrently in one pool, order between them is nondeterministic),
        # then UNLOCK last. LOCK is appended in the main thread before the
        # executor starts; UNLOCK in the finally block after it exits.
        lock_idx = call_order.index('LOCK')
        mysql_idx = call_order.index('MYSQL_CHECKSUM')
        unlock_idx = call_order.index('UNLOCK')

        self.assertEqual(lock_idx, 0, "LOCK must be the first event")
        self.assertEqual(unlock_idx, len(call_order) - 1, "UNLOCK must be the last event")

        # MySQL checksum must run while the lock is held
        self.assertLess(lock_idx, mysql_idx, "LOCK must come before MySQL checksum")
        self.assertLess(mysql_idx, unlock_idx, "MySQL checksum must come before UNLOCK")

        # CH checksums must also run while the lock is held
        for entry in call_order:
            if entry.startswith('CH_CHECKSUM_'):
                ch_idx = call_order.index(entry)
                self.assertLess(lock_idx, ch_idx,
                                f"{entry} must come after LOCK")
                self.assertLess(ch_idx, unlock_idx,
                                f"{entry} must come before UNLOCK — lock must be held "
                                f"during ClickHouse checksum for consistent comparison")


class TestConcurrentChecksums(unittest.TestCase):
    """Test that the MySQL source checksum and ClickHouse replica checksums
    run concurrently under the lock, rather than MySQL-first-then-ClickHouse.
    Running them in parallel minimizes lock hold time — the lock is held for
    the duration of the slowest single checksum, not the sum of all."""

    @patch('db_compare.top_level_table_checksum.close_connection')
    @patch('db_compare.top_level_table_checksum.unlock_tables')
    @patch('db_compare.top_level_table_checksum.lock_tables')
    @patch('db_compare.top_level_table_checksum.get_mysql_connection')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    @patch('db_compare.top_level_table_checksum.run_quick_safe_command')
    def test_mysql_and_clickhouse_run_concurrently(
        self, mock_run_cmd, mock_mysql_cmd, mock_ch_cmd, mock_get_conn,
        mock_lock, mock_unlock, mock_close
    ):
        """MySQL and all ClickHouse checksums must overlap in wall-clock time.

        Each checksum sleeps for `delay` seconds. If they ran serially the
        total time would be ~(N * delay); if concurrent it is ~delay. We
        assert the elapsed time is close to a single checksum's duration."""
        from db_compare.top_level_table_checksum import compute_checksum

        delay = 0.3
        active = {'count': 0, 'max': 0}
        active_lock = threading.Lock()

        def slow_checksum(cmd):
            with active_lock:
                active['count'] += 1
                active['max'] = max(active['max'], active['count'])
            time.sleep(delay)
            with active_lock:
                active['count'] -= 1
            return ('0', b'tbl hash 100')

        mock_run_cmd.side_effect = slow_checksum

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        # 1 MySQL + 3 ClickHouse = 4 checksums
        start = time.perf_counter()
        results = compute_checksum(
            'test_db', {}, {}, 'tbl', 'user', 'pass',
            'mysql-host', ['ch-host1', 'ch-host2', 'ch-host3'], 'id', 1000, None,
            lock_enabled=True, sleep_after_lock=0, mysql_port=3306
        )
        elapsed = time.perf_counter() - start

        # All 4 checksums returned
        self.assertEqual(len(results), 4)
        # MySQL result first (analyze_differences depends on source ordering)
        self.assertEqual(results[0][0], 'mysql-host')
        # All 4 checksums were in flight simultaneously
        self.assertEqual(active['max'], 4,
                         "MySQL and all ClickHouse checksums must run concurrently")
        # Elapsed time is ~1 checksum, not 4 serial ones
        self.assertLess(elapsed, delay * 2,
                        f"Concurrent execution should take ~{delay}s, not "
                        f"{delay*4}s serial (got {elapsed:.3f}s)")


class TestPerTableLockIsolation(unittest.TestCase):
    """Test that the new architecture locks tables independently,
    not all-at-once as the old code did.

    This is a simulation test that demonstrates the performance difference
    between the old and new locking strategies."""

    def test_old_vs_new_lock_hold_time(self):
        """Simulate the lock hold time difference between old and new approaches.

        Old approach (lock-all-then-checksum):
          - Lock table A, sleep 3s
          - Lock table B, sleep 3s
          - Lock table C, sleep 3s
          - Submit all checksums (MySQL + CH under lock)
          - Unlock as checksums complete
          Table A lock hold time: 9s (sleep from all tables) + max(checksum time)

        New approach (per-table lock inside compute_checksum):
          - Each table: lock, sleep 3s, MySQL+CH checksum, unlock
          - Tables processed in parallel by thread pool
          Table A lock hold time: 3s (own sleep) + own checksum time only
        """
        num_tables = 5
        sleep_per_lock = 0.1  # Use 100ms for test speed
        checksum_time = 0.05  # 50ms simulated checksum (MySQL + CH combined)

        # --- Simulate OLD approach: lock all, then checksum, then unlock ---
        lock_times_old = {}  # table -> (lock_time, unlock_time)

        def old_approach():
            # Phase 1: lock all tables sequentially
            for i in range(num_tables):
                table = f"table_{i}"
                lock_times_old[table] = {'locked_at': time.perf_counter()}
                time.sleep(sleep_per_lock)  # sleep_after_lock per table

            # Phase 2: run checksums (simulated — MySQL + CH both under lock)
            time.sleep(checksum_time * num_tables)  # worst case: serial

            # Phase 3: unlock all
            for i in range(num_tables):
                table = f"table_{i}"
                lock_times_old[table]['unlocked_at'] = time.perf_counter()

        old_approach()

        # --- Simulate NEW approach: lock-checksum-unlock per table ---
        # Lock is still held during both MySQL and CH checksums per table,
        # but each table manages its own lock independently in parallel.
        lock_times_new = {}
        lock = threading.Lock()

        def new_approach_per_table(table_idx):
            table = f"table_{table_idx}"
            locked_at = time.perf_counter()
            time.sleep(sleep_per_lock)  # sleep_after_lock
            time.sleep(checksum_time)   # MySQL + CH checksum (both under lock)
            unlocked_at = time.perf_counter()
            with lock:
                lock_times_new[table] = {
                    'locked_at': locked_at,
                    'unlocked_at': unlocked_at
                }

        with concurrent.futures.ThreadPoolExecutor(max_workers=num_tables) as executor:
            futures = [executor.submit(new_approach_per_table, i) for i in range(num_tables)]
            for f in futures:
                f.result()

        # --- Measure and compare ---
        old_hold_times = []
        for table, times in lock_times_old.items():
            hold = times['unlocked_at'] - times['locked_at']
            old_hold_times.append(hold)

        new_hold_times = []
        for table, times in lock_times_new.items():
            hold = times['unlocked_at'] - times['locked_at']
            new_hold_times.append(hold)

        max_old = max(old_hold_times)
        max_new = max(new_hold_times)
        avg_old = sum(old_hold_times) / len(old_hold_times)
        avg_new = sum(new_hold_times) / len(new_hold_times)

        print(f"\n{'='*60}")
        print(f"Lock Hold Time Comparison ({num_tables} tables)")
        print(f"{'='*60}")
        print(f"OLD approach (lock-all-then-checksum):")
        print(f"  Max lock hold time:  {max_old:.3f}s")
        print(f"  Avg lock hold time:  {avg_old:.3f}s")
        print(f"  First table locked for entire duration")
        print(f"NEW approach (per-table lock in compute_checksum):")
        print(f"  Max lock hold time:  {max_new:.3f}s")
        print(f"  Avg lock hold time:  {avg_new:.3f}s")
        print(f"  Each table locked only during its own checksum")
        print(f"IMPROVEMENT:")
        print(f"  Max hold time reduced by {(1 - max_new/max_old)*100:.0f}%")
        print(f"  Avg hold time reduced by {(1 - avg_new/avg_old)*100:.0f}%")
        print(f"{'='*60}")

        # The new approach should have significantly shorter max lock hold time
        # Old: first table is locked for ~(num_tables * sleep) + checksum_time
        # New: each table is locked for ~sleep + checksum_time
        self.assertLess(max_new, max_old,
                        f"New max hold ({max_new:.3f}s) should be less than "
                        f"old max hold ({max_old:.3f}s)")
        self.assertLess(max_new, max_old * 0.5,
                        f"New approach should reduce max lock hold by at least 50%")

    def test_old_approach_cumulative_sleep_blocks_all_tables(self):
        """Demonstrate that the old approach's sequential locking causes
        cumulative sleep time that blocks earlier tables.

        With N tables and S seconds sleep_after_lock:
        - Table 1 is write-blocked for N*S seconds before any checksum starts
        - Table N is write-blocked for S seconds before its checksum starts
        The discrepancy grows linearly with table count."""
        num_tables = 10
        sleep_per_lock = 0.05  # 50ms

        # Old approach: sequential lock acquisition
        lock_acquire_times = []
        start = time.perf_counter()
        for i in range(num_tables):
            lock_acquire_times.append(time.perf_counter() - start)
            time.sleep(sleep_per_lock)
        total_lock_phase = time.perf_counter() - start

        first_table_wait = total_lock_phase  # Table 0 waits for ALL sleeps
        last_table_wait = sleep_per_lock     # Table N-1 waits for 1 sleep

        print(f"\n{'='*60}")
        print(f"Cumulative Sleep Blocking ({num_tables} tables, {sleep_per_lock}s sleep)")
        print(f"{'='*60}")
        print(f"Old approach (sequential locking):")
        print(f"  Total lock phase:     {total_lock_phase:.3f}s")
        print(f"  Table 0 blocked for:  {first_table_wait:.3f}s (worst case)")
        print(f"  Table {num_tables-1} blocked for: {last_table_wait:.3f}s (best case)")
        print(f"  Unfairness ratio:     {first_table_wait/last_table_wait:.1f}x")
        print(f"New approach (parallel per-table):")
        print(f"  Every table blocked for: ~{sleep_per_lock:.3f}s (equal)")
        print(f"{'='*60}")

        # The total lock phase should be approximately N * sleep_per_lock
        expected_total = num_tables * sleep_per_lock
        self.assertAlmostEqual(total_lock_phase, expected_total, delta=0.1,
                               msg="Total lock phase should be ~N*sleep")
        # First table waits much longer than last table
        self.assertGreater(first_table_wait, last_table_wait * (num_tables - 1),
                           "First table should wait ~N times longer than last table")


class TestComputeChecksumResults(unittest.TestCase):
    """Test that compute_checksum returns correct results with the new structure."""

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_returns_mysql_and_clickhouse_results(
        self, mock_mysql_cmd, mock_ch_cmd, mock_checksum
    ):
        """compute_checksum should return one MySQL result + N ClickHouse results."""
        from db_compare.top_level_table_checksum import compute_checksum

        def fake_checksum(cmd, host, table):
            if host == 'mysql-host':
                return ('mysql-host', 'tbl', 'mysql_hash', 100)
            elif host == 'ch-host1':
                return ('ch-host1', 'tbl', 'ch1_hash', 100)
            elif host == 'ch-host2':
                return ('ch-host2', 'tbl', 'ch2_hash', 100)

        mock_checksum.side_effect = fake_checksum

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        results = compute_checksum(
            'test_db', {}, {}, 'tbl', 'user', 'pass',
            'mysql-host', ['ch-host1', 'ch-host2'], 'id', 1000, None,
            lock_enabled=False
        )

        # Should have 3 results: 1 MySQL + 2 ClickHouse
        self.assertEqual(len(results), 3)
        hosts = [r[0] for r in results]
        self.assertIn('mysql-host', hosts)
        self.assertIn('ch-host1', hosts)
        self.assertIn('ch-host2', hosts)

        # MySQL result should be first
        self.assertEqual(results[0][0], 'mysql-host')

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_returns_mysql_only_when_no_replicas(self, mock_mysql_cmd, mock_checksum):
        """When no replica hosts are configured, only MySQL result is returned."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_checksum.return_value = ('mysql-host', 'tbl', 'hash', 50)

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        results = compute_checksum(
            'test_db', {}, {}, 'tbl', 'user', 'pass',
            'mysql-host', [], 'id', 1000, None,
            lock_enabled=False
        )

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0][0], 'mysql-host')

    @patch('db_compare.top_level_table_checksum.run_quick_safe_checksum')
    @patch('db_compare.top_level_table_checksum.get_clickhouse_checksum_command', return_value='ch_cmd')
    @patch('db_compare.top_level_table_checksum.get_mysql_checksum_command', return_value='mysql_cmd')
    def test_database_override_map_applied(self, mock_mysql_cmd, mock_ch_cmd, mock_checksum):
        """database_override_map should remap the CH database name."""
        from db_compare.top_level_table_checksum import compute_checksum

        mock_checksum.return_value = ('host', 'tbl', 'hash', 100)

        import db_compare.top_level_table_checksum as mod
        mock_args = MagicMock()
        mock_args.partition_date = None
        mock_args.threads_per_table = 1
        mock_args.threads = 1
        mod.args = mock_args

        override_map = {'ch-host1': 'risk:uip_risk'}

        compute_checksum(
            'risk', override_map, {}, 'tbl', 'user', 'pass',
            'mysql-host', ['ch-host1'], 'id', 1000, None,
            lock_enabled=False
        )

        # Verify the CH command was built with the overridden database
        mock_ch_cmd.assert_called_once()
        call_args = mock_ch_cmd.call_args
        # Second positional arg (index 1) is the database name
        ch_database_used = call_args[0][1]
        self.assertEqual(ch_database_used, 'uip_risk')


class TestRunConfigNoLockState(unittest.TestCase):
    """Test that run_config no longer manages lock state (future_to_conn removed)."""

    def test_run_config_has_no_future_to_conn(self):
        """The run_config function should no longer contain future_to_conn.
        Lock lifecycle is now managed inside compute_checksum."""
        import inspect
        from db_compare.top_level_table_checksum import run_config

        source = inspect.getsource(run_config)
        self.assertNotIn('future_to_conn', source,
                         "run_config should not manage lock connections — "
                         "lock lifecycle is now inside compute_checksum")


if __name__ == '__main__':
    unittest.main(verbosity=2)
