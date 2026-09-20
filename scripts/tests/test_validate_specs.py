"""Unit tests for scripts/validate_specs.py.

Run with either:
    python3 -m pytest scripts/tests
    python3 -m unittest discover -s scripts/tests

Each test builds a minimal, valid repository fixture in a temporary directory,
mutates one thing, and asserts the validator reports exactly that.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import validate_specs as vs  # noqa: E402


SPEC_TEMPLATE = textwrap.dedent(
    """\
    # Spec {num}: Fixture

    ## 1. Executive Summary & Purpose
    Fixture spec.

    ## 2. Codebase Mapping on 2.11.0
    - **Primary Source**: `{mapping}`

    ## 3. Operational Specification
    Nothing.

    ## 4. Invariants Preserved
    - **Invariant I1**

    ## 5. Verification Criteria
    - `{verification}`
    """
)

MAIN_JAVA = "sink-connector/src/main/java/com/altinity/fixture/Foo.java"
TEST_JAVA = "sink-connector/src/test/java/com/altinity/fixture/FooTest.java"
LW_TEST_JAVA = "sink-connector-lightweight/src/test/java/com/altinity/fixture/BarIT.java"


def write(root: Path, rel: str, content: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def build_fixture(root: Path) -> None:
    """A repository that passes every check."""
    invariants = "\n\n".join(f"### Invariant I{n}: Fixture invariant {n}\nText." for n in range(1, vs.INVARIANT_COUNT + 1))
    write(root, "specs/CONSTITUTION.md", "# Constitution\n\n## 3. Core System Invariants\n\n" + invariants + "\n")
    write(root, "specs/README.md", "# Specs\n")
    write(root, "specs/SMART_RALPH_PROTOCOL.md", "# Protocol\n")
    for domain in vs.EXPECTED_DOMAINS:
        write(
            root,
            f"specs/{domain}/01-fixture.md",
            SPEC_TEMPLATE.format(num=domain, mapping=MAIN_JAVA, verification="FooTest.testBar()"),
        )
    write(root, MAIN_JAVA, "package com.altinity.fixture;\npublic class Foo {}\n")
    write(root, TEST_JAVA, "package com.altinity.fixture;\npublic class FooTest {\n  @Test\n  public void testBar() {}\n}\n")
    write(root, LW_TEST_JAVA, "package com.altinity.fixture;\npublic class BarIT {\n  @Test\n  void endToEnd() {}\n}\n")
    write(root, "formal_specs/lean/lean-toolchain", "leanprover/lean4:v4.11.0\n")
    write(
        root,
        "formal_specs/lean/lakefile.lean",
        "import Lake\nopen Lake DSL\n\npackage «Replication»\n\nlean_lib «Replication» where\n  roots := #[`Replication.Basic]\n",
    )
    write(root, "formal_specs/lean/README.md", "# Lean\n")
    write(root, "formal_specs/lean/Replication/Basic.lean", "namespace Replication\n\ndef thing : Nat := 1\n\ntheorem thing_pos : 0 < thing := by decide\n\nend Replication\n")
    write(root, "AGENTS.md", "# AGENTS\nSee specs/ and the Smart Ralph protocol.\n")
    write(root, "CLAUDE.md", "# CLAUDE\n")
    write(root, ".github/copilot-instructions.md", "# Copilot\n")


def spec_path(root: Path, domain_index: int = 0) -> Path:
    return root / "specs" / vs.EXPECTED_DOMAINS[domain_index] / "01-fixture.md"


def run(root: Path, allowlist: vs.Allowlist | None = None, **kwargs) -> vs.Report:
    return vs.validate(root, allowlist or vs.Allowlist(), quiet=True, **kwargs)


class FixtureCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        build_fixture(self.root)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def assertOneErrorContaining(self, report: vs.Report, *fragments: str) -> None:
        self.assertEqual(len(report.errors), 1, report.errors)
        for fragment in fragments:
            self.assertIn(fragment, report.errors[0])


class GovernanceTests(FixtureCase):
    def test_valid_fixture_passes(self) -> None:
        report = run(self.root)
        self.assertEqual(report.errors, [])
        self.assertEqual(report.warnings, [])
        self.assertEqual(report.spec_count, len(vs.EXPECTED_DOMAINS))

    def test_missing_invariant_heading_fails(self) -> None:
        constitution = self.root / "specs/CONSTITUTION.md"
        text = constitution.read_text(encoding="utf-8").replace(
            f"### Invariant I{vs.INVARIANT_COUNT}:", f"### Former Invariant I{vs.INVARIANT_COUNT}:"
        )
        constitution.write_text(text, encoding="utf-8")
        self.assertOneErrorContaining(run(self.root), f"missing heading for Invariant I{vs.INVARIANT_COUNT}")

    def test_extra_invariant_beyond_count_is_flagged(self) -> None:
        constitution = self.root / "specs/CONSTITUTION.md"
        with constitution.open("a", encoding="utf-8") as fh:
            fh.write(f"\n### Invariant I{vs.INVARIANT_COUNT + 1}: Surprise\nText.\n")
        self.assertOneErrorContaining(run(self.root), f"Invariant I{vs.INVARIANT_COUNT + 1}", "INVARIANT_COUNT")

    def test_missing_required_section_fails(self) -> None:
        spec = spec_path(self.root)
        spec.write_text(spec.read_text(encoding="utf-8").replace("## 5. Verification Criteria", "## 5. Checks"), encoding="utf-8")
        self.assertOneErrorContaining(run(self.root), "missing required section matching 'Verification'")

    def test_agents_md_must_reference_mandate(self) -> None:
        write(self.root, "AGENTS.md", "# AGENTS\nnothing here\n")
        self.assertOneErrorContaining(run(self.root), "AGENTS.md: missing reference")


class LeanTests(FixtureCase):
    def test_unlisted_lean_file_with_sorry_is_caught(self) -> None:
        # A module that is NOT in the lakefile roots was previously invisible to
        # the validator; it must now be flagged twice: not built, and unproven.
        write(self.root, "formal_specs/lean/Replication/Extra.lean", "theorem t : True := by sorry\n")
        report = run(self.root)
        self.assertEqual(len(report.errors), 2, report.errors)
        self.assertTrue(any("Extra.lean is not listed in the lakefile roots" in e for e in report.errors))
        self.assertTrue(any("Extra.lean: contains 'sorry'" in e for e in report.errors))

    def test_lakefile_root_without_module_fails(self) -> None:
        lakefile = self.root / "formal_specs/lean/lakefile.lean"
        lakefile.write_text(lakefile.read_text(encoding="utf-8").replace("`Replication.Basic]", "`Replication.Basic, `Replication.Ghost]"), encoding="utf-8")
        self.assertOneErrorContaining(run(self.root), "Replication.Ghost", "does not exist")

    def test_admit_and_native_decide_are_rejected(self) -> None:
        basic = self.root / "formal_specs/lean/Replication/Basic.lean"
        basic.write_text(basic.read_text(encoding="utf-8").replace("by decide", "by native_decide") + "\ntheorem u : True := by admit\n", encoding="utf-8")
        report = run(self.root)
        self.assertEqual(len(report.errors), 2, report.errors)
        self.assertTrue(any("'native_decide'" in e for e in report.errors))
        self.assertTrue(any("'admit'" in e for e in report.errors))

    def test_lean_files_in_lake_build_dir_are_ignored(self) -> None:
        write(self.root, "formal_specs/lean/.lake/packages/dep/Dep.lean", "theorem d : True := by sorry\n")
        self.assertEqual(run(self.root).errors, [])

    def test_lake_build_failure_is_reported(self) -> None:
        # `lake` is stubbed with a script that fails, found first on PATH.
        bindir = self.root / "bin"
        bindir.mkdir()
        stub = bindir / "lake"
        stub.write_text("#!/bin/sh\necho 'error: proof failed' >&2\nexit 1\n", encoding="utf-8")
        stub.chmod(0o755)
        old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = f"{bindir}{os.pathsep}{old_path}"
        try:
            report = run(self.root, run_lake=True)
        finally:
            os.environ["PATH"] = old_path
        self.assertOneErrorContaining(report, "`lake build` failed", "proof failed")


class CodebaseMappingTests(FixtureCase):
    def _set_mapping(self, token: str, index: int = 0) -> None:
        spec = spec_path(self.root, index)
        spec.write_text(spec.read_text(encoding="utf-8").replace(MAIN_JAVA, token), encoding="utf-8")

    def test_missing_path_fails_with_precise_message(self) -> None:
        self._set_mapping("sink-connector/src/main/java/com/altinity/fixture/Missing.java")
        self.assertOneErrorContaining(run(self.root), "Codebase Mapping cites", "Missing.java", "does not exist")

    def test_path_with_line_and_member_locators_resolves(self) -> None:
        self._set_mapping(MAIN_JAVA + ":42-50")
        self._set_mapping(MAIN_JAVA + "#bar", index=1)
        self.assertEqual(run(self.root).errors, [])

    def test_abbreviated_path_resolves_by_suffix(self) -> None:
        self._set_mapping("sink-connector/.../fixture/Foo.java")
        self.assertEqual(run(self.root).errors, [])
        self._set_mapping("sink-connector/.../fixture/Nope.java", index=1)
        self.assertOneErrorContaining(run(self.root), "no file in the tree ends with 'fixture/Nope.java'")

    def test_directory_token_must_exist(self) -> None:
        self._set_mapping("sink-connector/python/db_compare/")
        self.assertOneErrorContaining(run(self.root), "directory 'sink-connector/python/db_compare/' does not exist")

    def test_fully_qualified_class_name_is_resolved(self) -> None:
        self._set_mapping("com.altinity.fixture.Foo")
        self.assertEqual(run(self.root).errors, [])
        self._set_mapping("com.altinity.fixture.injector.Foo", index=1)
        self.assertOneErrorContaining(run(self.root), "cites class `com.altinity.fixture.injector.Foo` which does not exist")

    def test_non_path_backticks_are_ignored(self) -> None:
        self._set_mapping("offset.storage.jdbc.offset.table.ddl")
        self._set_mapping("SELECT a / b FROM t", index=1)
        self.assertEqual(run(self.root).errors, [])

    def test_paths_outside_mapping_section_are_not_checked(self) -> None:
        spec = spec_path(self.root)
        spec.write_text(spec.read_text(encoding="utf-8").replace("Nothing.", "See `sink-connector/does/not/exist.java`."), encoding="utf-8")
        self.assertEqual(run(self.root).errors, [])


class VerificationRefTests(FixtureCase):
    def _set_verification(self, token: str, index: int = 0) -> None:
        spec = spec_path(self.root, index)
        spec.write_text(spec.read_text(encoding="utf-8").replace("FooTest.testBar()", token), encoding="utf-8")

    def test_missing_test_class_fails(self) -> None:
        self._set_verification("GhostTest.testAnything()")
        self.assertOneErrorContaining(run(self.root), "no GhostTest.java exists under")

    def test_missing_test_method_fails(self) -> None:
        self._set_verification("FooTest.testMissing()")
        self.assertOneErrorContaining(run(self.root), "FooTest declares no method testMissing()")

    def test_class_only_and_hash_method_forms_pass(self) -> None:
        self._set_verification("FooTest")
        self._set_verification("FooTest#testBar", index=1)
        self._set_verification("BarIT.endToEnd()", index=2)
        report = run(self.root)
        self.assertEqual(report.errors, [])
        statuses = {(spec.split("/")[1], tok): st for spec, tok, st in report.refs}
        self.assertEqual(statuses[(vs.EXPECTED_DOMAINS[2], "BarIT.endToEnd()")], "ok")

    def test_lean_reference_is_checked(self) -> None:
        self._set_verification("Replication.Basic.thing_pos")
        self.assertEqual(run(self.root).errors, [])
        self._set_verification("Replication.Basic.nope", index=1)
        self.assertOneErrorContaining(run(self.root), "Basic.lean declares no nope")
        self._set_verification("Replication.Missing.nope", index=2)
        report = run(self.root)
        self.assertEqual(len(report.errors), 2, report.errors)
        self.assertTrue(any("Replication/Missing.lean does not exist" in e for e in report.errors))

    def test_report_lists_every_citation_with_status(self) -> None:
        self._set_verification("GhostTest")
        report = run(self.root)
        statuses = sorted({st for _, _, st in report.refs})
        self.assertEqual(statuses, ["missing class", "ok"])
        self.assertEqual(len(report.refs), len(vs.EXPECTED_DOMAINS))


class AllowlistTests(FixtureCase):
    def test_file_level_allowlist_waives_and_warns(self) -> None:
        spec = spec_path(self.root)
        spec.write_text(spec.read_text(encoding="utf-8").replace("FooTest.testBar()", "GhostTest"), encoding="utf-8")
        allow = vs.Allowlist(files={spec.relative_to(self.root).as_posix()})
        report = run(self.root, allow)
        self.assertEqual(report.errors, [])
        self.assertEqual(len(report.warnings), 1)
        self.assertIn("(allowlisted)", report.warnings[0])

    def test_token_level_allowlist_only_waives_that_token(self) -> None:
        spec = spec_path(self.root)
        rel = spec.relative_to(self.root).as_posix()
        spec.write_text(spec.read_text(encoding="utf-8").replace("FooTest.testBar()", "GhostTest").replace(MAIN_JAVA, "sink-connector/x/Missing.java"), encoding="utf-8")
        allow = vs.Allowlist(tokens={(rel, "GhostTest")})
        report = run(self.root, allow)
        self.assertEqual(len(report.errors), 1, report.errors)
        self.assertIn("Missing.java", report.errors[0])
        self.assertEqual(len(report.warnings), 1)

    def test_stale_allowlist_entry_is_warned(self) -> None:
        allow = vs.Allowlist(files={"specs/99-nothing/01-x.md"})
        report = run(self.root, allow)
        self.assertEqual(report.errors, [])
        self.assertEqual(report.warnings, ["allowlist entry no longer needed: specs/99-nothing/01-x.md"])

    def test_allowlist_file_parsing(self) -> None:
        path = write(self.root, "scripts/spec_validator_allowlist.txt", "# comment\nspecs/a/b.md\nspecs/c/d.md | SomeTest.x()  # trailing\n\n")
        allow = vs.Allowlist.load(path)
        self.assertEqual(allow.files, {"specs/a/b.md"})
        self.assertEqual(allow.tokens, {("specs/c/d.md", "SomeTest.x()")})


class ChangedBaseTests(FixtureCase):
    """The spec-first gate, exercised against a real temporary git repository."""

    GIT_ID = ["-c", "user.name=Spec Validator Test", "-c", "user.email=spec-validator@example.invalid", "-c", "commit.gpgsign=false"]

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *self.GIT_ID, "-C", str(self.root), *args], check=True, capture_output=True, text=True).stdout

    def setUp(self) -> None:
        super().setUp()
        self.git("init", "-q", "-b", "main")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "baseline")
        self.git("branch", "base")

    def commit(self, message: str, *files: tuple[str, str]) -> None:
        for rel, content in files:
            write(self.root, rel, content)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def test_main_source_change_without_spec_change_fails(self) -> None:
        self.commit("fix: tweak", (MAIN_JAVA, "package com.altinity.fixture;\npublic class Foo { int x; }\n"))
        report = run(self.root, changed_base="base")
        self.assertOneErrorContaining(report, "spec-governed source changed but no file under specs/", MAIN_JAVA)

    def test_grammar_change_without_spec_change_fails(self) -> None:
        self.commit("feat: grammar", ("sink-connector-lightweight/src/main/antlr4/MySqlParser.g4", "grammar MySqlParser;\n"))
        report = run(self.root, changed_base="base")
        self.assertOneErrorContaining(report, "MySqlParser.g4")

    def test_main_source_change_with_spec_change_passes(self) -> None:
        spec = spec_path(self.root)
        self.commit(
            "feat: with spec",
            (MAIN_JAVA, "package com.altinity.fixture;\npublic class Foo { int x; }\n"),
            (spec.relative_to(self.root).as_posix(), spec.read_text(encoding="utf-8") + "\nUpdated.\n"),
        )
        self.assertEqual(run(self.root, changed_base="base").errors, [])

    def test_formal_spec_change_counts_as_spec_change(self) -> None:
        self.commit(
            "feat: with lean",
            (MAIN_JAVA, "package com.altinity.fixture;\npublic class Foo { int x; }\n"),
            ("formal_specs/lean/Replication/Basic.lean", "namespace Replication\n\ndef thing : Nat := 2\n\ntheorem thing_pos : 0 < thing := by decide\n\nend Replication\n"),
        )
        self.assertEqual(run(self.root, changed_base="base").errors, [])

    def test_spec_exempt_marker_in_head_message_passes(self) -> None:
        self.commit("chore: typo\n\n[spec-exempt: comment-only change]", (MAIN_JAVA, "package com.altinity.fixture;\n// note\npublic class Foo {}\n"))
        self.assertEqual(run(self.root, changed_base="base").errors, [])

    def test_test_only_change_passes(self) -> None:
        self.commit("test: more", (TEST_JAVA, "package com.altinity.fixture;\npublic class FooTest {\n  @Test\n  public void testBar() {}\n  @Test\n  public void testBaz() {}\n}\n"))
        self.assertEqual(run(self.root, changed_base="base").errors, [])

    def test_unknown_base_ref_is_an_error(self) -> None:
        report = run(self.root, changed_base="no-such-ref")
        self.assertOneErrorContaining(report, "--changed-base")


class CliTests(FixtureCase):
    def test_main_exit_codes(self) -> None:
        self.assertEqual(vs.main(["--repo-root", str(self.root)]), 0)
        write(self.root, "AGENTS.md", "# AGENTS\nnothing\n")
        self.assertEqual(vs.main(["--repo-root", str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
