"""Failure modes of scripts/validate_specs.py (spec 11.01 section 6, spec 11.03 section 7).

Each test builds the passing fixture of test_validate_specs.py, then introduces
one defect the validator is expected to catch. Tests marked skip record a
DEFECT: the validator accepts the broken state today.

Run with either:
    python3 -m pytest scripts/tests
    python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import validate_specs as vs  # noqa: E402
from test_validate_specs import TEST_JAVA, FixtureCase, run, spec_path, write  # noqa: E402


class FailureModesSectionTests(FixtureCase):
    def _append_entry(self, entry: str) -> None:
        spec = spec_path(self.root)
        spec.write_text(spec.read_text(encoding="utf-8") + entry, encoding="utf-8")

    def test_a_section_that_states_all_three_fields_passes(self) -> None:
        self._append_entry("- **FM-2 Second fault** -- Detection: a metric. Recovery: restart. RTO: 20 s.\n")
        self.assertEqual(run(self.root).errors, [])

    @unittest.skip("DEFECT FM-11.01-2: Detection/Recovery/RTO are matched once per SECTION, so a second "
                   "failure mode without an RTO passes as long as another entry states one")
    def test_every_failure_mode_entry_must_state_its_rto(self) -> None:
        self._append_entry("- **FM-2 Second fault** -- Detection: a metric. Recovery: restart.\n")
        self.assertTrue(any("RTO" in e for e in run(self.root).errors), "an entry without an RTO must be reported")


class CitedMethodTests(FixtureCase):
    @unittest.skip("DEFECT FM-11.01-3: method presence is a regex on the test file, so a test that was deleted "
                   "but is still named in a comment ('the old helper testGone()') keeps its citation valid")
    def test_method_named_only_in_a_comment_is_not_a_declaration(self) -> None:
        write(self.root, TEST_JAVA,
              "package com.altinity.fixture;\npublic class FooTest {\n  @Test\n  public void testBar() {}\n"
              "  // replaces the old helper testGone() which was removed\n}\n")
        spec = spec_path(self.root)
        spec.write_text(spec.read_text(encoding="utf-8").replace("Recovery: restart.",
                                                                 "Recovery: restart, `FooTest.testGone()`."),
                        encoding="utf-8")
        self.assertTrue(any("testGone" in e for e in run(self.root).errors), "a method named only in a comment does not exist")


class LeanGateTests(FixtureCase):
    @unittest.skip("DEFECT FM-11.03-2: only sorry/admit/native_decide are rejected; a user-declared `axiom` "
                   "proves anything, passes `lake build` and passes the validator")
    def test_user_declared_axiom_is_rejected(self) -> None:
        write(self.root, "formal_specs/lean/Replication/Basic.lean",
              "namespace Replication\n\naxiom cheat : False\n\ntheorem anything : 1 = 2 := cheat.elim\n\nend Replication\n")
        self.assertTrue(any("axiom" in e for e in run(self.root).errors))

    @unittest.skip("DEFECT FM-11.03-1: a lakefile without @[default_target] makes a bare `lake build` compile "
                   "nothing and succeed; the validator does not check the attribute")
    def test_lakefile_without_default_target_is_rejected(self) -> None:
        # The fixture lakefile has no attribute either; the real one carries it.
        self.assertNotIn("default_target", (self.root / "formal_specs/lean/lakefile.lean").read_text(encoding="utf-8"))
        self.assertTrue(any("default_target" in e for e in run(self.root).errors))


class RobustnessTests(FixtureCase):
    def test_non_utf8_spec_aborts_the_run_loudly(self) -> None:
        # Not a finding but a crash: the run stops with a traceback (non-zero exit), never a silent pass.
        spec_path(self.root).write_bytes(b"# Spec\n\xff\xfe not utf-8\n")
        with self.assertRaises(UnicodeDecodeError):
            run(self.root)


if __name__ == "__main__":
    unittest.main()
