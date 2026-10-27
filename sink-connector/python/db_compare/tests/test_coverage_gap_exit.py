"""Exit rules of the db_compare run summary (coverage gap, opt-in difference failure).

Offline: report_run_summary() is the real function; no database is opened. Run from sink-connector/python:

    python3 -m pytest db_compare/tests/test_coverage_gap_exit.py
"""
import os
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))

import db_compare.top_level_table_checksum as tl  # noqa: E402


def summary(verdicts, gaps=(), **flags):
    """Return (exit code, log lines) of report_run_summary() for the given verdicts and coverage gaps."""
    with unittest.TestCase().assertLogs(level="INFO") as logs:
        code = tl.report_run_summary(verdicts, flags.pop("fail_on_empty", False), list(gaps), **flags)
    return code, logs.output


class TestCoverageGap(unittest.TestCase):
    """A table skipped for a lock timeout has no verdict; the run must not look green."""

    def test_coverage_gap_fails_the_run_by_default_and_names_the_table(self):
        code, logs = summary({"shop.orders": tl.VERDICT_MATCH}, gaps=["shop.hot"])
        self.assertEqual(code, 1)
        self.assertTrue(any("COVERAGE GAP: 1 table(s) were NOT compared" in line and "shop.hot" in line
                            and "--allow_coverage_gap" in line for line in logs), logs)

    def test_coverage_gap_is_accepted_with_allow_coverage_gap_and_still_named(self):
        code, logs = summary({"shop.orders": tl.VERDICT_MATCH}, gaps=["shop.hot", "crm.quote"],
                             allow_coverage_gap=True)
        self.assertEqual(code, 0)
        self.assertTrue(any("COVERAGE GAP accepted (--allow_coverage_gap): 2 table(s) were NOT compared" in line
                            and "shop.hot, crm.quote" in line for line in logs), logs)

    def test_accepted_gap_is_logged_at_info_not_warning(self):
        # The driver log keeps WARNING for differences only (spec 11.02 section 3.2 step 4).
        code, logs = summary({"shop.orders": tl.VERDICT_MATCH}, gaps=["shop.hot"], allow_coverage_gap=True)
        self.assertEqual(code, 0)
        accepted = [line for line in logs if "COVERAGE GAP accepted" in line]
        self.assertEqual(len(accepted), 1, logs)
        self.assertTrue(accepted[0].startswith("INFO:"), accepted)
        self.assertFalse(any(line.startswith("WARNING:") for line in logs), logs)

    def test_no_gap_and_all_match_exits_zero(self):
        code, _ = summary({"shop.orders": tl.VERDICT_MATCH})
        self.assertEqual(code, 0)

    def test_error_fails_the_run_even_when_the_gap_is_accepted(self):
        code, logs = summary({"shop.orders": tl.VERDICT_ERROR}, gaps=["shop.hot"], allow_coverage_gap=True)
        self.assertEqual(code, 1)
        self.assertTrue(any("1 table(s) have NO verdict" in line and "shop.orders" in line for line in logs), logs)


class TestDifferentIsOptIn(unittest.TestCase):
    """DIFFERENT keeps exit 0 unless --fail_on_different is given (spec 11.02 FM-11.02-1)."""

    def test_different_exits_zero_by_default_and_is_named(self):
        code, logs = summary({"shop.orders": tl.VERDICT_DIFFERENT})
        self.assertEqual(code, 0)
        self.assertTrue(any("1 table(s) DIFFERENT (the run exits 0; pass --fail_on_different" in line
                            and "shop.orders" in line for line in logs), logs)

    def test_different_exits_one_with_fail_on_different(self):
        code, logs = summary({"shop.orders": tl.VERDICT_DIFFERENT, "shop.a": tl.VERDICT_MATCH},
                             fail_on_different=True)
        self.assertEqual(code, 1)
        self.assertTrue(any("DIFFERENT (failing the run: --fail_on_different)" in line
                            and "shop.orders" in line for line in logs), logs)

    def test_different_and_a_gap_both_fail_and_both_are_named(self):
        code, logs = summary({"shop.orders": tl.VERDICT_DIFFERENT}, gaps=["shop.hot"], fail_on_different=True)
        self.assertEqual(code, 1)
        self.assertTrue(any("shop.hot" in line and "NOT compared" in line for line in logs), logs)


class TestEmptyIsUnchanged(unittest.TestCase):
    """EMPTY keeps its documented default (exit 0; --fail_on_empty opts in). Not a coverage gap."""

    def test_empty_only_exits_zero_by_default(self):
        code, _ = summary({"shop.orders": tl.VERDICT_EMPTY})
        self.assertEqual(code, 0)

    def test_empty_exits_one_with_fail_on_empty(self):
        code, logs = summary({"shop.orders": tl.VERDICT_EMPTY}, fail_on_empty=True)
        self.assertEqual(code, 1)
        self.assertTrue(any("EMPTY on both sides: 1 table(s) compared 0 rows" in line for line in logs), logs)


class TestCliContract(unittest.TestCase):
    def _help_text(self):
        python_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
        env = dict(os.environ)
        env['PYTHONPATH'] = python_root + os.pathsep + env.get('PYTHONPATH', '')
        out = subprocess.run([sys.executable, os.path.join('db_compare', 'top_level_table_checksum.py'), '--help'],
                             cwd=python_root, capture_output=True, text=True, env=env)
        return (out.stdout or '') + (out.stderr or '')

    def test_new_flags_are_documented_in_help(self):
        text = self._help_text()
        self.assertIn('--allow_coverage_gap', text)
        self.assertIn('--fail_on_different', text)


if __name__ == '__main__':
    unittest.main()
