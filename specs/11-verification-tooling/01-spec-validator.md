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
7. **Spec-first gate** (`--changed-base REF`): `git diff --name-only REF...HEAD` is computed. If it contains a file matching `*/src/main/*` or `*.g4` and no file under `specs/` or `formal_specs/`, the run fails and lists the offending files — unless the HEAD commit message contains `[spec-exempt: <reason>]`, in which case the exemption is printed and the gate passes. An unresolvable `REF` is an error.

### 3.3 Allowlist
Findings from passes 4 and 5 can be waived through `scripts/spec_validator_allowlist.txt`: a line `specs/<domain>/<file>.md` waives every such finding in that spec; `specs/<domain>/<file>.md | <exact token>` waives one token. Waived findings are printed as warnings prefixed `(allowlisted)`; an entry that waives nothing is reported as `allowlist entry no longer needed`. The file exists to let spec files owned by in-flight changes pass while they are being rewritten; it is expected to shrink to empty.

### 3.4 What the validator does not do
It does not parse Java or Lean; method presence is a regex on the declaring file, Lean declarations are matched by name at line start. It does not check that a cited test actually exercises the claim next to it, does not verify line numbers quoted in specs, and does not inspect code comments or Javadoc. `lake build` is only run with `--lake`; CI runs it as a separate step.

---

## 4. Invariants Preserved
- **Spec Integrity**: malformed specs, dangling code or test citations, unbuilt or unproven Lean modules and spec-less source changes are rejected before merge.

---

## 5. Verification Criteria
- `python3 scripts/validate_specs.py` exits 0 on the 2.11.0 tree and non-zero when any check above fails.
- `scripts/tests/test_validate_specs.py` covers every pass with temporary-directory fixtures: invariant headings, required sections, unlisted/`sorry`/`admit`/`native_decide` Lean modules, lakefile roots without files, `--lake` failure via a stubbed `lake`, path / abbreviated-path / directory / class-name resolution, missing test classes and methods, Lean declaration references, file- and token-level allowlisting with stale-entry warnings, and the spec-first gate (source-only change fails, grammar change fails, spec or Lean change passes, `[spec-exempt: ...]` passes, test-only change passes, unknown ref errors) against a real temporary git repository.
