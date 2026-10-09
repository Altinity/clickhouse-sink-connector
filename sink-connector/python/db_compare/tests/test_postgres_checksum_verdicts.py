#!/usr/bin/env python3
"""Verdicts of the PostgreSQL -> ClickHouse verification tools (spec 13.07).

Offline: every PostgreSQL and ClickHouse helper is patched; no connection is
opened. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_postgres_checksum_verdicts.py
"""
import argparse
import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import ch_sink_tools.db_compare.top_level_postgres_checksum as tlp  # noqa: E402
import ch_sink_tools.db_compare.postgres_table_checksum as ptc  # noqa: E402
import ch_sink_tools.db_compare.postgres_table_count as ptn  # noqa: E402

DIGEST = '0123456789abcdef0123456789abcdef'
_COMPARE_TABLE = tlp.compare_table  # run_legacy() patches the module attribute


def col(name, pg_type='integer', nullable=False, udt_name=None):
    return {'column_name': name, 'pg_type': pg_type, 'nullable': nullable,
            'udt_name': udt_name or pg_type}


def run_compare(pg_cols=None, ch_cols=None, pg_cnt=1000, ch_cnt=1000,
                pg_checksum=DIGEST, ch_checksum=DIGEST, no_checksum=False,
                skip_columns=None, unreadable=(), ch_columns_error=None):
    """Run compare_table() over patched helpers; return the ChecksumResult."""
    pg_cols = pg_cols if pg_cols is not None else [col('id'), col('name', 'text')]
    ch_cols = ch_cols if ch_cols is not None else [c['column_name'] for c in pg_cols]

    def fake_execute_sql(conn, sql):
        if ch_columns_error is not None and 'system.columns' in sql:
            raise ch_columns_error
        return ([(c,) for c in ch_cols], len(ch_cols))

    with patch.multiple(
            tlp,
            get_postgres_connection=MagicMock(),
            clickhouse_connection=MagicMock(),
            ch_table_exists=MagicMock(return_value=True),
            get_table_row_count=MagicMock(return_value=pg_cnt),
            get_table_pk=MagicMock(return_value=['id']),
            get_table_columns=MagicMock(return_value=pg_cols),
            get_pg_unreadable_columns=MagicMock(return_value=list(unreadable)),
            execute_sql=MagicMock(side_effect=fake_execute_sql),
            execute_pg=MagicMock(return_value=[{'cnt': pg_cnt}]),
            get_ch_count=MagicMock(return_value=ch_cnt),
            get_postgres_table_checksum=MagicMock(return_value=pg_checksum),
            get_ch_checksum=MagicMock(return_value=ch_checksum)):
        return _COMPARE_TABLE(
            'orders',
            'pg-host', 'u', 'p', 5432, 'db1', 'public',
            'ch-host', 'default', '', 9000, 'replica', False,
            set(tlp.DEFAULT_CH_EXCLUDE_COLUMNS),
            100000, 10000000, 100000, 1,
            0.0001, 100,
            False, False, no_checksum,
            skip_columns=skip_columns,
        )


def make_result(table, status, checksum_match=None, delta=0, **kw):
    return tlp.ChecksumResult(
        table=table, tier=1, pg_count=1000, ch_count=1000 + delta,
        count_delta=delta, count_delta_pct=abs(delta) / 1000.0,
        checksum_match=checksum_match, pg_checksum=None, ch_checksum=None,
        pg_max_pk=None, ch_max_pk=None, pg_max_ts=None, ch_max_ts=None,
        status=status, **kw)


def summary(results, **kw):
    out = io.StringIO()
    now = datetime.now(timezone.utc)
    with redirect_stdout(out):
        code = tlp.print_summary(results, now, now, '0/0', 0, 'db1', 'replica', **kw)
    return code, out.getvalue()


class TestUncomputedChecksumIsError(unittest.TestCase):
    """D-13.07-1: a checksum that is None is ERROR, never PASS."""

    def test_ch_checksum_none_is_error(self):
        r = run_compare(ch_checksum=None)
        self.assertEqual(r.status, 'ERROR')
        self.assertIn('ClickHouse checksum not computed', r.detail)

    def test_pg_checksum_none_is_error(self):
        r = run_compare(pg_checksum=None)
        self.assertEqual(r.status, 'ERROR')
        self.assertIn('PostgreSQL checksum not computed', r.detail)

    def test_no_comparable_column_is_error(self):
        cols = [col('doc', 'jsonb'), col('blob', 'bytea')]
        r = run_compare(pg_cols=cols, ch_checksum=None)
        self.assertEqual(r.status, 'ERROR')
        self.assertIn('no comparable column', r.detail)

    def test_ch_columns_read_failure_is_error(self):
        r = run_compare(ch_columns_error=RuntimeError('system.columns unavailable'))
        self.assertEqual(r.status, 'ERROR')

    def test_ch_count_failure_is_error(self):
        r = run_compare(pg_cnt=0, ch_cnt=-1, no_checksum=True)
        self.assertEqual(r.status, 'ERROR')
        self.assertIn('ClickHouse count query failed', r.detail)

    def test_equal_checksums_and_counts_pass(self):
        r = run_compare()
        self.assertEqual(r.status, 'PASS')
        self.assertIs(r.checksum_match, True)

    def test_run_with_failed_ch_checksum_exits_nonzero(self):
        code, out = run_legacy(compare_kwargs=dict(ch_checksum=None))
        self.assertEqual(code, 1)
        self.assertNotIn('tables match', out)


class TestWarnIsVisible(unittest.TestCase):
    """D-13.07-2: WARN is counted, never 'all match', and fails unless allowed."""

    def test_warn_within_thresholds_still_warn(self):
        r = run_compare(pg_cnt=2000000, ch_cnt=1999950, no_checksum=True)
        self.assertEqual(r.status, 'WARN')

    def test_warn_fails_run_by_default(self):
        code, out = summary([make_result('a', 'PASS', True),
                             make_result('b', 'WARN', None, delta=-50)])
        self.assertEqual(code, 1)
        self.assertIn('WARN=1', out)
        self.assertNotIn('all 2 tables match', out)
        self.assertIn('RESULT: FAIL', out)
        self.assertIn('Exit code: 1', out)

    def test_warn_allowed_by_explicit_option(self):
        code, out = summary([make_result('a', 'PASS', True),
                             make_result('b', 'WARN', True, delta=-5)],
                            allow_count_delta_warn=True)
        self.assertEqual(code, 0)
        self.assertIn('PASS WITH WARNINGS', out)
        self.assertNotIn('tables match', out)

    def test_error_and_extra_rows_fail(self):
        for status in ('ERROR', 'EXTRA', 'MISSING', 'FAIL'):
            code, out = summary([make_result('a', 'PASS', True),
                                 make_result('b', status)])
            self.assertEqual(code, 1, status)
            self.assertNotIn('tables match', out)

    def test_no_checksum_pass_does_not_claim_match(self):
        code, out = summary([make_result('a', 'PASS')], no_checksum=True)
        self.assertEqual(code, 0)
        self.assertNotIn('tables match', out)
        self.assertIn('checksums not compared', out)

    def test_all_pass_says_all_match(self):
        code, out = summary([make_result('a', 'PASS', True)])
        self.assertEqual(code, 0)
        self.assertIn('RESULT: PASS — all 1 tables match', out)

    def test_run_no_checksum_delta_within_thresholds_exits_nonzero(self):
        code, out = run_legacy(no_checksum=True,
                               compare_kwargs=dict(pg_cnt=2000000, ch_cnt=1999950))
        self.assertEqual(code, 1)
        self.assertNotIn('tables match', out)

    def test_run_with_allow_option_exits_zero(self):
        code, out = run_legacy(no_checksum=True,
                               checksum_cfg={'allow_count_delta_warn': True},
                               compare_kwargs=dict(pg_cnt=2000000, ch_cnt=1999950))
        self.assertEqual(code, 0)
        self.assertIn('PASS WITH WARNINGS', out)

    def test_string_true_does_not_allow_warn(self):
        code, _ = run_legacy(no_checksum=True,
                             checksum_cfg={'allow_count_delta_warn': 'true'},
                             compare_kwargs=dict(pg_cnt=2000000, ch_cnt=1999950))
        self.assertEqual(code, 1)


class TestMissingColumns(unittest.TestCase):
    """D-13.07-3: PG columns absent from ClickHouse are a MISMATCH."""

    def test_pg_column_missing_in_ch_fails(self):
        r = run_compare(pg_cols=[col('id'), col('b', 'text')], ch_cols=['id'])
        self.assertEqual(r.status, 'FAIL')
        self.assertEqual(r.missing_ch_columns, ['b'])
        self.assertIn("columns missing in ClickHouse: ['b']", r.detail)
        _, out = summary([r])
        self.assertIn('MISMATCH', out)
        self.assertNotIn('tables match', out)

    def test_missing_column_in_skip_list_is_not_a_failure(self):
        r = run_compare(pg_cols=[col('id'), col('b', 'text')], ch_cols=['id'],
                        skip_columns=['b'])
        self.assertEqual(r.status, 'PASS')
        self.assertEqual(r.missing_ch_columns, [])

    def test_missing_column_fails_even_without_checksum(self):
        r = run_compare(pg_cols=[col('id'), col('b', 'text')], ch_cols=['id'],
                        no_checksum=True)
        self.assertEqual(r.status, 'FAIL')


class TestCoverageGaps(unittest.TestCase):
    """D-13.07-4: hidden PG tables/columns and CH-only tables are reported."""

    def test_unreadable_column_is_error(self):
        r = run_compare(unreadable=['secret'])
        self.assertEqual(r.status, 'ERROR')
        self.assertIn("['secret']", r.detail)

    def test_unreadable_column_in_skip_list_is_ignored(self):
        r = run_compare(unreadable=['secret'], skip_columns=['secret'])
        self.assertEqual(r.status, 'PASS')

    def test_unreadable_column_query_uses_has_column_privilege(self):
        with patch.object(tlp, 'execute_pg', return_value=[{'column_name': 'x'}]) as ep:
            self.assertEqual(tlp.get_pg_unreadable_columns(MagicMock(), 'public', 't'), ['x'])
        sql, params = ep.call_args[0][1], ep.call_args[0][2]
        self.assertIn("has_column_privilege(c.oid, a.attnum, 'SELECT')", sql)
        self.assertEqual(params, ('public', 't'))

    def test_hidden_pg_table_and_ch_only_table_are_reported(self):
        def fake_execute_pg(conn, sql, params=None):
            if 'pg_catalog.pg_class' in sql:
                self.assertIn("has_table_privilege(c.oid, 'SELECT')", sql)
                self.assertEqual(params, ('public', '^o'))
                return [{'table_name': 'orders', 'can_select': True},
                        {'table_name': 'orders_secret', 'can_select': False},
                        {'table_name': 'orders_skip', 'can_select': False}]
            # PG-side regex filter over the ClickHouse candidates
            self.assertIn('unnest', sql)
            self.assertEqual(params[1], '^o')
            return [{'table_name': t} for t in params[0] if t.startswith('o')]

        ch_rows = [('orders',), ('orders_old',), ('offsets',), ('other_skip',),
                   ('zz_unrelated',)]
        with patch.object(tlp, 'execute_pg', side_effect=fake_execute_pg), \
                patch.object(tlp, 'execute_sql', return_value=(ch_rows, len(ch_rows))):
            rows = tlp.find_coverage_gaps(
                MagicMock(), MagicMock(), 'public', 'replica', '^o', ['orders'],
                {'orders_skip', 'other_skip'}, offset_db='replica',
                offset_table='offsets')
        got = {(r.table, r.status) for r in rows}
        self.assertEqual(got, {('orders_secret', 'ERROR'), ('orders_old', 'EXTRA')})
        secret = [r for r in rows if r.table == 'orders_secret'][0]
        self.assertIn('not visible to the checksum user', secret.detail)

    def test_run_reports_ch_only_table_and_fails(self):
        code, out = run_legacy(ch_only=['legacy_orders'])
        self.assertEqual(code, 1)
        self.assertIn('EXTRA', out)
        self.assertIn('legacy_orders', out)

    def test_run_reports_hidden_pg_table_and_fails(self):
        code, out = run_legacy(hidden=[('payroll', False)])
        self.assertEqual(code, 1)
        self.assertIn('payroll', out)
        self.assertIn('not visible to the checksum user', out)


SET_SESSION_CHARACTERISTICS_SQL = (
    "SET SESSION CHARACTERISTICS AS TRANSACTION "
    "ISOLATION LEVEL REPEATABLE READ, READ ONLY"
)


class _AutocommitTrackingConn(MagicMock):
    """A MagicMock whose ``autocommit`` attribute assignments are logged.

    pg8000 (unlike psycopg2) has no ``set_session()``; the REPEATABLE READ /
    READ ONLY snapshot is established by flipping ``autocommit`` around a
    ``SET SESSION CHARACTERISTICS ...`` statement (see
    ``begin_repeatable_read_snapshot``). These tests need to see the exact
    True-then-False sequence, which a plain MagicMock attribute does not
    record.
    """

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        object.__setattr__(self, 'autocommit_history', [])
        object.__setattr__(self, '_autocommit', None)

    @property
    def autocommit(self):
        return self._autocommit

    @autocommit.setter
    def autocommit(self, value):
        object.__setattr__(self, '_autocommit', value)
        self.autocommit_history.append(value)


class TestRepeatableReadSnapshot(unittest.TestCase):
    """D-13.07-5: the snapshot transaction really is REPEATABLE READ (and READ ONLY)."""

    @staticmethod
    def _fake_execute_pg(isolation='repeatable read', read_only='on'):
        def fake(conn, sql, params=None):
            if sql == 'SHOW transaction_isolation':
                return [{'transaction_isolation': isolation}]
            if sql == 'SHOW transaction_read_only':
                return [{'transaction_read_only': read_only}]
            return []
        return fake

    def test_helper_sets_session_characteristics_and_verifies(self):
        conn = _AutocommitTrackingConn()
        with patch.object(tlp, 'execute_pg',
                          side_effect=self._fake_execute_pg()) as ep:
            tlp.begin_repeatable_read_snapshot(conn)
        # autocommit must go True (so the SET runs outside any transaction
        # and takes effect immediately) then False (so the next statement's
        # implicit BEGIN inherits the new session defaults).
        self.assertEqual(conn.autocommit_history, [True, False])
        conn.cursor.return_value.execute.assert_called_once_with(
            SET_SESSION_CHARACTERISTICS_SQL)
        conn.cursor.return_value.close.assert_called_once()
        called_sqls = [c.args[1] for c in ep.call_args_list]
        self.assertEqual(
            called_sqls, ['SHOW transaction_isolation', 'SHOW transaction_read_only'])

    def test_helper_raises_when_level_is_wrong(self):
        with patch.object(tlp, 'execute_pg',
                          side_effect=self._fake_execute_pg(isolation='read committed')):
            with self.assertRaises(RuntimeError):
                tlp.begin_repeatable_read_snapshot(_AutocommitTrackingConn())

    def test_helper_raises_when_not_read_only(self):
        with patch.object(tlp, 'execute_pg',
                          side_effect=self._fake_execute_pg(read_only='off')):
            with self.assertRaises(RuntimeError):
                tlp.begin_repeatable_read_snapshot(_AutocommitTrackingConn())

    def test_snapshot_run_opens_repeatable_read_via_set_session_characteristics(self):
        pg_conn = _AutocommitTrackingConn()
        executed = []
        pg_conn.cursor.return_value.execute.side_effect = \
            lambda sql, *a: executed.append(sql)

        config = base_config({'snapshot_mode': True})
        with patch.multiple(
                tlp,
                get_postgres_connection=MagicMock(return_value=pg_conn),
                execute_pg=MagicMock(side_effect=self._fake_execute_pg()),
                get_standby_lsn=MagicMock(return_value=('0/10', 16)),
                get_tables=MagicMock(return_value=['orders']),
                clickhouse_connection=MagicMock(),
                find_coverage_gaps=MagicMock(return_value=[]),
                get_table_columns=MagicMock(return_value=[]),
                ch_table_exists=MagicMock(return_value=False),
                compare_table=MagicMock(return_value=make_result('orders', 'PASS', True))):
            with redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as cm:
                tlp.run_config(config, argparse.Namespace(table=None, no_checksum=False))
        self.assertEqual(cm.exception.code, 0)
        self.assertIn(SET_SESSION_CHARACTERISTICS_SQL, executed)
        self.assertFalse([s for s in executed if 'BEGIN' in s.upper()])
        self.assertEqual(pg_conn.autocommit_history, [True, False])


class TestStandaloneExitCodes(unittest.TestCase):
    """D-13.07-23: ch-pg-checksum and ch-pg-count exit non-zero on table failure."""

    def _run_main(self, module, argv):
        with patch.object(sys, 'argv', argv), \
                patch.object(module, 'get_postgres_connection', MagicMock()), \
                patch.object(module, 'get_tables', MagicMock(return_value=['t1', 't2'])), \
                patch('logging.getLogger', return_value=MagicMock()):
            with self.assertRaises(SystemExit) as cm:
                module.main()
        return cm.exception.code

    def test_pg_checksum_exits_nonzero_when_table_fails(self):
        argv = ['ch-pg-checksum', '--pg_host', 'pg-host', '--pg_user', 'u',
                '--pg_password', 'p', '--pg_database', 'db1', '--tables_regex', '.']
        with patch.object(ptc, 'get_table_columns', side_effect=RuntimeError('boom')), \
                patch.object(ptc, 'get_table_pk', return_value=['id']):
            self.assertEqual(self._run_main(ptc, argv), 1)

    def test_pg_checksum_exits_zero_when_tables_succeed(self):
        argv = ['ch-pg-checksum', '--pg_host', 'pg-host', '--pg_user', 'u',
                '--pg_password', 'p', '--pg_database', 'db1', '--tables_regex', '.']
        with patch.object(ptc, 'get_table_columns', return_value=[col('id')]), \
                patch.object(ptc, 'get_table_pk', return_value=['id']), \
                patch.object(ptc, 'get_postgres_table_checksum', return_value=DIGEST):
            self.assertEqual(self._run_main(ptc, argv), 0)

    def test_pg_checksum_exits_nonzero_for_a_missing_table(self):
        # A table with no visible column (missing, hidden) has nothing to
        # digest; it must fail, not print the empty-table digest (found end to
        # end: tests_e2e/postgres/test_pg_verification.py).
        argv = ['ch-pg-checksum', '--pg_host', 'pg-host', '--pg_user', 'u',
                '--pg_password', 'p', '--pg_database', 'db1', '--tables_regex',
                't_missing', '--no_wc']
        with patch.object(ptc, 'get_table_columns', return_value=[]), \
                patch.object(ptc, 'get_table_pk', return_value=[]):
            self.assertEqual(self._run_main(ptc, argv), 1)

    def test_no_comparable_column_gives_no_digest(self):
        conn = MagicMock()
        for columns in ([], [col('doc', pg_type='jsonb')]):
            self.assertIsNone(ptc.get_postgres_table_checksum(
                conn=conn, table_name='t1', columns_meta=columns,
                pk_columns=[], schema='public'))
        conn.cursor.assert_not_called()

    def test_pg_count_exits_nonzero_when_table_fails(self):
        argv = ['ch-pg-count', '--pg_host', 'pg-host', '--pg_user', 'u',
                '--pg_password', 'p', '--pg_database', 'db1']
        with patch.object(ptn, 'execute_pg', side_effect=RuntimeError('boom')):
            self.assertEqual(self._run_main(ptn, argv), 1)

    def test_pg_count_exits_zero_when_tables_succeed(self):
        argv = ['ch-pg-count', '--pg_host', 'pg-host', '--pg_user', 'u',
                '--pg_password', 'p', '--pg_database', 'db1']
        with patch.object(ptn, 'execute_pg', return_value=[{'cnt': 3}]):
            self.assertEqual(self._run_main(ptn, argv), 0)


def base_config(checksum_cfg=None):
    return {
        'source': {'postgres': {'host': 'pg-host', 'database': 'db1',
                                'user': 'u', 'password': 'p'}},
        'clickhouse': {'host': 'ch-host', 'database': 'replica'},
        'connector': {'offset_db': 'sink', 'offset_table': ''},
        'checksum': dict(checksum_cfg or {}),
    }


def run_legacy(no_checksum=False, checksum_cfg=None, compare_kwargs=None,
               hidden=(), ch_only=()):
    """Run run_config() in per-table mode with compare_table() driven by
    run_compare()'s patched helpers; return (exit code, stdout)."""
    compare_kwargs = dict(compare_kwargs or {})
    compare_kwargs.setdefault('no_checksum', no_checksum)

    def fake_compare(table_name, *a, **kw):
        return run_compare(**compare_kwargs)

    out = io.StringIO()
    with patch.multiple(
            tlp,
            get_postgres_connection=MagicMock(),
            get_standby_lsn=MagicMock(return_value=('0/10', 16)),
            get_tables=MagicMock(return_value=['orders']),
            clickhouse_connection=MagicMock(),
            get_pg_hidden_tables=MagicMock(return_value=list(hidden)),
            get_ch_only_tables=MagicMock(return_value=list(ch_only)),
            compare_table=MagicMock(side_effect=fake_compare)):
        with redirect_stdout(out):
            try:
                tlp.run_config(base_config(checksum_cfg),
                               argparse.Namespace(table=None, no_checksum=no_checksum))
            except SystemExit as e:
                return e.code, out.getvalue()
    raise AssertionError('run_config did not exit')


if __name__ == '__main__':
    unittest.main()
