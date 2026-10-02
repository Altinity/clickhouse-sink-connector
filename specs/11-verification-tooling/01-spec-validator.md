# Spec 11.01: Automated Specification Validator Architecture

## 1. Executive Summary & Purpose
Specifies the validation tool (`scripts/validate_specs.py`) that makes the spec-driven-development rules mechanically checkable: governance documents and invariant headings exist, every micro-spec has the required sections, the Lean suite is complete and free of unchecked proofs, every path and test a spec cites actually exists, and a source change cannot merge without a spec change. It is run locally and by CI (`.github/workflows/spec-governance.yml`, spec 11.03 §3.2).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `scripts/validate_specs.py`
- **Self-tests**: `scripts/tests/test_validate_specs.py` (`python3 -m unittest discover -s scripts/tests`, or `python3 -m pytest scripts/tests`)
- **Allowlist**: `scripts/spec_validator_allowlist.txt`
- **CI**: `.github/workflows/spec-governance.yml`

---

## 3. Operational Specification

### 3.1 Command line
```
python3 scripts/validate_specs.py [--repo-root PATH] [--allowlist FILE]
                                  [--changed-base REF] [--lake] [--report-refs]
```
Exit code `0` when no error was found, `1` otherwise. Warnings never change the exit code. `--report-refs` prints every Verification citation with its status (`ok`, `missing class`, `missing method`, `missing module`, `missing declaration`).

### 3.2 Validation passes
1. **Governance**: `specs/CONSTITUTION.md`, `specs/README.md` and `specs/SMART_RALPH_PROTOCOL.md` exist; the Constitution contains a heading `Invariant I<n>` for every `n` in `1..13` (`INVARIANT_COUNT`); a heading for `I14` is an error until the constant is raised together with it.
2. **Spec schema**: every domain directory `01-cdc-engine` … `11-verification-tooling` exists and is non-empty; every `*.md` in it has a heading matching each of `Executive Summary`, `Codebase Mapping`, `Invariants`, `Verification`.
3. **Lean suite**: `formal_specs/lean/lean-toolchain`, `lakefile.lean`, `README.md` exist; every `` `Replication.X `` root in the lakefile has a `Replication/X.lean`; every `Replication/*.lean` is listed as a root (a module outside the roots is never built); **every** `*.lean` under `formal_specs/lean/` (outside `.lake/`) is rejected if it contains the token `sorry`, `admit` or `native_decide` anywhere, including comments. With `--lake`, `lake build` is executed in `formal_specs/lean` and a non-zero exit is an error.
4. **Codebase Mapping resolution**: in each spec, within the section(s) whose heading matches `Codebase Mapping` (up to the next heading of the same or higher level), every backticked token is examined:
   - a token containing `/` and no whitespace is a path. `#member`, `:line` and `:line-line` suffixes are stripped; a token containing `...` is resolved by suffix match against the tree; a glob must match at least one file; a token ending in `/` must be a directory; any other token must exist (an unknown token with no recognised extension and no known top-level directory is ignored);
   - a token matching `com.altinity.<packages>.<Class>` must exist as a `.java` file under `sink-connector/src/main/java` or `sink-connector-lightweight/src/main/java`.
   Anything else (config keys, SQL, prose) is ignored.
5. **Verification citations**: within the section(s) whose heading matches `Verification`, every backticked token of the form `SomethingTest` / `SomethingIT`, optionally followed by `.method()` or `#method`, must name a `.java` file of that class under `sink-connector/src/test` or `sink-connector-lightweight/src/test`; when a method is given, the file must declare a method of that name. A token of the form `Replication.Module.name` must be declared (`theorem`/`def`/`lemma`/`abbrev`/`structure`/`inductive`) in `Replication/Module.lean`.
6. **Agent guidance**: `AGENTS.md`, `CLAUDE.md` and `.github/copilot-instructions.md` exist and `AGENTS.md` mentions `specs/` and `Smart Ralph`.
7. **Spec-first gate** (`--changed-base REF`): `git diff --name-only REF...HEAD` is computed. If it contains a file matching `*/src/main/*` or `*.g4` and no file under `specs/` or `formal_specs/`, the run fails and lists the offending files — unless the message of **any** commit in `REF..HEAD` contains `[spec-exempt: <reason>]`, in which case the exemption is printed and the gate passes. The marker is searched across the whole range (not only HEAD) so that a checkout of a synthetic pull-request merge commit — whose auto-generated message cannot carry the marker — still honours a marker on the contributor's commit; the CI workflow additionally checks out the pull request's head SHA. An unresolvable `REF` is an error.

### 3.3 Allowlist
Findings from passes 4 and 5 can be waived through `scripts/spec_validator_allowlist.txt`: a line `specs/<domain>/<file>.md` waives every such finding in that spec; `specs/<domain>/<file>.md | <exact token>` waives one token. A `#` starts a comment only at the start of a line or after whitespace, so hash-form tokens such as `FooTest#method` can be waived. Waived findings are printed as warnings prefixed `(allowlisted)`; an entry that waives nothing is reported as `allowlist entry no longer needed`. The file exists to let spec files owned by in-flight changes pass while they are being rewritten; it is expected to shrink to empty.

### 3.4 What the validator does not do
It does not parse Java or Lean; method presence is a regex on the declaring file, Lean declarations are matched by name at line start. It does not check that a cited test actually exercises the claim next to it, does not verify line numbers quoted in specs, and does not inspect code comments or Javadoc. `lake build` is only run with `--lake`; CI runs it as a separate step.

---

## 4. Invariants Preserved
- **Spec Integrity**: malformed specs, dangling code or test citations, unbuilt or unproven Lean modules and spec-less source changes are rejected before merge.

---

## 5. Verification Criteria
- `python3 scripts/validate_specs.py` exits 0 on the 2.11.0 tree and non-zero when any check above fails.
- `scripts/tests/test_validate_specs.py` covers every pass with temporary-directory fixtures: invariant headings, required sections, unlisted/`sorry`/`admit`/`native_decide` Lean modules, lakefile roots without files, `--lake` failure via a stubbed `lake`, path / abbreviated-path / directory / class-name resolution, missing test classes and methods, Lean declaration references, file- and token-level allowlisting with stale-entry warnings, and the spec-first gate (source-only change fails, grammar change fails, spec or Lean change passes, `[spec-exempt: ...]` passes, test-only change passes, unknown ref errors) against a real temporary git repository. The fixture's git commands run with `maintenance.auto=false` and `gc.auto=0`, so no detached `git maintenance run --auto` / `git gc --auto` process outlives a test and writes under `.git/objects` while the temporary repository is being removed (git >= 2.46 detaches that helper unconditionally; on git 2.55.0 the race made `tearDown` fail with `ENOTEMPTY`); `ChangedBaseTests.test_fixture_git_spawns_no_background_maintenance` pins this by asserting, from a trace2 event log, that a fixture commit starts no child git process.

---

## 6. Failure Modes & Recovery
The validator is an offline, read-only CI gate: it never touches a running connector, so none of its failures can lose or corrupt replicated data. Its dangerous failures are the silent ones — a check that passes on a spec, citation or proof that is wrong — because every other spec's guarantee is only as good as this gate. Loud failures (a finding, a crash) are recovered by editing the offending file and re-running, which takes about a second.

- **FM-11.01-1 The spec this validator enforces is itself wrong, and passes**
  - **Trigger**: a spec states a guarantee the code does not have. The validator checks shape and citations, never meaning (§3.4).
  - **Behaviour**: passes. Concrete case in this very file: §3.2 says `INVARIANT_COUNT` covers `1..13`, lists the domains `01`…`11` and names four required sections, while `scripts/validate_specs.py` has `INVARIANT_COUNT = 15`, twelve `EXPECTED_DOMAINS`, the fifth required section `Failure Modes` (with the `FAILURE_MODE_FIELDS` rule) and pass 8 (`check_bookkeeping_scans`, Invariant I14). The validator passes this stale text.
  - **Detection**: none. It surfaces only when a human reads the spec against the code.
  - **Blast radius**: none on the data path. Reviewers and agents trust a stale contract, so a regression against the real rules can be argued as "per spec".
  - **Recovery**: correct the §3.2 text in a spec change. The general fix is a self-test that compares the constants the spec quotes with the module's own.
  - **RTO**: unbounded while it stays silent. Once seen, the edit takes minutes.
  - **Test**: GAP: a self-test that parses §3.2 of this spec and asserts it names `INVARIANT_COUNT`, every `EXPECTED_DOMAINS` entry and every `REQUIRED_SPEC_SECTIONS` entry with their current values.
  - **DEFECT**: §3.2 misstates three of the validator's own constants and omits two passes, and nothing detects it.

- **FM-11.01-2 A failure-mode entry without Detection, Recovery or RTO passes**
  - **Trigger**: a `Failure Modes` section in which only some entries state the three fields.
  - **Behaviour**: `check_spec_schema` searches the whole section body once for each word in `FAILURE_MODE_FIELDS`, so one complete entry satisfies the rule for all of them.
  - **Detection**: none for the incomplete entry. A section with none of the words is reported as `'Failure Modes' section lacks the field(s) ...`.
  - **Blast radius**: a failure mode with no declared RTO or recovery passes as declared, which weakens Invariant I15 item 4.
  - **Recovery**: review by hand. The fix is to split the section into entries (list items that start with `**FM-`) and require the three fields in each one.
  - **RTO**: validator run time, about 1.2 s (measured: `time python3 scripts/validate_specs.py` on the dev host, 2026-09-30).
  - **Test**: `scripts/tests/test_validate_specs_failure_modes.py::FailureModesSectionTests::test_every_failure_mode_entry_must_state_its_rto` (skipped, DEFECT). The section-level rule is pinned by `scripts/tests/test_validate_specs.py::GovernanceTests::test_failure_modes_section_must_state_detection_recovery_and_rto`.
  - **DEFECT**: field presence is checked per section, not per failure mode.

- **FM-11.01-3 A deleted test still "exists" because its name appears in a comment or a call**
  - **Trigger**: a test method is removed or renamed, but its old name survives in the class, in a comment (`// replaces the old helper testGone()`) or in a call site (`return testGone();`).
  - **Behaviour**: `_method_declared` runs the regex `<word> <method>(` over the file text. A comment word or `return` in front of the name matches, so the citation is reported `ok`. A method that is `@Disabled` also counts as existing. That is intended, because DEFECT tests are cited that way.
  - **Detection**: none.
  - **Blast radius**: a spec keeps citing a test that no longer asserts anything.
  - **Recovery**: remove the citation, or restore the test.
  - **RTO**: about 1.2 s per validator run once the file is fixed.
  - **Test**: `scripts/tests/test_validate_specs_failure_modes.py::CitedMethodTests::test_method_named_only_in_a_comment_is_not_a_declaration` (skipped, DEFECT).
  - **DEFECT**: method presence is decided by a text regex that matches comments and calls.

- **FM-11.01-4 Python test citations are never resolved**
  - **Trigger**: specs 11.02, 11.04 and 11.05 cite Python tests (`path.py::Class::test`). One of them is renamed or deleted.
  - **Behaviour**: pass 5 resolves only Java `*Test`/`*IT` names (`TEST_REF_RE`) and `Replication.*` Lean names. Any other backticked token is ignored.
  - **Detection**: none.
  - **Blast radius**: the tooling specs, which declare how the replica is verified and repaired, can cite tests that do not exist.
  - **Recovery**: grep the cited `path.py::Class::test` by hand. The fix is to resolve `path.py::Class::test` tokens against the file.
  - **RTO**: minutes by hand.
  - **Test**: GAP: a validator self-test in which a Failure Modes section cites `x/tests/test_y.py::C::test_missing` and the run reports it.
  - **DEFECT**: dangling Python test citations pass the gate.

- **FM-11.01-5 A heading that merely mentions failure modes is held to the I15 rule**
  - **Trigger**: any heading matching `Failure Modes`, case-insensitive. For example, spec 11.04 §3.6 "Failure modes the design guards against".
  - **Behaviour**: `sections()` returns that body too, and it must also contain the three field words.
  - **Detection**: loud. `'Failure Modes' section lacks the field(s) Detection, Recovery, RTO`, exit 1, on the next run.
  - **Blast radius**: a false positive that blocks the merge. No data risk.
  - **Recovery**: add the fields (or a pointer sentence that carries them) to that section, or rename the heading.
  - **RTO**: minutes.
  - **Test**: `scripts/tests/test_validate_specs.py::GovernanceTests::test_failure_modes_section_must_state_detection_recovery_and_rto`.

- **FM-11.01-6 Unreadable spec file**
  - **Trigger**: a spec that is not UTF-8 (a pasted binary, a bad editor encoding).
  - **Behaviour**: `Path.read_text(encoding="utf-8")` raises `UnicodeDecodeError`. The run stops with a traceback, and the later passes never run.
  - **Detection**: loud. Python traceback and non-zero exit on that run.
  - **Blast radius**: CI is blocked until the file is fixed. Nothing is silently skipped.
  - **Recovery**: re-encode or restore the file, then re-run.
  - **RTO**: minutes.
  - **Test**: `scripts/tests/test_validate_specs_failure_modes.py::RobustnessTests::test_non_utf8_spec_aborts_the_run_loudly`.

- **FM-11.01-7 Spec-first gate on a shallow checkout**
  - **Trigger**: CI or a developer runs `--changed-base` in a clone without the merge base.
  - **Behaviour**: `check_changed_base` reports the git error and, when `git rev-parse --is-shallow-repository` is true, names the cause (`the checkout is SHALLOW ...`).
  - **Detection**: loud. Error line and exit 1.
  - **Blast radius**: the PR is blocked. The gate never passes by accident.
  - **Recovery**: fetch the base ref with full history (the workflow already uses `fetch-depth: 0`), then re-run.
  - **RTO**: one CI re-run, a few minutes.
  - **Test**: `scripts/tests/test_validate_specs.py::ChangedBaseTests::test_shallow_checkout_without_merge_base_names_the_cause`.

- **FM-11.01-8 The I14 scan misses a query that is not built from literals**
  - **Trigger**: an aggregate over a replicated table built with `String.format`, a `StringBuilder` or a variable-held keyword.
  - **Behaviour**: `check_bookkeeping_scans` sees only runs of adjacent Java string literals. A dynamic query passes unmarked.
  - **Detection**: none (see spec 10.06 for the invariant itself).
  - **Blast radius**: a table-scanning bookkeeping query can be merged without an `I14-scan-allowed` review.
  - **Recovery**: code review.
  - **RTO**: n/a (design-time).
  - **Test**: GAP: a self-test with `String.format("SELECT max(%s) FROM %s", ...)` asserting a finding.
  - **DEFECT**: the I14 gate is blind to every query that is not assembled from literals.

Summary: 8 failure modes, 5 DEFECT, 3 GAP.
