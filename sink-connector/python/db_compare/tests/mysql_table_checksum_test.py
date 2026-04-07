import os
import sys
import unittest

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

from db_compare.mysql_table_checksum import fstr


class FstrTestCase(unittest.TestCase):
    """Tests for fstr(), which replaced an eval() call.

    fstr() used to be implemented with eval() on an f-string template, so any
    attacker-influenced text reaching the template executed as Python. It is now
    a literal placeholder substitution. These tests pin that behaviour so the
    eval() cannot quietly come back.

    This file previously contained a single placeholder assertEqual(True, False)
    that failed on every run.
    """

    def test_substitutes_placeholder(self):
        self.assertEqual(
            fstr("where {partition_expression} = 1", "toDate(ts)"),
            "where toDate(ts) = 1",
        )

    def test_substitutes_every_occurrence(self):
        self.assertEqual(
            fstr("{partition_expression} and {partition_expression}", "p"),
            "p and p",
        )

    def test_none_expression_returns_template_unchanged(self):
        template = "where {partition_expression} = 1"
        self.assertEqual(fstr(template, None), template)

    def test_template_without_placeholder_is_unchanged(self):
        self.assertEqual(fstr("where 1=1", "toDate(ts)"), "where 1=1")

    def test_non_string_expression_is_coerced(self):
        self.assertEqual(fstr("v = {partition_expression}", 42), "v = 42")

    def test_does_not_evaluate_python_in_template(self):
        # The old eval()-based implementation would have executed this.
        # Substitution must treat it as inert text.
        template = "{__import__('os').system('touch /tmp/pwned')}"
        self.assertEqual(fstr(template, "x"), template)

    def test_does_not_evaluate_python_in_expression(self):
        # A hostile partition expression must be inserted literally, never run.
        payload = "__import__('os').system('touch /tmp/pwned')"
        self.assertEqual(
            fstr("where {partition_expression}", payload),
            "where " + payload,
        )

    def test_braces_other_than_placeholder_survive(self):
        # A real f-string/eval would choke on or interpret these; plain
        # substitution must leave them intact.
        self.assertEqual(fstr("json = '{\"a\": 1}'", "x"), "json = '{\"a\": 1}'")


if __name__ == "__main__":
    unittest.main()
