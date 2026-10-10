# Spec 11.06: Deterministic Review Gates (`scripts/review_gates.py`)

## 1. Executive Summary & Purpose
Some defects in a pull request can be proven by a machine from the diff alone,
and every one of them has a cost that lands on production rather than on the
review:

* a destructive statement nobody flagged
* a migration step that stops ClickHouse merges
* a GPL dependency shaded into an Apache-2.0 jar
* an exception swallowed so a failure is silent
* a conflict marker or a configuration file that no longer parses

This spec declares a deterministic, offline gate over the lines a change
adds, run on every pull request. It complements the human and agent review
methodology in `.claude/skills/` (judgement) and the spec validator
(Spec 11.01, structure). It does not replace either.

## 2. Codebase Mapping on 2.11.0
- **Gate CLI**: `scripts/review_gates.py`:
  * `run_gates` (driver)
  * `check_destructive`, `check_merge_stop`, `check_license`, `check_hygiene`
    (one per gate)
  * `parse_added_lines` (diff parsing)
  * `exempt_from_code_gates` (path classification)
- **Tests**: `scripts/tests/test_review_gates.py`. These are offline: each
  test builds a throwaway git repository.
- **CI**: `.github/workflows/review-gates.yml` runs the self-tests and then
  the gates over the PR base...head range on every pull request. The
  self-tests also run in `.github/workflows/spec-governance.yml` through
  `unittest discover -s scripts/tests`.
- **Documentation**: `doc/review_gates.md` (usage, rules, calibration),
  `doc/licensing.md` (the license policy the license gate mirrors).
- **Review methodology**: `.claude/skills/README.md` (index of the review
  skills that reference these gates).

## 3. Contract
### 3.1 Scope of the diff
The gates inspect `git diff -U0 base...head`, the lines the change adds
relative to the merge base. Lines that landed on the base branch after the
branch point are never attributed to the change. Commit messages are read
from `merge-base..head`, newest first.

### 3.2 destructive
An added line is a site when, outside a comment-only line, it matches:

* `DROP TABLE|DATABASE|PARTITION|VIEW|DICTIONARY|INDEX|COLUMN|PART|DETACHED`
* `TRUNCATE`
* `DELETE FROM`
* `ALTER TABLE ... DELETE`
* `DELETE WHERE`
* `DETACH PARTITION`
* `SYSTEM DROP REPLICA`
* a recursive and forced `rm` in any flag order

The match also applies after stripping inline block comments, quotes,
backslashes and `+` concatenation.

Each site needs a `DESTRUCTIVE:` marker in a comment (not a string literal),
followed by at least 20 characters, within 5 lines of the site in the head
version of the file.

The newest commit in the range that carries `Destructive-Op-Check:` must
state `sites=<N>; result=pass`, where N equals the number of sites in the
change. A missing trailer, a count mismatch or a non-pass result is a
finding.

### 3.3 merge-stop
An added line, or a run of up to four consecutive added lines, is a finding
when it contains any of these:

* `SYSTEM STOP MERGES` or `SYSTEM STOP TTL MERGES`, literal or spliced across
  quotes and comments
* a templated verb in `SYSTEM <verb> MERGES`
* a `stop|pause|disable|halt|suspend|freeze` merges helper identifier (snake,
  kebab when invoked, or camel case)

`SYSTEM START MERGES` alone is never a finding. There is no attestation
override.

### 3.4 license
For each changed `pom.xml`, the direct dependencies at `compile`, `runtime`
or no scope (outside `<dependencyManagement>`, not optional) are compared
between the merge base and head. A new one is a finding when either holds:

* it is on the Category X / test-framework list (`BANNED_MAVEN`, kept in
  step with `doc/licensing.md`);
* its artifactId does not appear in `THIRD_PARTY_NOTICES.md`.

A POM that does not parse is a finding. An added requirement line in a Python
packaging file that names a `BANNED_PYTHON` package is a finding.

### 3.5 hygiene
Each of these, on an added line, is a finding:

* a merge-conflict marker
* a private key or well-known token format

In non-test files, these are also findings:

* an empty Java/Kotlin/Groovy/Scala `catch` block
* a bare Python `except:`
* `except ...: pass`, unless the exception is one of `OSError`,
  `FileNotFoundError`, `ProcessLookupError`, `ChildProcessError` (best-effort
  cleanup)
* a Python debugger leftover

A changed YAML file that does not parse is a finding when PyYAML is installed
(templated YAML containing `{{`/`{%` is skipped; a missing PyYAML is reported
as a notice). A changed JSON file that does not parse is a finding.

### 3.6 Exemptions
The destructive and merge-stop gates skip:

* tests: a path segment `test`, `tests`, `__tests__`, `testflows` or
  `testdata`; or a `test_*`, `*_test.*`, `*Test(s).java`, `*IT.java` or
  `conftest.py` basename
* prose: `*.md`, `*.rst`, `*.txt`, `*.adoc`, and the `doc/`, `specs/`,
  `release-notes/` and `.claude/` directories
* grammars and proofs: `*.g4`, `*.lean`, `formal_specs/`
* the gate's own source and tests

### 3.7 Output and exit status
Findings are printed sorted as `path:line: [gate] message`. The exit status
is 0 with no findings, 1 with findings, and 2 on a usage or git error. A git
error is never reported as a clean result.

## 4. Invariants Preserved
- **Fail loudly** (AGENTS.md "Silence is the enemy"): the hygiene gate rejects
  swallowed exceptions, and the tool itself exits 2, never 0, when it cannot
  read the change.
- **Destructive operations are visible and attested**: no site reaches the
  base branch without a reviewer-readable warning and a count-bound
  attestation.
- **Value-level verification, not part-layout freezing**: the merge-stop gate
  enforces that migrations rely on checksums (Spec 11.02), never on stopping
  merges.
- **The Apache-2.0 artifacts stay free of Category X code**: the license gate
  complements the build-time `maven-enforcer-plugin` ban documented in
  `doc/licensing.md`.

## 5. Verification Criteria
All tests are offline and run by `python3 -m unittest discover -s scripts/tests`:

- §3.2: `test_unwarned_site_without_trailer_is_reported_twice`,
  `test_warned_site_with_matching_trailer_passes`,
  `test_trailer_count_must_match`, `test_short_warning_is_not_enough`,
  `test_marker_inside_string_literal_is_not_a_warning`,
  `test_concatenated_and_split_flags_are_sites`,
  `test_failed_result_is_rejected`.
- §3.3: `test_literal_stop_is_reported`,
  `test_split_and_templated_forms_are_reported`,
  `test_start_merges_is_allowed`.
- §3.4: `test_category_x_runtime_dependency_is_reported`,
  `test_test_and_provided_scopes_are_allowed`,
  `test_new_permissive_dependency_must_be_in_notices`,
  `test_python_category_x_requirement_is_reported`,
  `test_unparseable_pom_is_reported`.
- §3.5: `test_conflict_marker`, `test_swallowed_exceptions`,
  `test_narrow_cleanup_errors_may_be_ignored`,
  `test_handled_exception_is_fine`, `test_secret_and_debugger`,
  `test_broken_json`, `test_broken_yaml_when_pyyaml_available`.
- §3.6: `test_tests_docs_and_specs_are_exempt`, `test_prose_and_tests_are_exempt`.
- §3.7: `test_exit_codes`.
- Calibration: over the 2.11.0 line (`3c0759b1..7f3102db`), the gates report
  6 unwarned destructive sites and no merge-stop, license or hygiene
  findings (`doc/review_gates.md`).

## 6. Failure Modes & Recovery
- **FM-11.06-1 A destructive site the patterns do not recognise.** For example,
  a statement assembled from variables with no keyword on any one line.
  *Detection*: human review with the `destructive-operation-safety` skill,
  which does not rely on the patterns. *Recovery*: add the shape to
  `DESTRUCTIVE_PATTERNS` together with a test that fails without it.
  *RTO*: one pull request.
- **FM-11.06-2 A false positive blocks a legitimate change.** For example, a
  log message naming `DROP TABLE`. *Detection*: the gate's finding in CI.
  *Recovery*: add a `DESTRUCTIVE:` comment that explains it, and count it in
  the trailer. A recurring false-positive class is fixed in the gate with a
  test, never by deleting the gate. *RTO*: minutes.
- **FM-11.06-3 The base ref is missing in CI** (shallow clone, renamed
  branch). *Detection*: exit status 2 with the git error on stderr; the job
  fails instead of passing. *Recovery*: fetch the base with full history, as
  the workflow does. *RTO*: one re-run.
- **FM-11.06-4 PyYAML is absent.** *Detection*: a `notice:` line on stderr.
  *Recovery*: install it (the workflow does). The other gates are unaffected.
  *RTO*: one re-run.
