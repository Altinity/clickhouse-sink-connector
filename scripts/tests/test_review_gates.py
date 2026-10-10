"""Unit tests for scripts/review_gates.py (Spec 11.06).

Each test builds a throwaway git repository with a base commit and a head
commit, runs the gates over base...head and asserts on the findings. Run with
``python3 -m unittest discover -s scripts/tests -v``.
"""

import os
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import review_gates as rg  # noqa: E402

TRAILER_KEY = "Destructive-Op-Check"


class Repo:
    def __init__(self, root):
        self.root = root
        self._git("init", "-q", "-b", "main")
        self._git("config", "user.email", "test@example.invalid")
        self._git("config", "user.name", "test")
        self._git("config", "commit.gpgsign", "false")

    def _git(self, *args):
        subprocess.run(["git", "-C", self.root, *args], check=True,
                       capture_output=True, text=True)

    def write(self, path, text):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full) or self.root, exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(textwrap.dedent(text))

    def snapshot(self, message):
        self._git("add", "-A")
        self._git("commit", "-q", "--allow-empty", "-m", message)
        return subprocess.run(["git", "-C", self.root, "rev-parse", "HEAD"], check=True,
                              capture_output=True, text=True).stdout.strip()


class GateTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)
        self.repo.write("README.md", "base\n")
        self.repo.write("THIRD_PARTY_NOTICES.md", "| `com.example:known-lib` | Apache-2.0 |\n")
        self.base = self.repo.snapshot("base")

    def tearDown(self):
        self._tmp.cleanup()

    def run_gate(self, gate, message="change"):
        head = self.repo.snapshot(message)
        findings, _ = rg.run_gates(self.repo.root, self.base, head, [gate])
        return findings


class DestructiveGateTest(GateTestCase):
    def test_unwarned_site_without_trailer_is_reported_twice(self):
        self.repo.write("tool/purge.py", 'cur.execute("DROP TABLE t")\n')
        findings = self.run_gate("destructive")
        messages = [f.message for f in findings]
        self.assertEqual(len(findings), 2, messages)
        self.assertTrue(any("without a 'DESTRUCTIVE:' comment" in m for m in messages))
        self.assertTrue(any("its message has no" in m for m in messages))

    def test_warned_site_with_matching_trailer_passes(self):
        self.repo.write("tool/purge.py", """\
            # DESTRUCTIVE: drops only the scratch table this run created, never a live one
            cur.execute("DROP TABLE scratch_t")
            """)
        findings = self.run_gate("destructive", f"purge\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.assertEqual(findings, [])

    def test_trailer_count_must_match(self):
        self.repo.write("tool/purge.sh", """\
            # DESTRUCTIVE: removes the per-run scratch directory under the build dir only
            rm -rf "$BUILD/scratch"
            # DESTRUCTIVE: truncates the staging table loaded earlier in this same script
            clickhouse-client -q "TRUNCATE TABLE staging"
            """)
        findings = self.run_gate("destructive", f"purge\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.assertEqual(len(findings), 1)
        self.assertIn("sites=1", findings[0].message)
        self.assertIn("adds 2", findings[0].message)

    def test_short_warning_is_not_enough(self):
        self.repo.write("tool/purge.py", '# DESTRUCTIVE: yes\ncur.execute("DELETE FROM t")\n')
        findings = self.run_gate("destructive", f"x\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.assertEqual(len(findings), 1)

    def test_marker_inside_string_literal_is_not_a_warning(self):
        self.repo.write("tool/purge.py",
                        'x = "-- DESTRUCTIVE: this is only a string, not a comment at all"\n'
                        'cur.execute("DROP DATABASE d")\n')
        findings = self.run_gate("destructive", f"x\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.assertEqual(len(findings), 1)

    def test_concatenated_and_split_flags_are_sites(self):
        self.assertTrue(rg.is_destructive_line('sql = "DROP " + "TABLE x"'))
        self.assertTrue(rg.is_destructive_line("rm -r /tmp/x -f"))
        self.assertTrue(rg.is_destructive_line("ALTER TABLE t DELETE WHERE id = 1"))
        self.assertTrue(rg.is_destructive_line("DROP /* hidden */ TABLE x"))
        self.assertFalse(rg.is_destructive_line("-- DROP TABLE x is never run here"))
        self.assertFalse(rg.is_destructive_line("rm -f single_file"))
        self.assertFalse(rg.is_destructive_line("String DROP_TABLE = parser.token();"))

    def test_tests_docs_and_specs_are_exempt(self):
        self.repo.write("module/src/test/java/PurgeTest.java", 'run("DROP TABLE t");\n')
        self.repo.write("doc/purge.md", "Run `DROP TABLE t`.\n")
        self.repo.write("specs/01/x.md", "TRUNCATE TABLE t\n")
        self.repo.write("m/src/main/antlr4/Parser.g4", "dropTable : DROP TABLE ifExists? tables ;\n")
        self.repo.write("formal_specs/lean/Model.lean", "-- models TRUNCATE TABLE as clearing the state\ndef truncate (s : S) : S := s\n")
        self.assertEqual(self.run_gate("destructive"), [])

    def test_failed_result_is_rejected(self):
        self.repo.write("tool/p.py", "# DESTRUCTIVE: drops the temporary copy made above\ncur.execute('DROP TABLE tmp')\n")
        findings = self.run_gate("destructive", f"x\n\n{TRAILER_KEY}: sites=1; result=fail")
        self.assertEqual(len(findings), 1)
        self.assertIn("result=fail", findings[0].message)

    WARN = "# DESTRUCTIVE: drops only the scratch table this step created above\n"

    def test_each_commit_attests_its_own_sites(self):
        self.repo.write("tool/a.py", self.WARN + 'cur.execute("DROP TABLE a_tmp")\n')
        self.repo.snapshot(f"first\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.repo.write("tool/b.py", self.WARN + 'cur.execute("DROP TABLE b_tmp")\n')
        # The second commit carries its own trailer; the first keeps its own.
        self.assertEqual(self.run_gate("destructive", f"second\n\n{TRAILER_KEY}: sites=1; result=pass"), [])

    def test_commit_without_its_own_trailer_is_reported(self):
        self.repo.write("tool/a.py", self.WARN + 'cur.execute("DROP TABLE a_tmp")\n')
        self.repo.snapshot(f"first\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.repo.write("tool/b.py", self.WARN + 'cur.execute("DROP TABLE b_tmp")\n')
        findings = self.run_gate("destructive", "second, no trailer")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].path, "tool/b.py")
        self.assertIn("has no", findings[0].message)

    def test_squash_message_with_several_trailers_is_summed(self):
        self.repo.write("tool/a.py", self.WARN + 'cur.execute("DROP TABLE a_tmp")\n')
        self.repo.write("tool/b.py", self.WARN + 'cur.execute("DROP TABLE b_tmp")\n')
        message = (f"squash (#1)\n\n* part one\n\n{TRAILER_KEY}: sites=1; result=pass\n\n"
                   f"* part two\n\n{TRAILER_KEY}: sites=1; result=pass")
        self.assertEqual(self.run_gate("destructive", message), [])

    def test_changes_older_than_the_gate_are_not_checked(self):
        # An unwarned, unattested site that predates the gate (a long-lived
        # release branch compared with an old base), then the commit that
        # introduces the gate, then a clean change.
        self.repo.write("tool/old.py", 'cur.execute("DROP TABLE legacy")\n')
        self.repo.snapshot("old change, before the checks existed")
        self.repo.write("scripts/review_gates.py", "# the checks\n")
        self.repo.snapshot("introduce the checks")
        self.repo.write("tool/new.py", "x = 1\n")
        self.assertEqual(self.run_gate("destructive", "clean change after the checks"), [])

    def test_changes_from_the_gate_commit_onward_are_checked(self):
        self.repo.write("tool/old.py", 'cur.execute("DROP TABLE legacy")\n')
        self.repo.snapshot("old change, before the checks existed")
        self.repo.write("scripts/review_gates.py", "# the checks\n")
        self.repo.snapshot("introduce the checks")
        self.repo.write("tool/new.py", 'cur.execute("DROP TABLE fresh")\n')
        findings = self.run_gate("destructive", "unwarned site after the checks")
        self.assertEqual(sorted({f.path for f in findings}), ["tool/new.py"])
        self.assertEqual(len(findings), 2)


class MergeStopGateTest(GateTestCase):
    def test_literal_stop_is_reported(self):
        self.repo.write("tool/migrate.py", 'run("SYSTEM STOP MERGES db.t")\n')
        self.assertEqual(len(self.run_gate("merge-stop")), 1)

    def test_split_and_templated_forms_are_reported(self):
        self.repo.write("tool/a.py", 'q = "SYSTEM STOP " \\\n    "MERGES db.t"\n')
        self.repo.write("tool/b.py", 'q = f"SYSTEM {verb} MERGES {table}"\n')
        self.repo.write("tool/c.sh", 'clickhouse-client -q "SYSTEM ${ACTION:-STOP} MERGES t"\n')
        self.repo.write("tool/d.py", "def stop_merges(table):\n    return table\n")
        self.repo.write("tool/E.java", "void pauseMerges(String t) {}\n")
        paths = sorted({f.path for f in self.run_gate("merge-stop")})
        self.assertEqual(paths, ["tool/E.java", "tool/a.py", "tool/b.py", "tool/c.sh", "tool/d.py"])

    def test_start_merges_is_allowed(self):
        self.repo.write("tool/recover.py", 'run("SYSTEM START MERGES db.t")\nmerges_stopped = False\n')
        self.assertEqual(self.run_gate("merge-stop"), [])

    def test_prose_and_tests_are_exempt(self):
        self.repo.write("doc/x.md", "Never run SYSTEM STOP MERGES in a migration.\n")
        self.repo.write("tool/tests/test_x.py", 'assert "SYSTEM STOP MERGES" in banned\n')
        self.assertEqual(self.run_gate("merge-stop"), [])


POM = """\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <dependencyManagement>
    <dependencies>
      <dependency><groupId>org.mariadb.jdbc</groupId><artifactId>mariadb-java-client</artifactId></dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency><groupId>com.example</groupId><artifactId>known-lib</artifactId></dependency>
{extra}
  </dependencies>
</project>
"""


class LicenseGateTest(GateTestCase):
    def setUp(self):
        super().setUp()
        self.repo.write("m/pom.xml", POM.format(extra=""))
        self.base = self.repo.snapshot("pom")

    def test_category_x_runtime_dependency_is_reported(self):
        self.repo.write("m/pom.xml", POM.format(extra=(
            "    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></dependency>")))
        findings = self.run_gate("license")
        self.assertEqual(len(findings), 1)
        self.assertIn("GPL", findings[0].message)

    def test_test_and_provided_scopes_are_allowed(self):
        self.repo.write("m/pom.xml", POM.format(extra=(
            "    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId>"
            "<scope>provided</scope></dependency>\n"
            "    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>")))
        self.assertEqual(self.run_gate("license"), [])

    def test_new_permissive_dependency_must_be_in_notices(self):
        self.repo.write("m/pom.xml", POM.format(extra=(
            "    <dependency><groupId>com.example</groupId><artifactId>new-lib</artifactId></dependency>")))
        findings = self.run_gate("license")
        self.assertEqual(len(findings), 1)
        self.assertIn("THIRD_PARTY_NOTICES.md", findings[0].message)
        self.repo.write("THIRD_PARTY_NOTICES.md", "known-lib\nnew-lib\n")
        self.assertEqual(self.run_gate("license"), [])

    def test_python_category_x_requirement_is_reported(self):
        self.repo.write("tools/requirements.txt", "pg8000>=1.31\npsycopg2-binary==2.9\n")
        findings = self.run_gate("license")
        self.assertEqual([f.line for f in findings], [2])

    def test_unparseable_pom_is_reported(self):
        self.repo.write("m/pom.xml", "<project><dependencies></project>")
        findings = self.run_gate("license")
        self.assertEqual(len(findings), 1)
        self.assertIn("does not parse", findings[0].message)


class HygieneGateTest(GateTestCase):
    def test_conflict_marker(self):
        self.repo.write("a.py", "<<<<<<< HEAD\nx = 1\n=======\nx = 2\n>>>>>>> branch\n")
        self.assertEqual([f.line for f in self.run_gate("hygiene")], [1, 5])

    def test_swallowed_exceptions(self):
        self.repo.write("a.py", "try:\n    f()\nexcept ValueError:\n    pass\ntry:\n    g()\nexcept:\n    raise\n")
        self.repo.write("B.java", "try { f(); } catch (Exception e) { }\ntry {\n g();\n} catch (IOException e) {\n}\n")
        findings = self.run_gate("hygiene")
        self.assertEqual([(f.path, f.line) for f in findings], [("B.java", 1), ("B.java", 4), ("a.py", 3), ("a.py", 7)])

    def test_narrow_cleanup_errors_may_be_ignored(self):
        self.repo.write("a.py", "try:\n    pipe.close()\nexcept OSError:\n    pass\n"
                                "try:\n    os.unlink(p)\nexcept (FileNotFoundError, OSError):\n    pass\n"
                                "try:\n    run()\nexcept Exception:\n    pass\n")
        self.assertEqual([f.line for f in self.run_gate("hygiene")], [11])

    def test_handled_exception_is_fine(self):
        self.repo.write("a.py", "try:\n    f()\nexcept ValueError as exc:\n    log.warning('bad value %s', exc)\n    raise\n")
        self.assertEqual(self.run_gate("hygiene"), [])

    def test_secret_and_debugger(self):
        self.repo.write("a.py", "import pdb\nKEY = 'AKIAABCDEFGHIJKLMNOP'\n")
        findings = self.run_gate("hygiene")
        self.assertEqual([f.line for f in findings], [1, 2])

    def test_broken_json(self):
        self.repo.write("conf/x.json", '{"a": 1,}\n')
        self.assertEqual(len(self.run_gate("hygiene")), 1)

    def test_broken_yaml_when_pyyaml_available(self):
        try:
            import yaml  # noqa: F401
        except ImportError:
            self.skipTest("PyYAML not installed")
        self.repo.write("conf/x.yaml", "a: [1, 2\n")
        self.assertEqual(len(self.run_gate("hygiene")), 1)


class CliTest(GateTestCase):
    def test_exit_codes(self):
        self.repo.write("a.py", "x = 1\n")
        self.repo.snapshot("clean")
        self.assertEqual(rg.main(["--repo", self.repo.root, "--base", self.base]), 0)
        self.repo.write("b.py", "<<<<<<< HEAD\n")
        self.repo.snapshot("dirty")
        self.assertEqual(rg.main(["--repo", self.repo.root, "--base", self.base]), 1)
        self.assertEqual(rg.main(["--repo", self.repo.root, "--base", "no-such-ref"]), 2)


if __name__ == "__main__":
    unittest.main()
